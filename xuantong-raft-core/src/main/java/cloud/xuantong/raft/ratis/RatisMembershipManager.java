package cloud.xuantong.raft.ratis;

import cloud.xuantong.state.api.StateGroupId;
import org.apache.ratis.RaftConfigKeys;
import org.apache.ratis.client.RaftClient;
import org.apache.ratis.client.RaftClientConfigKeys;
import org.apache.ratis.conf.RaftProperties;
import org.apache.ratis.proto.RaftProtos.RaftConfigurationProto;
import org.apache.ratis.proto.RaftProtos.RaftPeerProto;
import org.apache.ratis.proto.RaftProtos.RaftPeerRole;
import org.apache.ratis.protocol.GroupInfoReply;
import org.apache.ratis.protocol.RaftClientReply;
import org.apache.ratis.protocol.RaftGroup;
import org.apache.ratis.protocol.RaftPeer;
import org.apache.ratis.protocol.RaftPeerId;
import org.apache.ratis.protocol.SetConfigurationRequest;
import org.apache.ratis.retry.RetryPolicies;
import org.apache.ratis.rpc.SupportedRpcType;
import org.apache.ratis.util.NetUtils;
import org.apache.ratis.util.TimeDuration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Explicit, fail-closed membership orchestration for compact Config/Registry Groups.
 *
 * <p>New nodes are added as listeners, checked for log catch-up and runtime capabilities,
 * and only then promoted with a compare-and-set configuration change. A leader selected
 * for removal is transferred to a surviving voter before the final transition.</p>
 */
public final class RatisMembershipManager {
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final RatisMembershipPolicy policy;

