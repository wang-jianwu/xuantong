package com.xuantong.core.config;

import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;
import org.noear.solon.cache.jedis.RedisCacheService;
import org.noear.solon.data.cache.CacheService;

@Configuration
public class CacheConfig {
    @Bean(name = "base", typed = true)
    public CacheService configCache(@Inject("${cache.base}") RedisCacheService cache){
        return cache;
    }
}