package cloud.xuantong.server.state;

import cloud.xuantong.protocol.v2.ServiceCoordinate;
import cloud.xuantong.protocol.v2.ServiceInstanceCoordinate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryControlPlaneScopeTest {
    @Test
    void rejectsServiceAndInstanceCoordinatesOutsideAuthenticatedGroup() {
        assertThrows(IllegalArgumentException.class, () ->
                RegistryControlPlaneSupport.service(
                        "public", "DEFAULT_GROUP", "public", "OTHER_GROUP", "orders"));

        ServiceInstanceCoordinate instance = ServiceInstanceCoordinate.newBuilder()
                .setService(ServiceCoordinate.newBuilder()
                        .setNamespaceId("public")
                        .setGroupName("OTHER_GROUP")
                        .setServiceName("orders"))
                .setInstanceId("instance-1")
                .build();
        assertThrows(IllegalArgumentException.class, () ->
                RegistryControlPlaneSupport.instance(
                        "public", "DEFAULT_GROUP", instance));
    }
}
