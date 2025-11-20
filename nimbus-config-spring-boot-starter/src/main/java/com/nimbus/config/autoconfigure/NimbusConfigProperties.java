package com.nimbus.config.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "nimbus.config")
public class NimbusConfigProperties {

    /**
     * Nimbus配置中心服务器地址列表
     */
    private List<String> serverAddresses;

    /**
     * 应用名称
     */
    private String appName;

    /**
     * 环境名称（如：dev, test, prod）
     */
    private String environment = "dev";

    // Getters and Setters

    public List<String> getServerAddresses() {
        return serverAddresses;
    }

    public void setServerAddresses(List<String> serverAddresses) {
        this.serverAddresses = serverAddresses;
    }

    public String getAppName() {
        return appName;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }
}