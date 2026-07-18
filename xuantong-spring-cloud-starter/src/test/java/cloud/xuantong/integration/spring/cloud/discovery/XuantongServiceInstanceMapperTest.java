package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.client.serializer.Serializer;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.ServiceInstance;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XuantongServiceInstanceMapperTest {
    private final XuantongServiceInstanceMapper mapper = new XuantongServiceInstanceMapper();

    @Test
    void mapsAuthoritativeInstanceAndMetadataToSpringCloud() {
        cloud.xuantong.client.model.ServiceInstance source =
                new cloud.xuantong.client.model.ServiceInstance();
        source.setNamespaceId("public");
        source.setGroupName("DEFAULT_GROUP");
        source.setServiceName("order-service");
        source.setInstanceId("order-1");
        source.setIp("10.0.0.8");
        source.setPort(9090);
        source.setWeight(2.5D);
        source.setLeaseId("lease-7");
        source.setMetadata(Serializer.defaultSerializer().serialize(
                Map.of("scheme", "grpc", "zone", "shanghai-a")));

        ServiceInstance target = mapper.toSpring(source);

        assertEquals("order-1", target.getInstanceId());
        assertEquals("order-service", target.getServiceId());
        assertEquals("10.0.0.8", target.getHost());
        assertEquals(9090, target.getPort());
        assertFalse(target.isSecure());
        assertEquals("grpc://10.0.0.8:9090", target.getUri().toString());
        assertEquals("2.5", target.getMetadata().get("weight"));
        assertEquals("lease-7", target.getMetadata().get("xuantong.lease-id"));
        assertEquals("shanghai-a", target.getMetadata().get("zone"));
    }

    @Test
    void mapsSpringRegistrationToXuantongLeaseCandidate() {
        XuantongRegistration registration = new XuantongRegistration(
                "order-1",
                "order-service",
                "10.0.0.8",
                8443,
                true,
                3D,
                new LinkedHashMap<>(Map.of("version", "v2")));

        cloud.xuantong.client.model.ServiceInstance target = mapper.toXuantong(
                registration, "prod", "PAYMENT");

        assertEquals("prod", target.getNamespaceId());
        assertEquals("PAYMENT", target.getGroupName());
        assertEquals("order-service", target.getServiceName());
        assertEquals("order-1", target.getInstanceId());
        assertEquals(8443, target.getPort());
        assertEquals(3D, target.getWeight());
        assertTrue(target.getHealthy());
        assertTrue(target.getEnabled());
        Map<String, String> metadata = Serializer.defaultSerializer().deserializeMap(
                target.getMetadata(), String.class, String.class);
        assertEquals("v2", metadata.get("version"));
        assertEquals("https", metadata.get("scheme"));
    }
}
