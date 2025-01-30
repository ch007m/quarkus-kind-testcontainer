package org.acme;

import com.dajudge.kindcontainer.KindContainer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.hamcrest.MatcherAssert.assertThat;


@Testcontainers
public class ArgoCDDeployTest {
    @Container
    public static final KindContainer<?> KUBE = new KindContainer<>();

    private static final String ARGOCD_NS = "argocd";
    private static final String ARGOCD_DEPLOYMENT_SERVER_NAME = "argocd-server";
    private static final String ARGOCD_POD_APP_CONTROLLER_NAME = "argocd-application-controller-0";
    private static final String DEPLOYMENT_STATUS_AVAILABLE = "Available";
    private static final String POD_STATUS_AVAILABLE = "Ready";

    final KubernetesClient client = new DefaultKubernetesClient(fromKubeconfig(KUBE.getKubeconfig()));

    // String argocdUrl = "https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml";

    private void checkDeploymentReady(String name) {
        await().atMost(2, TimeUnit.MINUTES).untilAsserted(() -> {
            var resource = client.resources(Deployment.class)
                .inNamespace(ARGOCD_NS)
                .withName(name)
                .get();
            assertThat(resource, is(notNullValue()));
            assertThat(resource.getStatus().getConditions().stream().anyMatch(c -> c.getType().equals(DEPLOYMENT_STATUS_AVAILABLE)), is(true));
        });
    }

    private void checkPodReady(String name) {
        await().atMost(2, TimeUnit.MINUTES).untilAsserted(() -> {
            var resource = client.resources(Pod.class)
                .inNamespace(ARGOCD_NS)
                .withName(name)
                .get();
            assertThat(resource, is(notNullValue()));
            assertThat(resource.getStatus().getConditions().stream().anyMatch(c -> c.getType().equals(POD_STATUS_AVAILABLE)), is(true));
        });
    }

    @Test
    public void deployArgoCD() {
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
        await().ignoreException(NullPointerException.class).atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            // check that we create the argocd-server deployment
            final var deployment = client.apps().deployments()
                .inNamespace(ARGOCD_NS)
                .withName(ARGOCD_DEPLOYMENT_SERVER_NAME).get();

            final var maybeFirstContainer = deployment.getSpec().getTemplate().getSpec().getContainers()
                .stream()
                .findFirst();

            assertThat(maybeFirstContainer.isPresent(), is(true));
            final var firstContainer = maybeFirstContainer.get();

            // TODO: To be reviewed
            assertThat(firstContainer.getImage(), is("quay.io/argoproj/argocd:v2.13.2"));
        });

        checkDeploymentReady(ARGOCD_DEPLOYMENT_SERVER_NAME);
        checkPodReady(ARGOCD_POD_APP_CONTROLLER_NAME);
    }
}
