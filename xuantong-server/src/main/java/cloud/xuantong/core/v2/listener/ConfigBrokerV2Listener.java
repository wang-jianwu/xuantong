package cloud.xuantong.core.v2.listener;

import cloud.xuantong.core.v2.config.BrokerNodeConfig;
import cloud.xuantong.core.v2.event.ConfigReleaseEvent;
import cloud.xuantong.core.v2.event.ControlPlaneEvent;
import cloud.xuantong.core.v2.model.ConfigRelease;
import cloud.xuantong.core.v2.model.ResourceNameRules;
import cloud.xuantong.core.v2.repository.ConfigReleaseRepository;
import cloud.xuantong.core.service.ClientAccessTokenService;
import lombok.extern.slf4j.Slf4j;
import org.noear.snack4.ONode;
import org.noear.socketd.broker.BrokerListener;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Message;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import org.noear.solon.core.event.EventListener;

import java.io.IOException;

@Slf4j
@Component
public class ConfigBrokerV2Listener extends BrokerListener implements EventListener<ConfigReleaseEvent> {

    @Inject
    private ConfigReleaseRepository releaseRepository;
    @Inject
    private BrokerNodeConfig nodeConfig;
    @Inject private ClientAccessTokenService tokenService;
    @Inject private BrokerClientSessionRegistry sessionRegistry;

    @Override
    public void onOpen(Session session) throws IOException {
        if (!isAuthorized(session) || !hasValidSubscription(session)) {
            session.close();
            return;
        }
        super.onOpen(session);
        if (!sessionRegistry.register(
                BrokerClientSessionRegistry.Channel.CONFIG, session.param("token"), session)) {
            session.close();
            return;
        }
        log.info("V2 config subscriber connected: name={}, namespace={}, group={}",
                session.name(), session.param("namespace"), session.param("group"));
    }

    @Override
    public void onClose(Session session) {
        sessionRegistry.unregister(session);
        super.onClose(session);
    }

    @Override
    public void onMessage(Session session, Message message) throws IOException {
        sessionRegistry.touch(session);
        if (!tokenService.isAuthorized(
                session.param("token"), session.param("namespace"), session.param("group"))) {
            log.warn("Closing unauthorized V2 config session: name={}", session.name());
            session.close();
            return;
        }
        if ("/get".equals(message.event())) {
            handleGet(session, message);
            return;
        }
        if ("/ping".equals(message.event())) {
            if (message.isRequest()) {
                replyOnce(session, message, new StringEntity("{\"status\":\"ok\"}"));
            }
            return;
        }
        super.onMessage(session, message);
    }

    @Override
    public void onEvent(ConfigReleaseEvent releaseEvent) {
        ConfigRelease release = releaseEvent.release();
        ControlPlaneEvent event = ControlPlaneEvent.create(
                releaseEvent.eventType(),
                release.getNamespaceId(),
                release.getGroupName(),
                release.getDataId(),
                release.getRevision(),
                nodeConfig.getNodeId(),
                release);
        String subscriber = subscriberName(release.getNamespaceId(), release.getGroupName());
        try {
            broadcast("/config-change", new StringEntity(ONode.serialize(event)).at(subscriber + "*"));
            log.debug("V2 config event pushed: resource={} revision={} subscriber={}",
                    release.getDataId(), release.getRevision(), subscriber);
        } catch (Exception e) {
            if (isNoSubscriber(e)) {
                log.debug("No V2 config subscriber: resource={} revision={}",
                        release.getDataId(), release.getRevision());
            } else {
                log.warn("V2 config push failed: resource={} revision={}",
                        release.getDataId(), release.getRevision(), e);
            }
        }
    }

    private void handleGet(Session session, Message message) throws IOException {
        Entity entity = message.entity();
        String namespaceId = session.param("namespace");
        String groupName = session.param("group");
        String dataId = entity.meta("dataId");
        try {
            dataId = ResourceNameRules.validate("dataId", dataId);
            ConfigRelease release = releaseRepository.findLatest(namespaceId, groupName, dataId);
            if (release == null) {
                replyOnce(session, message, new StringEntity("").metaPut("found", "false"));
                return;
            }
            replyOnce(session, message, new StringEntity(release.getContent() == null ? "" : release.getContent())
                    .metaPut("found", "true")
                    .metaPut("revision", String.valueOf(release.getRevision()))
                    .metaPut("checksum", release.getChecksum())
                    .metaPut("contentType", release.getContentType()));
        } catch (IllegalArgumentException e) {
            replyOnce(session, message, new StringEntity("")
                    .metaPut("found", "false")
                    .metaPut("error", e.getMessage()));
        }
    }

    private void replyOnce(Session session, Message message, Entity response) throws IOException {
        session.replyEnd(message, response);
    }

    private boolean isAuthorized(Session session) {
        if (!tokenService.authorize(session.param("token"), session.param("namespace"), session.param("group"))) {
            log.warn("V2 config subscriber authentication failed: name={}", session.name());
            return false;
        }
        return true;
    }

    private boolean hasValidSubscription(Session session) {
        try {
            String namespaceId = ResourceNameRules.validate("namespace", session.param("namespace"));
            String groupName = ResourceNameRules.validate("group", session.param("group"));
            String expectedName = subscriberName(namespaceId, groupName);
            if (!expectedName.equals(session.name())) {
                log.warn("Invalid V2 subscriber name: expected={}, actual={}", expectedName, session.name());
                return false;
            }
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid V2 subscription parameters: name={}", session.name());
            return false;
        }
    }

    public static String subscriberName(String namespaceId, String groupName) {
        return "config:" + namespaceId + ":" + groupName;
    }

    private boolean isNoSubscriber(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("don't have");
    }
}
