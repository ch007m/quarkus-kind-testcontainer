package org.acme;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static io.restassured.RestAssured.given;
import static org.acme.ArgoCDDeployTest.KUBE;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class GreetingResourceTest {
    private long timeOut;
    private final String ARGOCD_NS = "argocd";

    final KubernetesClient client = new DefaultKubernetesClient();

    //@Test
    public void filterResources() {
        List<HasMetadata> items = client.load(getClass().getResourceAsStream("/argocd.yml")).items();
        assertEquals(59, items.size());

        // Deploy the different resources: Service, CRD, Deployment, ConfigMap
        List<HasMetadata> filteredItems = items.stream()
            .filter(r -> !(r instanceof Deployment &&
                ("argocd-dex-server".equals(r.getMetadata().getName()) || "argocd-notifications-controller".equals(r.getMetadata().getName()))))
            .collect(Collectors.toList());

        assertEquals(57, filteredItems.size());

        for (HasMetadata item : filteredItems) {
            var res = client.resource(item).inNamespace("argocd");
            res.create();
            assertNotNull(res);
        };
    }

    @Test
    public void testGreeting() {
        if (System.getenv("argocd.resource.timeout") != null) {
            timeOut = Long.parseLong(System.getenv("argocd.resource.timeout"));
            System.out.println("Timeout: " + timeOut);
        }
    }

    @Test
    void testHelloEndpoint() {
        given()
          .when().get("/hello")
          .then()
             .statusCode(200)
             .body(is("Hello from Quarkus REST"));
    }

}