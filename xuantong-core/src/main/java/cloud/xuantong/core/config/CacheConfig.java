package cloud.xuantong.core.config;

import cn.hutool.core.util.StrUtil;
import org.noear.solon.Solon;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.cache.jedis.RedisCacheService;
import org.noear.solon.data.cache.CacheService;
import org.noear.solon.data.cache.LocalCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 缓存配置 — 自动检测
 * <p>
 * 配了 Redis → 用 RedisCacheService（适合多机集群）
 * 没配 Redis → 用 LocalCacheService（单机开箱即用）
 */
@Configuration
public class CacheConfig {
    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean(name = "base", typed = true)
    public CacheService configCache() {
        String redisServer = Solon.cfg().get("cache.base.server");

        if (StrUtil.isNotBlank(redisServer)) {
            log.info("Redis cache configured at: {}", redisServer);
            try {
                // 需要 solon-cache-jedis 依赖（默认已包含）
                RedisCacheService redisCache = new RedisCacheService(Solon.cfg().getProp("cache.base"));
                log.info("RedisCacheService initialized successfully");
                return redisCache;
            } catch (NoClassDefFoundError e) {
                log.warn("Redis configured but solon-cache-jedis not on classpath. "
                        + "Add solon-cache-jedis dependency or remove cache.base config. "
                        + "Falling back to local cache.");
            } catch (Exception e) {
                log.warn("Failed to create RedisCacheService: {}. Falling back to local cache.", e.getMessage());
            }
        }

        // 默认：本地内存缓存
        log.info("Using LocalCacheService (no Redis configured)");
        return new LocalCacheService();
    }
}