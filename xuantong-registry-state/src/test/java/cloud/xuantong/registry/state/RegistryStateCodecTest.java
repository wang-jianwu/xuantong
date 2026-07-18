package cloud.xuantong.registry.state;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryStateCodecTest {
    @Test
    void rejectsTruncatedFinalLengthPrefixedValue() {
        byte[] encoded = RegistryStateCodec.encodeMutationError(
                new RegistryMutationError("LEASE_FENCED", "lease owner changed"));
        byte[] truncated = Arrays.copyOf(encoded, encoded.length - 1);

        IOException failure = assertThrows(
                IOException.class,
                () -> RegistryStateCodec.decodeMutationError(truncated));

        assertEquals("Encoded Registry State value is truncated", failure.getMessage());
    }

    @Test
    void serviceLifecycleAndGenerationRoundTrip() throws Exception {
        ServiceKey key = new ServiceKey("public", "DEFAULT_GROUP", "orders");
        ServiceLifecycle lifecycle = new ServiceLifecycle(
                key, 3L, ServiceLifecycleStatus.ACTIVE, 1_000L);
        RegistryMutationResult expected = new RegistryMutationResult(
                "ACTIVATE_SERVICE",
                7L,
                1_000L,
                List.of(lifecycle),
                List.of(),
                List.of());

        RegistryMutationResult decoded = RegistryStateCodec.decodeMutationResult(
                RegistryStateCodec.encodeMutationResult(expected));
        ServiceLifecycleState state = RegistryStateCodec.decodeServiceLifecycleState(
                RegistryStateCodec.encodeServiceLifecycleState(
                        new ServiceLifecycleState(true, 7L, 1_000L, lifecycle)));

        assertEquals(expected, decoded);
        assertEquals(lifecycle, state.lifecycle());
        assertEquals(3L, state.lifecycle().generation());
    }
}
