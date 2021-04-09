package dev.kameleon.version;

import dev.kameleon.version.metadata.MavenMetadata;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.ext.web.client.WebClient;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class VersionService {

    private final static List<String> QUARKUS_DEFAULT = List.of("1.7.0");
    private final static List<String> CLASSIC_DEFAULT = List.of("3.9.0");
    private final static String QUARKUS_VERSION = "1.12.2.Final";

    @ConfigProperty(name = "camel.versions.lts.classic")
    String ltsClassic;

    @Inject
    Vertx vertx;

    private WebClient client;

    @PostConstruct
    void initialize() {
        this.client = WebClient.create(vertx,
                new WebClientOptions().setDefaultHost("search.maven.org").setDefaultPort(443).setSsl(true)
                        .setTrustAll(true));
    }

    public Uni<List<String>> getVersions(String type) {
        String url = ConfigProvider.getConfig().getValue("kameleon.archetype.metadata." + type, String.class);
        return client.getAbs(url).send().map(resp -> {
            if (resp.statusCode() == 200) {
                return getVersionsFromMetadata(resp.bodyAsString());
            } else {
                return "quarkus".equals(type) ? QUARKUS_DEFAULT : CLASSIC_DEFAULT;
            }
        });
    }

    private List<String> getVersionsFromMetadata(String m) {
        List<String> result = new ArrayList<>();
        try {
            JAXBContext jaxbContext = JAXBContext.newInstance(MavenMetadata.class);
            Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
            StringReader xmlReader = new StringReader(m);
            MavenMetadata metadata = (MavenMetadata) unmarshaller.unmarshal(xmlReader);

            List<ComparableVersion> allVersions = metadata.versioning.versions.version.stream()
                    .filter(v -> !v.contains("-RC") && !v.contains("-M") && !v.contains("-CR"))
                    .map(s -> new ComparableVersion(s))
                    .sorted(Comparator.reverseOrder()).collect(Collectors.toList());

            allVersions.forEach(s -> {
                if (!result.stream().filter(s1 -> s1.startsWith(s.toString().substring(0, 1))).findFirst().isPresent()){
                    result.add(s.toString());
                } else if (s.toString().startsWith(ltsClassic) && !result.stream().filter(s1 -> s1.startsWith(ltsClassic)).findFirst().isPresent() ){
                    result.add(s.toString());
                }
            });
        } catch (JAXBException e) {
            e.printStackTrace();
        }
        return result;
    }

    public String getQuarkusVersion(String camelQuarkusVersion) throws Exception {
        String url = "https://raw.githubusercontent.com/apache/camel-quarkus/"+camelQuarkusVersion+"/pom.xml";
        String pom = client.getAbs(url).send().map(resp -> {
            if (resp.statusCode() == 200) {
                return resp.bodyAsString();
            } else {
                return QUARKUS_VERSION;
            }
        }).subscribe().asCompletionStage().get();
        MavenXpp3Reader reader = new MavenXpp3Reader();
        Model model = reader.read(new StringReader(pom));
        return model.getProperties().getProperty("quarkus.version");
    }
}