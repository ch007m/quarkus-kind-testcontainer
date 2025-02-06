package org.acme;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.argocd.v1alpha1.Application;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.acme.ArgocdResourceGenerator.populateApplication;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ArgoCDCITest extends BaseContainer {
    private static final Logger LOG = LoggerFactory.getLogger(ArgoCDCITest.class);

    private static final String ARGOCD_NS = "argocd";

    private static final String ARGOCD_POD_APP_CONTROLLER_NAME = "argocd-application-controller-0";
    private static final String ARGOCD_POD_APPLICATIONSET_CONTROLLER_NAME = "argocd-applicationset-controller";
    private static final String ARGOCD_POD_SERVER_NAME = "argocd-server";
    private static final String ARGOCD_POD_REDIS_NAME = "argocd-redis";
    private static final String ARGOCD_POD_REPO_SERVER_NAME = "argocd-repo-server";
    private static final String ARGOCD_POD_DEX_SERVER_NAME = "argocd-dex-server";
    private static final String ARGOCD_POD_NOTIFICATION_CONTROLLER_NAME = "argocd-notifications-controller";

    public static long timeOut = 1;

    final static KubernetesClient client = new DefaultKubernetesClient(fromKubeconfig(KIND.getKubeconfig()));

    private static void waitTillPodReady(String ns, String name) {
        client.resources(Pod.class)
            .inNamespace(ns)
            .withName(name)
            .waitUntilReady(timeOut, TimeUnit.SECONDS);
        LOG.info("Pod: {} ready in {}", name, ns);
    }

    private static void waitTillPodByLabelReady(String ns, String key, String value) {
        client.resources(Pod.class)
            .inNamespace(ns)
            .withLabel(key, value)
            .waitUntilReady(timeOut, TimeUnit.SECONDS);
        LOG.info("Pod: {} ready in {}", value, ns);
    }

    @BeforeAll
    public static void deployArgocd() {
        if (System.getenv("ARGOCD_RESOURCE_TIMEOUT") != null) {
            timeOut = Long.parseLong(System.getenv("ARGOCD_RESOURCE_TIMEOUT"));
            LOG.info("Kubernetes waiting resource - Timeout: {}", timeOut);
        }

        List<HasMetadata> items = client.load(ArgoCDCITest.class.getResourceAsStream("/argocd.yml")).items();

        LOG.info("Creating the argocd namespace");
        client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(ARGOCD_NS).endMetadata().build()).create();

        // Deploy the different resources: Service, CRD, Deployment, ConfigMap except the Argocd Notification and Dex server
        List<HasMetadata> filteredItems = items.stream()
            .filter(r -> !(r instanceof Deployment &&
                (ARGOCD_POD_DEX_SERVER_NAME.equals(r.getMetadata().getName()) || ARGOCD_POD_NOTIFICATION_CONTROLLER_NAME.equals(r.getMetadata().getName()))))
            .collect(Collectors.toList());
        assertEquals(57, filteredItems.size());

        LOG.info("Deploying the argocd resources ...");
        for (HasMetadata item : filteredItems) {
            var res = client.resource(item).inNamespace("argocd");
            res.create();
            assertNotNull(res);
        };

        // Waiting till the pods are ready/running ...
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_REDIS_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_REPO_SERVER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_SERVER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_APPLICATIONSET_CONTROLLER_NAME);
        waitTillPodReady(ARGOCD_NS, ARGOCD_POD_APP_CONTROLLER_NAME);
        //waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_NOTIFICATION_CONTROLLER_NAME);
        //waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_POD_DEX_SERVER_NAME);
    }

    @Test
    public void testCaseOne() {

        // Populate the Argocd Application resource
        // Namespace: argocd control's plane - argocd
        Config config = new Config();
        config.setDestinationNamespace("argocd");
        config.setApplicationName("test-1");
        config.setApplicationNamespace("argocd");
        config.setGitRevision("master");

        client.resource(populateApplication(config))
            .inNamespace(ARGOCD_NS)
            .create();

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