    public RatisMembershipManager(
            Duration requestTimeout,
            int maxAttempts,
            RatisMembershipPolicy policy) {
        if (requestTimeout == null || requestTimeout.isZero()
                || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.requestTimeout = requestTimeout;
        this.maxAttempts = maxAttempts;
        this.policy = policy;
    }

    public RatisMembershipChangeResult change(
            Collection<StateGroupId> stateGroups,
            List<RatisPeerDefinition> expectedCurrentVoters,
            List<RatisPeerDefinition> targetVoters,
            Map<StateGroupId, RatisVersionRequirement> requirements) throws IOException {
        List<StateGroupId> groups = validatedGroups(stateGroups);
        List<RatisPeerDefinition> current = sorted(expectedCurrentVoters);
        List<RatisPeerDefinition> target = sorted(targetVoters);
        validateTransition(current, target);
        Map<StateGroupId, RatisVersionRequirement> required =
                Map.copyOf(requirements == null ? Map.of() : requirements);
        for (StateGroupId groupId : groups) {
            RatisVersionRequirement requirement = required.get(groupId);
            if (requirement == null || !groupId.equals(requirement.groupId())) {
                throw new IllegalArgumentException(
                        "Missing active version requirement for " + groupId);
            }
        }

        List<RatisPeerDefinition> union = union(current, target);
        List<RatisGroupMembershipView> completed = new ArrayList<>();
        for (StateGroupId groupId : groups) {
            completed.add(changeGroup(
                    groupId, current, target, union, required.get(groupId)));
        }
        return new RatisMembershipChangeResult(
                current,
                target,
                nodeIdsDifference(target, current),
                nodeIdsDifference(current, target),
                completed);
    }

    public RatisGroupMembershipView inspect(
            StateGroupId groupId, Collection<RatisPeerDefinition> seeds) throws IOException {
        List<RatisPeerDefinition> peers = sorted(seeds);
        if (peers.isEmpty()) {
            throw new IllegalArgumentException("seeds must not be empty");
        }
        try (RaftClient client = createAdminClient(groupId, peers)) {
            return inspectFromAny(client, groupId, peers);
        }
    }

    public List<RatisStateNodeCapability> capabilities(
            StateGroupId groupId,
            Collection<RatisPeerDefinition> seeds,
            Collection<RatisPeerDefinition> voters) throws IOException {
        List<RatisPeerDefinition> reachable = union(sorted(seeds), sorted(voters));
        try (RatisStateClient state = new RatisStateClient(
                new RatisGroupDefinition(groupId, reachable),
                requestTimeout,
                maxAttempts)) {
            return capabilities(state, sorted(voters));
        }
    }

    private RatisGroupMembershipView changeGroup(
            StateGroupId groupId,
            List<RatisPeerDefinition> expectedCurrent,
            List<RatisPeerDefinition> target,
            List<RatisPeerDefinition> union,
            RatisVersionRequirement requirement) throws IOException {
        try (RaftClient admin = createAdminClient(groupId, union);
             RatisStateClient state = new RatisStateClient(
                     new RatisGroupDefinition(groupId, union), requestTimeout, maxAttempts)) {
            RatisGroupMembershipView observed = inspectFromAny(admin, groupId, union);
            waitForJointConsensusToFinish(admin, groupId, union, observed);
            observed = inspectFromAny(admin, groupId, union);

            if (samePeers(observed.voters(), target) && observed.listeners().isEmpty()) {
                List<RatisStateNodeCapability> capabilities = capabilities(state, target);
                RatisCapabilityGate.requireSupported(target, requirement, capabilities);
                return verifyTarget(admin, groupId, target, union);
            }
            if (!samePeers(observed.voters(), expectedCurrent)) {
                throw new RatisOperationException(
                        "Raft membership changed concurrently for " + groupId
                                + "; expected=" + expectedCurrent
                                + ", observed=" + observed.voters());
            }

            List<RatisPeerDefinition> additions = difference(target, expectedCurrent);
            if (!additions.isEmpty()) {
                prepareJoiningGroups(admin, groupId, expectedCurrent, additions);
                List<RatisPeerDefinition> expectedListeners = sorted(additions);
                if (!samePeers(observed.listeners(), expectedListeners)) {
                    setConfiguration(
                            admin,
                            expectedCurrent,
                            expectedListeners,
                            observed.voters(),
                            observed.listeners());
                }
                waitForConfiguration(
                        admin, groupId, union, expectedCurrent, expectedListeners);
                waitForCatchUp(admin, groupId, union, additions);
            } else if (!observed.listeners().isEmpty()) {
                throw new RatisOperationException(
                        "Unexpected listeners exist for " + groupId + ": "
                                + observed.listeners());
            }

            List<RatisStateNodeCapability> capabilities = capabilities(state, target);
            RatisCapabilityGate.requireSupported(target, requirement, capabilities);

            RatisGroupMembershipView staged = inspectFromAny(admin, groupId, union);
            transferLeadershipIfRemoved(admin, staged, target);
            setConfiguration(admin, target, List.of(),
                    staged.voters(), staged.listeners());
            waitForConfiguration(admin, groupId, union, target, List.of());
            return verifyTarget(admin, groupId, target, union);
        }
    }

    private void prepareJoiningGroups(
            RaftClient admin,
            StateGroupId groupId,
            List<RatisPeerDefinition> current,
            List<RatisPeerDefinition> additions) throws IOException {
        List<RaftPeer> stagedPeers = new ArrayList<>();
        current.forEach(peer -> stagedPeers.add(peer.toRaftPeer(RaftPeerRole.FOLLOWER)));
        additions.forEach(peer -> stagedPeers.add(peer.toRaftPeer(RaftPeerRole.LISTENER)));
        RaftGroup stagedGroup = RaftGroup.valueOf(
                new RatisGroupDefinition(groupId, union(current, additions)).toRaftGroupId(),
                stagedPeers);
        for (RatisPeerDefinition addition : additions) {
            var management = admin.getGroupManagementApi(
                    RaftPeerId.valueOf(addition.nodeId()));
            if (management.list().getGroupIds().contains(stagedGroup.getGroupId())) {
                continue;
            }
            requireSuccess(management.add(stagedGroup, false),
                    "prepare joining Group " + groupId + " on " + addition.nodeId());
        }
    }

    private List<RatisStateNodeCapability> capabilities(
            RatisStateClient state, List<RatisPeerDefinition> target) throws IOException {
        List<RatisStateNodeCapability> capabilities = new ArrayList<>();
        for (RatisPeerDefinition peer : target) {
            capabilities.add(state.capability(peer.nodeId()));
        }
        return List.copyOf(capabilities);
    }

    private void waitForCatchUp(
            RaftClient admin,
            StateGroupId groupId,
            List<RatisPeerDefinition> seeds,
            List<RatisPeerDefinition> additions) throws IOException {
        long deadline = System.nanoTime() + policy.catchUpTimeout().toNanos();
        IOException last = null;
        while (System.nanoTime() < deadline) {
            try {
                RatisGroupMembershipView cluster = inspectFromAny(admin, groupId, seeds);
                long leaderCommitted = cluster.committedIndex();
                boolean caughtUp = true;
                for (RatisPeerDefinition addition : additions) {
                    GroupInfoReply info = admin.getGroupManagementApi(
                            RaftPeerId.valueOf(addition.nodeId()))
                            .info(new RatisGroupDefinition(
                                    groupId, seeds).toRaftGroupId());
                    if (!info.isRaftStorageHealthy()
                            || !info.getLogInfoProto().hasApplied()
                            || leaderCommitted - info.getLogInfoProto()
                            .getApplied().getIndex() > policy.maximumCatchUpGap()) {
                        caughtUp = false;
                        break;
                    }
                }
                if (caughtUp) {
                    return;
                }
            } catch (IOException e) {
                last = e;
            }
            pause();
        }
        throw new RatisOperationException(
                "Joining State nodes did not catch up for " + groupId,
                last);
    }

    private void transferLeadershipIfRemoved(
            RaftClient admin,
            RatisGroupMembershipView staged,
            List<RatisPeerDefinition> target) throws IOException {
        if (staged.leaderId().isEmpty()
                || target.stream().anyMatch(peer ->
                peer.nodeId().equals(staged.leaderId()))) {
            return;
        }
        RatisPeerDefinition successor = target.stream()
                .filter(peer -> staged.voters().stream().anyMatch(current ->
                        current.nodeId().equals(peer.nodeId())))
                .findFirst()
                .orElseThrow(() -> new RatisOperationException(
                        "No surviving voter can receive leadership before removal"));
        requireSuccess(admin.admin().transferLeadership(
                        RaftPeerId.valueOf(successor.nodeId()),
                        RaftPeerId.valueOf(staged.leaderId()),
                        requestTimeout.toMillis()),
                "transfer leadership to " + successor.nodeId());
    }

    private RatisGroupMembershipView verifyTarget(
            RaftClient admin,
            StateGroupId groupId,
            List<RatisPeerDefinition> target,
            List<RatisPeerDefinition> seeds) throws IOException {
        RatisGroupMembershipView view = inspectFromAny(admin, groupId, seeds);
        if (view.jointConsensus() || !samePeers(view.voters(), target)
                || !view.listeners().isEmpty() || view.leaderId().isEmpty()) {
            throw new RatisOperationException(
                    "Raft membership verification failed for " + groupId
                            + ": " + view);
        }
        for (RatisPeerDefinition peer : target) {
            GroupInfoReply info = admin.getGroupManagementApi(
                    RaftPeerId.valueOf(peer.nodeId()))
                    .info(new RatisGroupDefinition(groupId, seeds).toRaftGroupId());
            if (!info.isRaftStorageHealthy()) {
                throw new RatisOperationException(
                        "Raft storage is unhealthy on " + peer.nodeId()
                                + " for " + groupId);
            }
        }
        return view;
    }

    private void waitForJointConsensusToFinish(
            RaftClient admin,
            StateGroupId groupId,
            List<RatisPeerDefinition> seeds,
            RatisGroupMembershipView initial) throws IOException {
        if (!initial.jointConsensus()) {
            return;
        }
        long deadline = System.nanoTime() + policy.catchUpTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            RatisGroupMembershipView current = inspectFromAny(admin, groupId, seeds);
            if (!current.jointConsensus()) {
                return;
            }
            pause();
        }
        throw new RatisOperationException(
                "Raft Group remained in joint consensus: " + groupId);
    }

