package org.acme;
import com.dajudge.kindcontainer.KindContainer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.awaitility.Awaitility.await;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;


@Testcontainers
public class ArgoCDDeployTest {
    @Container
    public static final KindContainer<?> KUBE = new KindContainer<>();

    private static final String ARGOCD_NS = "argocd";
    private static final String ARGOCD_DEPLOY_SERVER_NAME = "argocd-server";

    // String argocdUrl = "https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml";

    @Test
    public void deployArgoCD() {
        try (final KubernetesClient client = new DefaultKubernetesClient(fromKubeconfig(KUBE.getKubeconfig()))) {
            List<HasMetadata> items = client.load(getClass().getResourceAsStream("/argocd.yml")).items();
            assertEquals(59, items.size());

            // Let's create the argocd namespace to deploy the resources
            client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(ARGOCD_NS).endMetadata().build()).create();

            // Deploy the different resources: Service, CRD, Deployment, ConfigMap
            for (HasMetadata item : items) {
                HasMetadata response = client.resource(item).inNamespace("argocd").create();
                assertNotNull(response);
            }

            // Wait till pod is running, etc
            await().ignoreException(NullPointerException.class).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
                // check that we create the argocd-server deployment
                final var deployment = client.apps().deployments()
                    .inNamespace(ARGOCD_NS)
                    .withName(ARGOCD_DEPLOY_SERVER_NAME).get();

                final var maybeFirstContainer = deployment.getSpec().getTemplate().getSpec().getContainers()
                    .stream()
                    .findFirst();

                assertThat(maybeFirstContainer.isPresent(), is(true));
                final var firstContainer = maybeFirstContainer.get();

                // TODO: To be reviewed
                assertThat(firstContainer.getImage(), is("quay.io/argoproj/argocd:v2.13.2"));
            });
        }
    }
}
