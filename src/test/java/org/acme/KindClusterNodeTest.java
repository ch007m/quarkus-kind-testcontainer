package org.acme;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;

import static io.fabric8.kubernetes.client.Config.fromKubeconfig;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KindClusterNodeTest extends BaseContainer{

    @Test
    public void verify_node_is_present() {
        try (final KubernetesClient client = new DefaultKubernetesClient(fromKubeconfig(KIND.getKubeconfig()))) {
            assertEquals(1, client.nodes().list().getItems().size());
            assertEquals(client.nodes().list().getItems().get(0).getMetadata().getName(),"kind");
        }
    }
}
