package org.acme;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.argocd.v1alpha1.AppProject;
import io.quarkiverse.argocd.v1alpha1.Application;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.acme.ArgocdResourceGenerator.populateApplication;
import static org.acme.ArgocdResourceGenerator.populateProject;
import static org.junit.Assert.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ArgoCDCITest extends BaseContainer {
    private static final Logger LOG = LoggerFactory.getLogger(ArgoCDCITest.class);

    private static final String ARGOCD_NS = "argocd";

    private static final String ARGOCD_APP_CONTROLLER_NAME = "argocd-application-controller";
    private static final String ARGOCD_APPLICATIONSET_CONTROLLER_NAME = "argocd-applicationset-controller";
    private static final String ARGOCD_SERVER_NAME = "argocd-server";
    private static final String ARGOCD_REDIS_NAME = "argocd-redis";
    private static final String ARGOCD_REPO_SERVER_NAME = "argocd-repo-server";
    private static final String ARGOCD_DEX_SERVER_NAME = "argocd-dex-server";
    private static final String ARGOCD_NOTIFICATION_CONTROLLER_NAME = "argocd-notifications-controller";
    private static final String ARGOCD_CONFIGMAP_PARAMS_NAME = "argocd-cmd-params-cm";

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
                (ARGOCD_DEX_SERVER_NAME.equals(r.getMetadata().getName()) || ARGOCD_NOTIFICATION_CONTROLLER_NAME.equals(r.getMetadata().getName()))))
            .collect(Collectors.toList());
        assertEquals(57, filteredItems.size());

        LOG.info("Deploying the argocd resources ...");
        for (HasMetadata item : filteredItems) {
            var res = client.resource(item).inNamespace("argocd");
            res.create();
            assertNotNull(res);
        };

        // Waiting till the pods are ready/running ...
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_REDIS_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_REPO_SERVER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_SERVER_NAME);
        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_APPLICATIONSET_CONTROLLER_NAME);
        waitTillPodReady(ARGOCD_NS, ARGOCD_APP_CONTROLLER_NAME + "-0");
        //waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_NOTIFICATION_CONTROLLER_NAME);
        //waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_DEX_SERVER_NAME);
    }

    /*
      Use the Default Argocd AppProject
      Populate an Argocd Application and deploy it under: argocd control's plane
    */
    @Test
    @Order(1)
    public void testCaseOne() {

        Config config = new Config();
        config.setDestinationNamespace("argocd");
        config.setApplicationName("test-1");
        config.setApplicationNamespace("argocd");
        config.setGitRevision("master");

        LOG.info(">>> Running the test case - 1");

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

    /*
      Use a new AppProject deployed under: argocd control's plane
      Populate an Argocd Application using the new AppProject and
      deploy it under: argocd control's plane
    */
    @Test
    @Order(2)
    public void testCaseTwo() {

        Config config = new Config();
        config.setProjectName("test-2");
        config.setGitUrl("https://github.com/argoproj/argocd-example-apps.git");
        config.setDestinationNamespace("argocd");

        config.setApplicationName("test-2");
        config.setApplicationNamespace("argocd");

        config.setGitRevision("master");

        LOG.info(">>> Running the test case - 2");

        client.resource(populateProject(config))
            .inNamespace(ARGOCD_NS)
            .create();

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

        AppProject appProject = client.resources(AppProject.class)
            .inNamespace(ARGOCD_NS)
            .withName(config.getApplicationName()).get();
        LOG.warn(client.getKubernetesSerialization().asYaml(appProject));
    }

    /*
      Use a new AppProject deployed under: argocd control's plane
      Populate an Argocd Application using the new AppProject and
      deploy it under its own namespace
      That requires to enable the "App in any namespaces" as documented:
      https://argo-cd.readthedocs.io/en/stable/operator-manual/app-any-namespace/
    */
    @Test
    @Order(3)
    public void testCaseThree() {
        String ARGO_APPLICATION_NAMESPACE = "test3";

        Config config = new Config();
        config.setProjectName("test-3");
        config.setGitUrl("https://github.com/argoproj/argocd-example-apps.git");
        config.setDestinationNamespace(ARGO_APPLICATION_NAMESPACE);
        // The following property is needed otherwise we got as error
        // message: application 'test-3' in namespace 'test3' is not permitted to use project 'test-3'
        config.setSourceNamespaces(ARGO_APPLICATION_NAMESPACE);

        config.setApplicationName("test-3");
        config.setApplicationNamespace(ARGO_APPLICATION_NAMESPACE);

        config.setGitRevision("master");

        LOG.info(">>> Running the test case - 3");

        LOG.info("Patching the Argocd ConfigMap to add the test3 namespace to the property: application.namespaces");
        client.configMaps().inNamespace(ARGOCD_NS).withName(ARGOCD_CONFIGMAP_PARAMS_NAME)
            .edit(cm -> new ConfigMapBuilder(cm)
                .addToData("application.namespaces",ARGO_APPLICATION_NAMESPACE)
                .build());

        LOG.info("Creating the test3 namespace");
        client.namespaces().resource(new NamespaceBuilder().withNewMetadata().withName(ARGO_APPLICATION_NAMESPACE).endMetadata().build()).create();

        LOG.info("Rolling the ArgoCD server & Application Controller");
        client.apps().deployments().inNamespace(ARGOCD_NS).withName(ARGOCD_SERVER_NAME)
            .rolling().restart();
        client.apps().statefulSets().inNamespace(ARGOCD_NS).withName(ARGOCD_APP_CONTROLLER_NAME)
            .rolling().restart();

        waitTillPodByLabelReady(ARGOCD_NS,"app.kubernetes.io/name",ARGOCD_SERVER_NAME);
        waitTillPodReady(ARGOCD_NS, ARGOCD_APP_CONTROLLER_NAME + "-0");

        LOG.info("Deploy the AppProject");
        client.resource(populateProject(config))
            .inNamespace(ARGOCD_NS)
            .create();

        LOG.info("Deploy the Application");
        client.resource(populateApplication(config))
            .inNamespace(ARGO_APPLICATION_NAMESPACE)
            .create();

        LOG.info("Checking when Argocd Application will be Healthy");
        try {
            client.resources(Application.class)
                .inNamespace(ARGO_APPLICATION_NAMESPACE)
                .withName(config.getApplicationName())
                .waitUntilCondition(a ->
                    a != null &&
                        a.getStatus() != null &&
                        a.getStatus().getHealth() != null &&
                        a.getStatus().getHealth().getStatus() != null &&
                        a.getStatus().getHealth().getStatus().equals("Healthy"), 3600, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error(client.getKubernetesSerialization().asYaml(client.genericKubernetesResources("argoproj.io/v1alpha1", "Application").inNamespace(ARGO_APPLICATION_NAMESPACE).withName(config.getApplicationName()).get()));
        }
        LOG.info("Argocd Application: {} healthy", config.getApplicationName());

        LOG.info("Checking now when Argocd Application will be synced");
        try {
            client.resources(Application.class)
                .inNamespace(ARGO_APPLICATION_NAMESPACE)
                .withName(config.getApplicationName())
                .waitUntilCondition(a ->
                    a != null &&
                        a.getStatus() != null &&
                        a.getStatus().getSync() != null &&
                        a.getStatus().getSync().getStatus() != null &&
                        a.getStatus().getSync().getStatus().equals("Synced"), 3600, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error(client.getKubernetesSerialization().asYaml(client.genericKubernetesResources("argoproj.io/v1alpha1", "Application").inNamespace(ARGO_APPLICATION_NAMESPACE).withName(config.getApplicationName()).get()));
        }
        LOG.info("Argocd Application: {} synced", config.getApplicationName());

        Application app = client.resources(Application.class)
            .inNamespace("test-3")
            .withName(config.getApplicationName()).get();
        LOG.warn(client.getKubernetesSerialization().asYaml(app));

        AppProject appProject = client.resources(AppProject.class)
            .inNamespace("test-3")
            .withName(config.getApplicationName()).get();
        LOG.warn(client.getKubernetesSerialization().asYaml(appProject));
    }
}
