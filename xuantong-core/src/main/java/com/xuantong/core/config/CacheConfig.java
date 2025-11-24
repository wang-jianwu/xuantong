package com.xuantong.core.config;

import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;
import org.noear.solon.cache.jedis.RedisCacheService;
import org.noear.solon.data.cache.CacheService;

@Slf4j
@Configuration
public class CacheConfig {
    @Bean(name = "base", typed = true)
    public CacheService configCache(@Inject("${cache.base}") RedisCacheService cache){
        log.info("RedisCacheService >>>>>>init");
        return cache;
    }
}