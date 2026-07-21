package cloud.xuantong.integration.spring.boot.autoconfigure;

import cloud.xuantong.client.annotation.ConfigValue;
import cloud.xuantong.client.listener.ConfigListener;
import cloud.xuantong.client.listener.ListenerRegistration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XuantongConfigValueProcessorTest {

    @Test
    void closesManagedBeanListenerDuringDestruction() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        FakeConfigClient client = new FakeConfigClient();
        XuantongConfigValueProcessor processor = new XuantongConfigValueProcessor(
                () -> client, beanFactory);
        RefreshableBean bean = new RefreshableBean();

        processor.postProcessBeforeInitialization(bean, "refreshableBean");
        processor.postProcessBeforeDestruction(bean, "refreshableBean");

        assertTrue(client.registrationClosed.get());
    }

    @Test
    void doesNotAttachLongLivedListenerToPrototypeBean() {
        DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
        FakeConfigClient client = new FakeConfigClient();
        GenericBeanDefinition definition = new GenericBeanDefinition();
        definition.setBeanClass(RefreshableBean.class);
        definition.setScope(BeanDefinition.SCOPE_PROTOTYPE);
        beanFactory.registerBeanDefinition("prototypeBean", definition);
        XuantongConfigValueProcessor processor = new XuantongConfigValueProcessor(
                () -> client, beanFactory);

        processor.postProcessBeforeInitialization(new RefreshableBean(), "prototypeBean");

        assertEquals(0, client.listenCalls.get());
    }

    private static final class RefreshableBean {
        @ConfigValue("demo.message")
        private String message;
    }

    private static final class FakeConfigClient
            implements XuantongConfigValueProcessor.ConfigClientAccess {
        private final AtomicInteger listenCalls = new AtomicInteger();
        private final AtomicBoolean registrationClosed = new AtomicBoolean();

        @Override
        public String get(String dataId, String defaultValue) {
            return defaultValue;
        }

        @Override
        public <T> T getObject(String dataId, Class<T> type) {
            return null;
        }

        @Override
        public <T> List<T> getObjectList(String dataId, Class<T> type) {
            return List.of();
        }

        @Override
        public <K, V> Map<K, V> getObjectMap(
                String dataId, Type keyType, Type valueType) {
            return Map.of();
        }

        @Override
        public ListenerRegistration listen(String dataId, ConfigListener listener) {
            listenCalls.incrementAndGet();
            return () -> registrationClosed.set(true);
        }
    }
}
