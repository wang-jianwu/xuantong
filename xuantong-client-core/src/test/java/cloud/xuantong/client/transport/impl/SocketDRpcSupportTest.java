package cloud.xuantong.client.transport.impl;

import org.junit.jupiter.api.Test;
import org.noear.socketd.transport.core.Entity;
import org.noear.socketd.transport.core.Reply;
import org.noear.socketd.transport.core.Session;
import org.noear.socketd.transport.core.entity.StringEntity;
import org.noear.socketd.transport.stream.RequestStream;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class SocketDRpcSupportTest {
    @Test
    void completedRequestDoesNotSendAProgressPing() throws Exception {
        AtomicInteger pings = new AtomicInteger();
        AtomicReference<Entity> sentEntity = new AtomicReference<Entity>();
        Reply reply = reply("ok");
        Session session = session(true, pings, sentEntity, reply);
        Entity entity = new StringEntity("{}").at("config:public:DEFAULT_GROUP");

        Entity result = SocketDRpcSupport.request(session, "/get", entity, 1_000L);

        assertSame(reply, result);
        assertSame(entity, sentEntity.get());
        assertEquals("config:public:DEFAULT_GROUP", sentEntity.get().meta("@"));
        assertEquals(0, pings.get());
    }

    @Test
    void outstandingRequestUsesAProtocolPingAndStopsAfterCompletion() throws Exception {
        AtomicInteger pings = new AtomicInteger();
        AtomicReference<Entity> sentEntity = new AtomicReference<Entity>();
        Reply reply = reply("ok");
        Session session = session(false, pings, sentEntity, reply);

        Entity result = SocketDRpcSupport.request(
                session, "/get", new StringEntity("{}"), 1_000L);

        assertSame(reply, result);
        assertEquals(1, pings.get());
    }

    private Session session(
            boolean initiallyDone,
            AtomicInteger pings,
            AtomicReference<Entity> sentEntity,
            Reply reply) {
        AtomicBoolean done = new AtomicBoolean(initiallyDone);
        RequestStream stream = (RequestStream) Proxy.newProxyInstance(
                RequestStream.class.getClassLoader(),
                new Class<?>[]{RequestStream.class},
                (proxy, method, args) -> {
                    if ("isDone".equals(method.getName())) return done.get();
                    if ("await".equals(method.getName())) return reply;
                    if ("sid".equals(method.getName())) return "request-1";
                    if ("thenError".equals(method.getName())
                            || "thenProgress".equals(method.getName())
                            || "thenReply".equals(method.getName())) return proxy;
                    throw new UnsupportedOperationException(method.getName());
                });
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("sendAndRequest".equals(method.getName())) {
                    sentEntity.set((Entity) args[1]);
                    return stream;
                }
                if ("sendPing".equals(method.getName())) {
                    pings.incrementAndGet();
                    done.set(true);
                    return null;
                }
                if ("isValid".equals(method.getName()) || "isActive".equals(method.getName())) {
                    return true;
                }
                if ("isClosing".equals(method.getName())) return false;
                if ("sessionId".equals(method.getName())) return "session-1";
                if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(method.getName())) return proxy == args[0];
                if ("toString".equals(method.getName())) return "session-1";
                throw new UnsupportedOperationException(method.getName());
            }
        };
        return (Session) Proxy.newProxyInstance(
                Session.class.getClassLoader(), new Class<?>[]{Session.class}, handler);
    }

    private Reply reply(String body) {
        Entity delegate = new StringEntity(body);
        return (Reply) Proxy.newProxyInstance(
                Reply.class.getClassLoader(), new Class<?>[]{Reply.class},
                (proxy, method, args) -> {
                    if ("sid".equals(method.getName())) return "request-1";
                    if ("isEnd".equals(method.getName())) return true;
                    return method.invoke(delegate, args);
                });
    }
}
