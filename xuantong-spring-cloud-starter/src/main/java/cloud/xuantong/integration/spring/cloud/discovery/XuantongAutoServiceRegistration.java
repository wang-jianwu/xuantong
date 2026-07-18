package cloud.xuantong.integration.spring.cloud.discovery;

import cloud.xuantong.integration.spring.cloud.autoconfigure.XuantongSpringCloudProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistration;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistrationProperties;
import org.springframework.cloud.client.serviceregistry.RegistrationLifecycle;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.SmartLifecycle;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Registers the application only after its actual listening port is known. */
public class XuantongAutoServiceRegistration implements
        AutoServiceRegistration,
        SmartLifecycle,
        ApplicationListener<WebServerInitializedEvent>,
        DisposableBean {
    private final ApplicationContext applicationContext;
    private final XuantongServiceRegistry serviceRegistry;
    private final AutoServiceRegistrationProperties autoRegistrationProperties;
    private final XuantongSpringCloudProperties properties;
    private final XuantongRegistration registration;
    private final List<RegistrationLifecycle<XuantongRegistration>> lifecycles;
    private final AtomicBoolean running = new AtomicBoolean();

    public XuantongAutoServiceRegistration(
            ApplicationContext applicationContext,
            XuantongServiceRegistry serviceRegistry,
            AutoServiceRegistrationProperties autoRegistrationProperties,
            XuantongSpringCloudProperties properties,
            XuantongRegistration registration,
            List<RegistrationLifecycle<XuantongRegistration>> lifecycles) {
        this.applicationContext = applicationContext;
        this.serviceRegistry = serviceRegistry;
        this.autoRegistrationProperties = autoRegistrationProperties;
        this.properties = properties;
        this.registration = registration;
        this.lifecycles = List.copyOf(lifecycles);
    }

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        if (event.getApplicationContext()
                instanceof ConfigurableWebServerApplicationContext context
                && "management".equals(context.getServerNamespace())) {
            return;
        }
        if (registration.getPort() <= 0) {
            registration.setPort(event.getWebServer().getPort());
        }
        start();
    }

    @Override
    public void start() {
        if (!isEnabled() || registration.getPort() <= 0
                || !running.compareAndSet(false, true)) {
            return;
        }
        try {
            applicationContext.publishEvent(
                    new InstancePreRegisteredEvent(this, registration));
            for (RegistrationLifecycle<XuantongRegistration> lifecycle : lifecycles) {
                lifecycle.postProcessBeforeStartRegister(registration);
            }
            serviceRegistry.register(registration);
            for (RegistrationLifecycle<XuantongRegistration> lifecycle : lifecycles) {
                lifecycle.postProcessAfterStartRegister(registration);
            }
            applicationContext.publishEvent(
                    new InstanceRegisteredEvent<>(this, properties.getDiscovery()));
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        for (RegistrationLifecycle<XuantongRegistration> lifecycle : lifecycles) {
            lifecycle.postProcessBeforeStopRegister(registration);
        }
        serviceRegistry.deregister(registration);
        for (RegistrationLifecycle<XuantongRegistration> lifecycle : lifecycles) {
            lifecycle.postProcessAfterStopRegister(registration);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return 0;
    }

    @Override
    public void destroy() {
        stop();
        serviceRegistry.close();
    }

    private boolean isEnabled() {
        return properties.isEnabled()
                && properties.getDiscovery().isEnabled()
                && properties.getDiscovery().isRegister()
                && autoRegistrationProperties.isEnabled();
    }
}
