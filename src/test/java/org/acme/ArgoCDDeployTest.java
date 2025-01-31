package org.acme;

import com.dajudge.kindcontainer.KindContainer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.quarkiverse.argocd.v1alpha1.Application;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.acme.ArgocdResourceGenerator.populateApplication;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import static org.hamcrest.MatcherAssert.assertThat;


@Testcontainers
public class ArgoCDDeployTest {
    private static final Logger LOG = LoggerFactory.getLogger(ArgoCDDeployTest.class);

    @Container
    public static final KindContainer<?> KUBE = new KindContainer<>();

    private static final String ARGOCD_NS = "argocd";

    private static final String ARGOCD_DEPLOYMENT_SERVER_NAME = "argocd-server";
    private static final String ARGOCD_DEPLOYMENT_REDIS_NAME = "argocd-redis";
    private static final String ARGOCD_DEPLOYMENT_REPO_SERVER_NAME = "argocd-repo-server";
    private static final String ARGOCD_DEPLOYMENT_DEX_SERVER_NAME = "argocd-dex-server";
    private static final String ARGOCD_DEPLOYMENT_NOTIFICATION_CONTROLLER_NAME = "argocd-notifications-controller";
    private static final String ARGOCD_DEPLOYMENT_APPLICATIONSET_CONTROLLER_NAME = "argocd-applicationset-controller";
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
            LOG.info("Deployment available: {}", name);
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
            LOG.info("Pod ready: {}", name);
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
            var res = client.resource(item).inNamespace("argocd");
            res.create();
            //res.waitUntilReady(5, TimeUnit.SECONDS);
            assertNotNull(res);
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
        checkDeploymentReady(ARGOCD_DEPLOYMENT_REDIS_NAME);
        checkDeploymentReady(ARGOCD_DEPLOYMENT_REPO_SERVER_NAME);
        checkDeploymentReady(ARGOCD_DEPLOYMENT_DEX_SERVER_NAME);
        checkDeploymentReady(ARGOCD_DEPLOYMENT_NOTIFICATION_CONTROLLER_NAME);
        checkDeploymentReady(ARGOCD_DEPLOYMENT_APPLICATIONSET_CONTROLLER_NAME);
        // Checking the pod created by the StatefulSet
        checkPodReady(ARGOCD_POD_APP_CONTROLLER_NAME);

        // Populate the Argocd resources
        Config config = new Config();
        config.setDestinationNamespace("argocd");
        config.setApplicationName("test-1");
        config.setApplicationNamespace("argocd");

        Application app = populateApplication(config);
        Application argocdApp = client.resource(app)
            .inNamespace(ARGOCD_NS)
            .waitUntilCondition(s -> s.getStatus().getHealth().getStatus().equals("Healthy"),30, TimeUnit.SECONDS);
        assertThat(argocdApp.getStatus().getHealth().getStatus(), is("Healthy"));
    }
}
