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
import io.quarkus.logging.Log;
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

    private void waitTillPodReady(String ns, String name) {
        client.resources(Pod.class)
            .inNamespace(ns)
            .withName(name)
            .waitUntilReady(30, TimeUnit.SECONDS);
        LOG.info("Pod: {} ready in {}", name, ns);
    }

    private void waitTillPodByLabelReady(String ns, String key, String value) {
        client.resources(Pod.class)
            .inNamespace(ns)
            .withLabel(key, value)
            .waitUntilReady(30, TimeUnit.SECONDS);
        LOG.info("Pod: {} ready in {}", value, ns);
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

        // Waiting till the pods are ready/running ...
        waitTillPodReady(ARGOCD_NS, ARGOCD_POD_APP_CONTROLLER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name","argocd-server");

        // Populate the Argocd resources
        Config config = new Config();
        config.setDestinationNamespace("argocd");
        config.setApplicationName("test-1");
        config.setApplicationNamespace("argocd");
        config.setGitRevision("master");

        client.resource(populateApplication(config))
            .inNamespace(ARGOCD_NS)
            .create();

        client.resources(Application.class)
            .inNamespace(ARGOCD_NS)
            .withName(config.getApplicationName())
            .waitUntilCondition(a ->
                a != null &&
                a.getStatus() != null &&
                a.getStatus().getHealth() != null &&
                a.getStatus().getHealth().getStatus().equals("Healthy") &&
                a.getStatus().getSync().getStatus().equals("Synced"),1800, TimeUnit.SECONDS);

        // TODO: Investigate why the pod is never created as status of Sync never become => synced
        // or if error is reported by argocd server/controller
        // journalctl -xeu kubelet
        // alias k=kubectl

        // Argocd Server
        // time="2025-01-31T13:16:27Z" level=warning msg="Failed to resync revoked tokens. retrying again in 1 minute: dial tcp 10.245.14.250:6379: connect: connection refused"

        // Application Controller
        // time="2025-01-31T13:16:23Z" level=info msg="Normalized app spec: {\"status\":{\"conditions\":[{\"lastTransitionTime\":\"2025-01-31T13:16:23Z\",\"message\":\"Failed to load target state: failed to generate manifest for source 1 of 1: rpc error: code = Unavailable desc = connection error: desc = \\\"transport: Error while dialing: dial tcp 10.245.254.103:8081: connect: connection refused\\\"\",\"type\":\"ComparisonError\"}]}}" app-namespace=argocd app-qualified-name=argocd/test-1 application=test-1 project=default
        // time="2025-01-31T13:16:26Z" level=error msg="Failed to cache app resources: error setting app resource tree: dial tcp 10.245.14.250:6379: connect: connection refused" app-namespace=argocd app-qualified-name=argocd/test-1 application=test-1 comparison-level=3 dedup_ms=0 dest-name= dest-namespace=argocd dest-server="https://kubernetes.default.svc" diff_ms=0 git_ms=360 health_ms=0 live_ms=0 project=default settings_ms=0 sync_ms=0
        // time="2025-01-31T13:16:26Z" level=info msg="Skipping auto-sync: application status is Unknown" app-namespace=argocd app-qualified-name=argocd/test-1 application=test-1 project=default
        // time="2025-01-31T13:16:26Z" level=info msg="Updated sync status:  -> Unknown" application=test-1 dest-namespace=argocd dest-server="https://kubernetes.default.svc" reason=ResourceUpdated type=Normal
        // time="2025-01-31T13:16:26Z" level=info msg="Updated health status:  -> Healthy" application=test-1 dest-namespace=argocd dest-server="https://kubernetes.default.svc" reason=ResourceUpdated type=Normal
        // time="2025-01-31T13:16:26Z" level=info msg="Update successful" app-namespace=argocd app-qualified-name=argocd/test-1 application=test-1 project=default
        // time="2025-01-31T13:16:26Z" level=info msg="Reconciliation completed" app-namespace=argocd app-qualified-name=argocd/test-1 app_status_update_ms=0 application=test-1 auto_sync_ms=0 compare_app_state_ms=360 comparison-level=3 comparison_with_nothing_ms=0 dedup_ms=0 dest-name= dest-namespace=argocd dest-server="https://kubernetes.default.svc" diff_ms=0 git_ms=360 health_ms=0 live_ms=0 normalize_application_ms=2 patch_ms=3 persist_app_status_ms=7 process_finalizers_ms=0 project=default refresh_app_conditions_ms=0 set_app_managed_resources_ms=3156 setop_ms=0 settings_ms=0 sync_ms=0 time_ms=3527


        Application app = client.resources(Application.class)
            .inNamespace(ARGOCD_NS)
            .withName(config.getApplicationName()).get();

        LOG.info("Argocd Application is ready: {}", config.getApplicationName());
        LOG.info(client.getKubernetesSerialization().asYaml(app));
    }
}
