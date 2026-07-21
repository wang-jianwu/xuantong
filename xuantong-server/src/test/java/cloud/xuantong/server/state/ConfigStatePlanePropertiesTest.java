package cloud.xuantong.server.state;

import cloud.xuantong.raft.ratis.RatisGroupDefinition;
import cloud.xuantong.raft.ratis.RatisPeerDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigStatePlanePropertiesTest {
    @TempDir
    Path tempDirectory;

    @Test
    void parsesThreeNodeAndBracketedIpv6Topology() {
        List<RatisPeerDefinition> peers = ConfigStatePlaneProperties.parsePeers(
                "state-1@10.0.0.1:9101, state-2@[::1]:9102,state-3@node-3:9103");

        assertEquals(3, peers.size());
        assertEquals("10.0.0.1", peers.get(0).host());
        assertEquals("::1", peers.get(1).host());
        assertEquals(9103, peers.get(2).port());
    }

    @Test
    void productionTopologyRequiresThreeOrFiveVoters() {
        ConfigStatePlaneProperties properties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                "config-default",
                "state-1@127.0.0.1:9101",
                tempDirectory,
                false);

        assertThrows(IllegalStateException.class, properties::groupDefinition);
    }

    @Test
    void singleNodeModeAllowsOneVoter() {
        ConfigStatePlaneProperties properties = new ConfigStatePlaneProperties(
                true,
                "state-1",
                "config-default",
                "state-1@127.0.0.1:9101",
                tempDirectory,
                true);

        RatisGroupDefinition group = properties.groupDefinition();

        assertEquals("config-default", group.groupId().value());
        assertEquals(1, group.peers().size());
        assertEquals(tempDirectory.toAbsolutePath().normalize(),
                properties.nodeOptions(group).storageDirectory());
        assertEquals(0L, properties.nodeOptions(group).storageFreeSpaceMinBytes());
        assertEquals(3, properties.nodeOptions(group).snapshotRetentionFileCount());
        assertEquals("127.0.0.1", properties.nodeOptions(group).rpcBindHost());
        assertEquals(9101, properties.nodeOptions(group).rpcBindPort());
    }

}