    private void waitForConfiguration(
            RaftClient admin,
            StateGroupId groupId,
            List<RatisPeerDefinition> seeds,
            List<RatisPeerDefinition> voters,
            List<RatisPeerDefinition> listeners) throws IOException {
        long deadline = System.nanoTime() + policy.catchUpTimeout().toNanos();
        while (System.nanoTime() < deadline) {
            RatisGroupMembershipView current = inspectFromAny(admin, groupId, seeds);
            if (!current.jointConsensus()
                    && samePeers(current.voters(), voters)
                    && samePeers(current.listeners(), listeners)) {
                return;
            }
            pause();
        }
        throw new RatisOperationException(
                "Raft configuration did not converge for " + groupId);
    }

    private void setConfiguration(
            RaftClient admin,
            List<RatisPeerDefinition> newVoters,
            List<RatisPeerDefinition> newListeners,
            List<RatisPeerDefinition> currentVoters,
            List<RatisPeerDefinition> currentListeners) throws IOException {
        SetConfigurationRequest.Arguments arguments =
                SetConfigurationRequest.Arguments.newBuilder()
                        .setMode(SetConfigurationRequest.Mode.COMPARE_AND_SET)
                        .setServersInCurrentConf(toPeers(currentVoters, RaftPeerRole.FOLLOWER))
                        .setListenersInCurrentConf(toPeers(
                                currentListeners, RaftPeerRole.LISTENER))
                        .setServersInNewConf(toPeers(newVoters, RaftPeerRole.FOLLOWER))
                        .setListenersInNewConf(toPeers(newListeners, RaftPeerRole.LISTENER))
                        .build();
        requireSuccess(admin.admin().setConfiguration(arguments),
                "compare-and-set Raft configuration");
    }

