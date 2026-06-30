-- Xuantong Config 数据库初始化脚本
CREATE DATABASE IF NOT EXISTS xuantong_config DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE xuantong_config;

-- 环境表
CREATE TABLE IF NOT EXISTS environment (
    code VARCHAR(20) PRIMARY KEY COMMENT '环境代码',
    name VARCHAR(50) NOT NULL COMMENT '环境名称',
    description VARCHAR(200) COMMENT '环境描述',
    `order` INT DEFAULT 0 COMMENT '显示顺序',
    is_default BOOLEAN DEFAULT FALSE COMMENT '是否默认环境',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) COMMENT '环境配置表';

-- 项目表
CREATE TABLE IF NOT EXISTS project (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) UNIQUE NOT NULL COMMENT '项目代码',
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    description VARCHAR(200) COMMENT '项目描述',
    owner VARCHAR(50) COMMENT '项目负责人',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否激活',
    created_by VARCHAR(50) COMMENT '创建人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT '项目表';

-- 用户表
CREATE TABLE IF NOT EXISTS user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码',
    email VARCHAR(100) UNIQUE COMMENT '邮箱',
    real_name VARCHAR(50) COMMENT '真实姓名',
    role ENUM('admin', 'user', 'guest') DEFAULT 'user' COMMENT '角色',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否激活',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_time TIMESTAMP NULL COMMENT '最后登录时间'
) COMMENT '用户表';

-- 配置项表
CREATE TABLE IF NOT EXISTS config_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    `key` VARCHAR(200) NOT NULL COMMENT '配置键',
    `value` TEXT COMMENT '配置值',
    description VARCHAR(500) COMMENT '配置描述',
    environment VARCHAR(20) NOT NULL COMMENT '环境',
    project VARCHAR(50) NOT NULL COMMENT '项目',
    version INT DEFAULT 1 COMMENT '版本号',
    is_encrypted BOOLEAN DEFAULT FALSE COMMENT '是否加密',
    created_by VARCHAR(50) COMMENT '创建人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_key_env_project (`key`, environment, project)
) COMMENT '配置项表';

-- 配置变更日志表
CREATE TABLE IF NOT EXISTS config_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_id BIGINT NOT NULL COMMENT '配置ID',
    operation ENUM('CREATE', 'UPDATE', 'DELETE') NOT NULL COMMENT '操作类型',
    old_value TEXT COMMENT '旧值',
    new_value TEXT COMMENT '新值',
    operator VARCHAR(50) NOT NULL COMMENT '操作人',
    operate_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    ip_address VARCHAR(45) COMMENT '操作IP',
    INDEX idx_config_id (config_id),
    INDEX idx_operate_time (operate_time)
) COMMENT '配置变更日志表';

-- 插入默认数据
INSERT IGNORE INTO environment (code, name, description, `order`, is_default) VALUES
('dev', '开发环境', '开发测试环境', 1, TRUE),
('test', '测试环境', '测试环境', 2, FALSE),
('prod', '生产环境', '生产环境', 3, FALSE);

-- 默认管理员账号: admin / admin123（首次登录会自动从MD5升级为BCrypt）
-- 生产环境请立即修改默认密码！
INSERT IGNORE INTO user (username, password, email, real_name, role, is_active) VALUES
('admin', MD5('admin123'), 'admin@xuantong.com', '系统管理员', 'admin', TRUE);

INSERT IGNORE INTO project (code, name, description, owner, is_active, created_by) VALUES
('demo', '演示项目', '演示项目', 'admin', TRUE, 'admin');