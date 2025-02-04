package org.acme;

import com.dajudge.kindcontainer.KindContainer;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.argocd.v1alpha1.Application;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.acme.ArgocdResourceGenerator.populateApplication;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;


@Testcontainers
public class ArgoCDDeployTest {
    private static final Logger LOG = LoggerFactory.getLogger(ArgoCDDeployTest.class);

    @Container
    public static final KindContainer<?> KUBE = new KindContainer<>();

    private static final String ARGOCD_NS = "argocd";

    private static final String ARGOCD_DEPLOYMENT_SERVER_NAME = "argocd-server";

    private static final String ARGOCD_POD_APP_CONTROLLER_NAME = "argocd-application-controller-0";
    private static final String ARGOCD_POD_APPLICATIONSET_CONTROLLER_NAME = "argocd-applicationset-controller";
    private static final String ARGOCD_POD_SERVER_NAME = "argocd-server";
    private static final String ARGOCD_POD_REDIS_NAME = "argocd-redis";
    private static final String ARGOCD_POD_REPO_SERVER_NAME = "argocd-repo-server";
    private static final String ARGOCD_POD_DEX_SERVER_NAME = "argocd-dex-server";
    private static final String ARGOCD_POD_NOTIFICATION_CONTROLLER_NAME = "argocd-notifications-controller";

    private static final String DEPLOYMENT_STATUS_AVAILABLE = "Available";

    final KubernetesClient client = new DefaultKubernetesClient(fromKubeconfig(KUBE.getKubeconfig()));

    private void waitTillPodReady(String ns, String name) {
        client.resources(Pod.class)
            .inNamespace(ns)
            .withName(name)
            .waitUntilReady(180, TimeUnit.SECONDS);
        LOG.info("Pod: {} ready in {}", name, ns);
    }

    private void waitTillPodByLabelReady(String ns, String key, String value) {
        client.resources(Pod.class)
            .inNamespace(ns)
            .withLabel(key, value)
            .waitUntilReady(180, TimeUnit.SECONDS);
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
        };

        // Waiting till the pods are ready/running ...
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_REDIS_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_REPO_SERVER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_DEX_SERVER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_SERVER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_NOTIFICATION_CONTROLLER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_APPLICATIONSET_CONTROLLER_NAME);
        waitTillPodReady(ARGOCD_NS, ARGOCD_POD_APP_CONTROLLER_NAME);

        // Populate the Argocd resources
        Config config = new Config();
        config.setDestinationNamespace("argocd");
        config.setApplicationName("test-1");
        config.setApplicationNamespace("argocd");
        config.setGitRevision("master");

        client.resource(populateApplication(config))
            .inNamespace(ARGOCD_NS)
            .create();

        // Sleep 5 seconds
        // If we don't pause here the program, then we got this error: https://github.com/ch007m/quarkus-kind-testcontainer/issues/1#issuecomment-2630766957
        // as Jackson cannot process the Application object as it do not include the Operation class and related
        // Such Operation is added by Argo cd when it performs sync or health operations and
        // here: status is not yet healthy and synced is out-of-sync
        /*
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }*/

        LOG.info("Checking when Argocd Application will be Healthy");
        try {
            client.resources(Application.class)
                .inNamespace(ARGOCD_NS)
                .withName(config.getApplicationName())
                .waitUntilCondition(a ->
                    a != null &&
                        a.getStatus() != null &&
                        a.getStatus().getHealth() != null &&
                        a.getStatus().getHealth().getStatus() != null &&
                        a.getStatus().getHealth().getStatus().equals("Healthy"), 3600, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error(client.getKubernetesSerialization().asYaml(client.genericKubernetesResources("argoproj.io/v1alpha1", "Application").inNamespace(ARGOCD_NS).withName(config.getApplicationName()).get()));
        }
        LOG.info("Argocd Application: {} healthy", config.getApplicationName());

        LOG.info("Checking now when Argocd Application will be synced");
        try {
        client.resources(Application.class)
            .inNamespace(ARGOCD_NS)
            .withName(config.getApplicationName())
            .waitUntilCondition(a ->
                a != null &&
                a.getStatus() != null &&
                a.getStatus().getSync() != null &&
                a.getStatus().getSync().getStatus() != null &&
                a.getStatus().getSync().getStatus().equals("Synced"), 3600, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error(client.getKubernetesSerialization().asYaml(client.genericKubernetesResources("argoproj.io/v1alpha1", "Application").inNamespace(ARGOCD_NS).withName(config.getApplicationName()).get()));
        }
        LOG.info("Argocd Application: {} synced", config.getApplicationName());

        Application app = client.resources(Application.class)
            .inNamespace(ARGOCD_NS)
            .withName(config.getApplicationName()).get();
        LOG.warn(client.getKubernetesSerialization().asYaml(app));
    }
}