    private RatisGroupMembershipView inspectFromAny(
            RaftClient admin,
            StateGroupId groupId,
            List<RatisPeerDefinition> seeds) throws IOException {
        IOException failure = null;
        for (RatisPeerDefinition seed : seeds) {
            try {
                GroupInfoReply info = admin.getGroupManagementApi(
                        RaftPeerId.valueOf(seed.nodeId()))
                        .info(new RatisGroupDefinition(groupId, seeds).toRaftGroupId());
                RaftConfigurationProto configuration = info.getConf().orElse(null);
                List<RatisPeerDefinition> voters;
                List<RatisPeerDefinition> listeners;
                boolean jointConsensus;
                if (configuration != null) {
                    voters = peers(configuration.getPeersList());
                    listeners = peers(configuration.getListenersList());
                    jointConsensus = configuration.getOldPeersCount() > 0
                            || configuration.getOldListenersCount() > 0;
                } else {
                    // Ratis 3.2.2 creates the configuration in RaftServerImpl but its
                    // gRPC GroupInfo serializer omits the conf field. The returned Group
                    // still contains peer startup roles, which is sufficient after the
                    // blocking setConfiguration call has completed.
                    voters = peerDefinitions(info.getGroup().getPeers(), false);
                    listeners = peerDefinitions(info.getGroup().getPeers(), true);
                    jointConsensus = false;
                }
                String leaderId = leaderId(info, seed.nodeId());
                long committed = info.getLogInfoProto().hasCommitted()
                        ? info.getLogInfoProto().getCommitted().getIndex() : -1L;
                return new RatisGroupMembershipView(
                        groupId,
                        voters,
                        listeners,
                        leaderId,
                        committed,
                        jointConsensus);
            } catch (IOException | RuntimeException e) {
                IOException mapped = e instanceof IOException io
                        ? io : new IOException(e.getMessage(), e);
                if (failure == null) {
                    failure = mapped;
                } else {
                    failure.addSuppressed(mapped);
                }
            }
        }
        throw failure == null
                ? new RatisOperationException("No Raft seed was inspected for " + groupId)
                : failure;
    }

