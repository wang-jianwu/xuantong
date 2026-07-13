package cloud.xuantong.core.v2.listener;

import cloud.xuantong.core.v2.config.BrokerNodeConfig;
import cloud.xuantong.core.v2.event.ControlPlaneEvent;
import cloud.xuantong.core.v2.event.ServiceInstanceEvent;
import cloud.xuantong.core.v2.model.ResourceNameRules;
import cloud.xuantong.core.v2.model.ServiceInstance;
import cloud.xuantong.core.v2.model.ServiceDefinition;
import cloud.xuantong.core.v2.model.ServiceSnapshot;
import cloud.xuantong.core.v2.service.ServiceDefinitionService;
import cloud.xuantong.core.v2.service.ServiceInstanceRegistry;
import cloud.xuantong.core.service.ClientAccessTokenService;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.socketd.broker.BrokerListener;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventListener;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DiscoveryBrokerV2Listener extends BrokerListener implements EventListener<ServiceInstanceEvent> {

    @Inject
    private ServiceInstanceRegistry instanceRegistry;
    @Inject
    private ServiceDefinitionService serviceDefinitionService;
    @Inject
    private BrokerNodeConfig nodeConfig;
    @Inject private ClientAccessTokenService tokenService;
    @Inject private BrokerClientSessionRegistry sessionRegistry;

    private final Map<String, SessionLease> sessionLeases = new ConcurrentHashMap<>();
    private final Map<InstanceLeaseKey, String> leaseOwners = new ConcurrentHashMap<>();

    @Override
    public void onOpen(Session session) throws IOException {
        if (!isAuthorized(session) || !hasValidSubscription(session)) {
            session.close();
            return;
        }
        super.onOpen(session);
        if (!sessionRegistry.register(
                BrokerClientSessionRegistry.Channel.DISCOVERY, session.param("token"), session)) {
            session.close();
            return;
        }
        log.info("V2 discovery subscriber connected: name={}, namespace={}, group={}, service={}",
                session.name(), session.param("namespace"), session.param("group"),
                session.param("serviceName"));
    }

    @Override
    public void onClose(Session session) {
        sessionRegistry.unregister(session);
        releaseSessionLease(session);
        super.onClose(session);
    }

    @Override
    public void onMessage(Session session, Message message) throws IOException {
        sessionRegistry.touch(session);
        if (!tokenService.isAuthorized(
                session.param("token"), session.param("namespace"), session.param("group"))) {
            log.warn("Closing unauthorized V2 discovery session: name={}", session.name());
            session.close();
            return;
        }
        if ("/get".equals(message.event())) {
            handleGet(session, message);
            return;
        }
        if ("/register".equals(message.event())) {
            handleRegister(session, message);
            return;
        }
        if ("/services".equals(message.event())) {
            handleServices(session, message);
            return;
        }
        if ("/heartbeat".equals(message.event())) {
            handleHeartbeat(session, message);
            return;
        }
        if ("/deregister".equals(message.event())) {
            handleDeregister(session, message);
            return;
        }
        if ("/ping".equals(message.event())) {
            if (message.isRequest()) {
                session.reply(message, new StringEntity("{\"status\":\"ok\"}"));
            }
            return;
        }
        super.onMessage(session, message);
    }

    @Override
    public void onEvent(ServiceInstanceEvent instanceEvent) {
        if ("INSTANCE_HEARTBEAT".equals(instanceEvent.eventType())) {
            return;
        }
        ControlPlaneEvent event = ControlPlaneEvent.create(
                instanceEvent.eventType(),
                instanceEvent.service().namespaceId(),
                instanceEvent.service().groupName(),
                instanceEvent.service().serviceName(),
                instanceEvent.revision(),
                nodeConfig.getNodeId(),
                instanceEvent.instance());
        String subscriber = subscriberName(
                instanceEvent.service().namespaceId(),
                instanceEvent.service().groupName(),
                instanceEvent.service().serviceName());
        try {
            broadcast("/service-change", new StringEntity(ONode.serialize(event)).at(subscriber + "*"));
            log.debug("V2 discovery event pushed: service={}, instance={}, revision={}",
                    instanceEvent.service().canonicalName(),
                    instanceEvent.instance().getInstanceId(), instanceEvent.revision());
        } catch (Exception e) {
            if (isNoSubscriber(e)) {
                log.debug("No V2 discovery subscriber: service={}, revision={}",
                        instanceEvent.service().canonicalName(), instanceEvent.revision());
            } else {
                log.warn("V2 discovery push failed: service={}, revision={}",
                        instanceEvent.service().canonicalName(), instanceEvent.revision(), e);
            }
        }
    }

    private void handleGet(Session session, Message message) throws IOException {
        try {
            ServiceSnapshot snapshot = instanceRegistry.snapshot(
                    session.param("namespace"), session.param("group"),
                    session.param("serviceName"), true);
            session.reply(message, new StringEntity(ONode.serialize(snapshot.instances()))
                    .metaPut("revision", String.valueOf(snapshot.revision())));
        } catch (IllegalArgumentException e) {
            session.reply(message, new StringEntity("[]")
                    .metaPut("revision", "0")
                    .metaPut("error", e.getMessage()));
        }
    }

    private void handleRegister(Session session, Message message) throws IOException {
        try {
            ServiceInstance request = ONode.deserialize(message.dataAsString(), ServiceInstance.class);
            ensureServiceDefinition(session);
            ensureLeaseCanBeOwned(session, request);
            ServiceInstance registered = instanceRegistry.register(
                    session.param("namespace"), session.param("group"),
                    session.param("serviceName"), request);
            bindSessionLease(session, registered);
            session.reply(message, new StringEntity(ONode.serialize(registered))
                    .metaPut("success", "true"));
        } catch (Exception e) {
            replyFailure(session, message, e.getMessage());
        }
    }

    private void handleServices(Session session, Message message) throws IOException {
        try {
            List<String> serviceNames = serviceDefinitionService.findByGroup(
                            session.param("namespace"), session.param("group"))
                    .stream()
                    .map(ServiceDefinition::getServiceName)
                    .collect(Collectors.toList());
            session.reply(message, new StringEntity(ONode.serialize(serviceNames))
                    .metaPut("success", "true"));
        } catch (Exception e) {
            replyFailure(session, message, e.getMessage());
        }
    }

    private void ensureServiceDefinition(Session session) {
        String namespaceId = session.param("namespace");
        String groupName = session.param("group");
        String serviceName = session.param("serviceName");
        if (serviceDefinitionService.find(namespaceId, groupName, serviceName) != null) {
            return;
        }
        ServiceDefinition service = new ServiceDefinition();
        service.setNamespaceId(namespaceId);
        service.setGroupName(groupName);
        service.setServiceName(serviceName);
        service.setDescription("Automatically created by service registration");
        try {
            serviceDefinitionService.create(service, "discovery-client");
        } catch (IllegalArgumentException e) {
            if (serviceDefinitionService.find(namespaceId, groupName, serviceName) == null) {
                throw e;
            }
        }
    }

    private void handleHeartbeat(Session session, Message message) throws IOException {
        try {
            ServiceInstance instance = instanceRegistry.heartbeat(
                    session.param("namespace"), session.param("group"),
                    session.param("serviceName"), message.entity().meta("instanceId"),
                    message.entity().meta("leaseId"));
            session.reply(message, new StringEntity(ONode.serialize(instance))
                    .metaPut("success", "true"));
        } catch (Exception e) {
            replyFailure(session, message, e.getMessage());
        }
    }

    private void handleDeregister(Session session, Message message) throws IOException {
        try {
            boolean removed = instanceRegistry.deregister(
                    session.param("namespace"), session.param("group"),
                    session.param("serviceName"), message.entity().meta("instanceId"),
                    message.entity().meta("leaseId"));
            if (removed) {
                unbindSessionLease(session);
            }
            session.reply(message, new StringEntity(Boolean.toString(removed))
                    .metaPut("success", String.valueOf(removed)));
        } catch (Exception e) {
            replyFailure(session, message, e.getMessage());
        }
    }

    private void replyFailure(Session session, Message message, String error) throws IOException {
        session.reply(message, new StringEntity("")
                .metaPut("success", "false")
                .metaPut("error", error == null ? "unknown error" : error));
    }

    private void ensureLeaseCanBeOwned(Session session, ServiceInstance request) {
        if (request == null) {
            return;
        }
        InstanceLeaseKey key = new InstanceLeaseKey(
                session.param("namespace"), session.param("group"),
                session.param("serviceName"), request.getInstanceId());
        String ownerSessionId = leaseOwners.get(key);
        if (ownerSessionId == null || ownerSessionId.equals(session.sessionId())) {
            return;
        }
        SessionLease ownerLease = sessionLeases.get(ownerSessionId);
        Session ownerSession = getSessionById(ownerSessionId);
        if (ownerSession != null && ownerSession.isActive()
                && ownerLease != null
                && !ownerLease.leaseId().equals(request.getLeaseId())) {
            throw new IllegalArgumentException(
                    "Service instance is owned by another active lease: " + request.getInstanceId());
        }
    }

    private void bindSessionLease(Session session, ServiceInstance instance) {
        InstanceLeaseKey key = new InstanceLeaseKey(
                instance.getNamespaceId(), instance.getGroupName(),
                instance.getServiceName(), instance.getInstanceId());
        SessionLease lease = new SessionLease(key, instance.getLeaseId());
        SessionLease previous = sessionLeases.put(session.sessionId(), lease);
        if (previous != null && !previous.equals(lease)) {
            leaseOwners.remove(previous.key(), session.sessionId());
        }
        String previousOwner = leaseOwners.put(key, session.sessionId());
        if (previousOwner != null && !previousOwner.equals(session.sessionId())) {
            sessionLeases.remove(previousOwner);
        }
    }

    private void unbindSessionLease(Session session) {
        SessionLease lease = sessionLeases.remove(session.sessionId());
        if (lease != null) {
            leaseOwners.remove(lease.key(), session.sessionId());
        }
    }

    private void releaseSessionLease(Session session) {
        SessionLease lease = sessionLeases.remove(session.sessionId());
        if (lease == null || !leaseOwners.remove(lease.key(), session.sessionId())) {
            return;
        }
        try {
            instanceRegistry.deregister(
                    lease.key().namespaceId(), lease.key().groupName(),
                    lease.key().serviceName(), lease.key().instanceId(), lease.leaseId());
            log.info("Service instance removed after Socket.D session closed: service={}, instance={}",
                    lease.key().serviceName(), lease.key().instanceId());
        } catch (Exception e) {
            log.debug("Service instance already released after Socket.D session closed: service={}, instance={}",
                    lease.key().serviceName(), lease.key().instanceId());
        }
    }

    private boolean isAuthorized(Session session) {
        if (!tokenService.authorize(session.param("token"), session.param("namespace"), session.param("group"))) {
            log.warn("V2 discovery subscriber authentication failed: name={}", session.name());
            return false;
        }
        return true;
    }

    private boolean hasValidSubscription(Session session) {
        try {
            String namespaceId = ResourceNameRules.validate("namespace", session.param("namespace"));
            String groupName = ResourceNameRules.validate("group", session.param("group"));
            String serviceName = ResourceNameRules.validate("serviceName", session.param("serviceName"));
            String expectedName = subscriberName(namespaceId, groupName, serviceName);
            if (!expectedName.equals(session.name())) {
                log.warn("Invalid V2 discovery subscriber name: expected={}, actual={}",
                        expectedName, session.name());
                return false;
            }
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid V2 discovery subscription parameters: name={}", session.name());
            return false;
        }
    }

    public static String subscriberName(String namespaceId, String groupName, String serviceName) {
        return "discovery:" + namespaceId + ":" + groupName + ":" + serviceName;
    }

    private boolean isNoSubscriber(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("don't have");
    }

    private record InstanceLeaseKey(
            String namespaceId, String groupName, String serviceName, String instanceId) {
    }

    private record SessionLease(InstanceLeaseKey key, String leaseId) {
    }
}
