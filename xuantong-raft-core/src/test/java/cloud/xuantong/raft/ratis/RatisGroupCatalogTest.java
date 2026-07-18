package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateCommand;
import cloud.xuantong.state.api.StateGroupId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RatisGroupCatalogTest {
    private final List<RatisPeerDefinition> peers = List.of(
            new RatisPeerDefinition("state-1", "127.0.0.1", 19001),
            new RatisPeerDefinition("state-2", "127.0.0.1", 19002),
            new RatisPeerDefinition("state-3", "127.0.0.1", 19003));

    @Test
    void compactCatalogRequiresTypedConfigAndRegistryGroups() {
        RatisGroupDefinition config = new RatisGroupDefinition(
                StateGroupId.config("config-default"), peers);
        RatisGroupDefinition registry = new RatisGroupDefinition(
                StateGroupId.registry("registry-default"), peers);

        RatisGroupCatalog catalog = RatisGroupCatalog.compact(config, registry);

        assertEquals(config, catalog.bootstrapGroup());
        assertEquals(registry, catalog.requireGroup(registry.groupId()));
        assertThrows(IllegalArgumentException.class,
                () -> RatisGroupCatalog.compact(registry, config));
    }

    @Test
    void compactCatalogRejectsDifferentPhysicalTopologies() {
        RatisGroupDefinition config = new RatisGroupDefinition(
                StateGroupId.config("config-default"), peers);
        RatisGroupDefinition registry = new RatisGroupDefinition(
                StateGroupId.registry("registry-default"), List.of(
                        new RatisPeerDefinition("state-1", "127.0.0.1", 29001),
                        new RatisPeerDefinition("state-2", "127.0.0.1", 29002),
                        new RatisPeerDefinition("state-3", "127.0.0.1", 29003)));

        assertThrows(IllegalArgumentException.class,
                () -> RatisGroupCatalog.compact(config, registry));
    }

    @Test
    void routerRejectsUnknownGroupWithoutContactingAnyRaftPeer() throws Exception {
        RatisGroupDefinition config = new RatisGroupDefinition(
                StateGroupId.config("config-default"), peers);
        try (RatisStateRouter router = new RatisStateRouter(
                List.of(config), Duration.ofSeconds(1), 1)) {
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> router.submit(new StateCommand(
                                    StateGroupId.registry("registry-default"),
                                    "op-1",
                                    "registry.register",
                                    1,
                                    new byte[0]))
                            .get(1, TimeUnit.SECONDS));
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }
}