    private static String leaderId(GroupInfoReply info, String inspectedNodeId) {
        if (info.getRoleInfoProto().getRole() == RaftPeerRole.LEADER) {
            return inspectedNodeId;
        }
        if (info.getRoleInfoProto().hasFollowerInfo()
                && info.getRoleInfoProto().getFollowerInfo().hasLeaderInfo()) {
            return info.getRoleInfoProto().getFollowerInfo()
                    .getLeaderInfo().getId().getId().toStringUtf8();
        }
        return "";
    }

    private RaftClient createAdminClient(
            StateGroupId groupId, List<RatisPeerDefinition> peers) {
        RaftProperties properties = new RaftProperties();
        RaftConfigKeys.Rpc.setType(properties, SupportedRpcType.GRPC);
        RaftClientConfigKeys.Rpc.setRequestTimeout(properties,
                TimeDuration.valueOf(requestTimeout.toMillis(), TimeUnit.MILLISECONDS));
        return RaftClient.newBuilder()
                .setRaftGroup(new RatisGroupDefinition(groupId, peers).toRaftGroup())
                .setProperties(properties)
                .setRetryPolicy(RetryPolicies.retryUpToMaximumCountWithFixedSleep(
                        maxAttempts,
                        TimeDuration.valueOf(100, TimeUnit.MILLISECONDS)))
                .build();
    }

    private void validateTransition(
            List<RatisPeerDefinition> current,
            List<RatisPeerDefinition> target) {
        validateTopology("current", current.size());
        validateTopology("target", target.size());
        Map<String, RatisPeerDefinition> currentById = byNodeId(current);
        Map<String, RatisPeerDefinition> targetById = byNodeId(target);
        for (Map.Entry<String, RatisPeerDefinition> entry : currentById.entrySet()) {
            RatisPeerDefinition targetPeer = targetById.get(entry.getKey());
            if (targetPeer != null && !entry.getValue().equals(targetPeer)) {
                throw new IllegalArgumentException(
                        "A Raft node address cannot change in-place; replace nodeId "
                                + entry.getKey() + " instead");
            }
        }
        long overlap = currentById.keySet().stream()
                .filter(targetById::containsKey).count();
        int currentMajority = current.size() / 2 + 1;
        if (current.size() > 1 && overlap < currentMajority) {
            throw new IllegalArgumentException(
                    "One membership operation must preserve the current quorum; overlap="
                            + overlap + ", required=" + currentMajority);
        }
    }

    private void validateTopology(String name, int count) {
        if (count == 1 && policy.allowSingleNode()) {
            return;
        }
        if (count != 3 && count != 5) {
            throw new IllegalArgumentException(
                    name + " Raft topology must contain 3 or 5 voters");
        }
    }

    private static List<StateGroupId> validatedGroups(Collection<StateGroupId> groups) {
        if (groups == null || groups.isEmpty()) {
            throw new IllegalArgumentException("stateGroups must not be empty");
        }
        LinkedHashSet<StateGroupId> unique = new LinkedHashSet<>(groups);
        if (unique.size() != groups.size()) {
            throw new IllegalArgumentException("stateGroups must be unique");
        }
        return List.copyOf(unique);
    }

