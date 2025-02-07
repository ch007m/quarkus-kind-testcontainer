package org.acme;

import io.quarkiverse.argocd.v1alpha1.AppProject;
import io.quarkiverse.argocd.v1alpha1.AppProjectBuilder;
import io.quarkiverse.argocd.v1alpha1.Application;
import io.quarkiverse.argocd.v1alpha1.ApplicationBuilder;

public class ArgocdResourceGenerator {

    public static AppProject populateProject(Config config) {
    // @formatter:off
        var projectBuilder = new AppProjectBuilder()
          .withNewMetadata()
            .withName(config.getProjectName())
            .withNamespace(config.getProjectNamespace())
          .endMetadata()
          .withNewSpec()
            .addNewDestination()
              .withNamespace(config.getDestinationNamespace())
              .withServer(config.getDestinationKubeServer())
            .endDestination()
          .withSourceRepos(config.getGitUrl())
          .endSpec();

        if (config.getSourceNamespaces() != null) {
            projectBuilder.editOrNewSpec().withSourceNamespaces(config.getSourceNamespaces()).endSpec();
        }

        // @formatter:on
        return projectBuilder.build();
    }

    public static Application populateApplication(Config config) {
        // @formatter:off
        Application application = new ApplicationBuilder()
                .withNewMetadata()
                  .withName(config.getApplicationName())
                  .withNamespace(config.getApplicationNamespace())
                .endMetadata()
                .withNewSpec()
                  .withProject(config.getProjectName())
                  .withNewDestination()
                    .withServer(config.getDestinationKubeServer())
                    .withNamespace(config.getApplicationNamespace())
                  .endDestination()
                  .withNewSource()
                    .withPath(config.getHelmPath())
                    .withRepoURL(config.getHelmUrl())
                    .withTargetRevision(config.getGitRevision())
                    .withNewHelm()
                      .withValueFiles("values.yaml")
                      //.endHelm()
                      .endApplicationspecHelm()
                  //.endSource()
                  .endApplicationspecSource()
                  .withNewSyncPolicy()
                    .withNewAutomated()
                      .withPrune(true)
                      .withSelfHeal(true)
                    .endAutomated()
                    .addToSyncOptions("CreateNamespace=true", "RespectIgnoreDifferences=true", "ApplyOutOfSyncOnly=true")
                    .withNewRetry()
                      .withNewBackoff()
                        .withDuration("5s")
                        .withFactor(2L)
                        .withMaxDuration("10m")
                      //.endBackoff()
                      .endSyncpolicyBackoff()
                    //.endRetry()
                    .endSyncpolicyRetry()
                  .endSyncPolicy()
                .endSpec()
                .build();
        // @formatter:on
        return application;
    }
}
