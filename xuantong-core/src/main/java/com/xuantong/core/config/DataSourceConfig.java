package com.xuantong.core.config;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.noear.solon.annotation.Bean;
import org.noear.solon.annotation.Configuration;
import org.noear.solon.annotation.Inject;

import javax.sql.DataSource;

/**
 * author wangjianwu
 * date 2025/11/15 13:50
 */
@Slf4j
@Configuration
public class DataSourceConfig {

    @Bean(name = "xuantong", typed = true)
    public DataSource dataSource(@Inject("${xuantong}") HikariDataSource dataSource) {
        log.info("HikariDataSource >>>>>>init");
        return dataSource;
    }
}