    private static List<RatisPeerDefinition> peers(List<RaftPeerProto> values) {
        List<RatisPeerDefinition> peers = new ArrayList<>(values.size());
        for (RaftPeerProto value : values) {
            InetSocketAddress address = NetUtils.createSocketAddr(value.getAddress());
            peers.add(new RatisPeerDefinition(
                    value.getId().toStringUtf8(),
                    address.getHostString(),
                    address.getPort()));
        }
        return sorted(peers);
    }

    private static List<RatisPeerDefinition> peerDefinitions(
            Collection<RaftPeer> values, boolean listeners) {
        return sorted(values.stream()
                .filter(peer -> (peer.getStartupRole() == RaftPeerRole.LISTENER) == listeners)
                .map(peer -> {
                    InetSocketAddress address = NetUtils.createSocketAddr(peer.getAddress());
                    return new RatisPeerDefinition(
                            peer.getId().toString(),
                            address.getHostString(),
                            address.getPort());
                })
                .toList());
    }

    private static List<RaftPeer> toPeers(
            List<RatisPeerDefinition> definitions, RaftPeerRole role) {
        return definitions.stream().map(peer -> peer.toRaftPeer(role)).toList();
    }

    private static List<RatisPeerDefinition> sorted(
            Collection<RatisPeerDefinition> peers) {
        List<RatisPeerDefinition> values = new ArrayList<>(
                peers == null ? List.of() : peers);
        values.sort(Comparator.comparing(RatisPeerDefinition::nodeId));
        if (!values.isEmpty()) {
            new RatisGroupDefinition(StateGroupId.meta("membership-validation"), values);
        }
        return List.copyOf(values);
    }

    private static List<RatisPeerDefinition> union(
            Collection<RatisPeerDefinition> first,
            Collection<RatisPeerDefinition> second) {
        LinkedHashMap<String, RatisPeerDefinition> values = new LinkedHashMap<>();
        for (RatisPeerDefinition peer : first) {
            values.put(peer.nodeId(), peer);
        }
        for (RatisPeerDefinition peer : second) {
            RatisPeerDefinition existing = values.putIfAbsent(peer.nodeId(), peer);
            if (existing != null && !existing.equals(peer)) {
                throw new IllegalArgumentException(
                        "Conflicting definition for Raft node " + peer.nodeId());
            }
        }
        return sorted(values.values());
    }

    private static List<RatisPeerDefinition> difference(
            Collection<RatisPeerDefinition> values,
            Collection<RatisPeerDefinition> removed) {
        Set<String> removedIds = removed.stream()
                .map(RatisPeerDefinition::nodeId).collect(java.util.stream.Collectors.toSet());
        return sorted(values.stream()
                .filter(value -> !removedIds.contains(value.nodeId())).toList());
    }

    private static List<String> nodeIdsDifference(
            Collection<RatisPeerDefinition> values,
            Collection<RatisPeerDefinition> removed) {
        return difference(values, removed).stream()
                .map(RatisPeerDefinition::nodeId).toList();
    }

    private static boolean samePeers(
            Collection<RatisPeerDefinition> first,
            Collection<RatisPeerDefinition> second) {
        return sorted(first).equals(sorted(second));
    }

    private static Map<String, RatisPeerDefinition> byNodeId(
            Collection<RatisPeerDefinition> peers) {
        Map<String, RatisPeerDefinition> values = new LinkedHashMap<>();
        for (RatisPeerDefinition peer : peers) {
            if (values.putIfAbsent(peer.nodeId(), peer) != null) {
                throw new IllegalArgumentException(
                        "Duplicate Raft nodeId: " + peer.nodeId());
            }
        }
        return values;
    }

    private static void requireSuccess(RaftClientReply reply, String action)
            throws IOException {
        if (reply == null || !reply.isSuccess()) {
            throw new RatisOperationException(
                    "Failed to " + action,
                    reply == null ? null : reply.getException());
        }
    }

    private static void pause() throws IOException {
        try {
            Thread.sleep(100L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Raft membership", e);
        }
    }
}
