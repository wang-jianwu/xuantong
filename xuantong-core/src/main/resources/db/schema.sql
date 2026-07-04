-- Xuantong Config 数据库初始化脚本
-- 兼容 H2（默认）、MySQL、PostgreSQL
-- 启动时自动执行，无需手动导入

-- 环境表
CREATE TABLE IF NOT EXISTS environment (
    code VARCHAR(20) PRIMARY KEY COMMENT '环境代码',
    name VARCHAR(50) NOT NULL COMMENT '环境名称',
    description VARCHAR(200) COMMENT '环境描述',
    `order` INT DEFAULT 0 COMMENT '显示顺序',
    is_default BOOLEAN DEFAULT FALSE COMMENT '是否默认环境',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

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
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 用户表
CREATE TABLE IF NOT EXISTS `user` (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(50) UNIQUE NOT NULL COMMENT '用户名',
    password VARCHAR(100) NOT NULL COMMENT '密码',
    email VARCHAR(100) UNIQUE COMMENT '邮箱',
    real_name VARCHAR(50) COMMENT '真实姓名',
    role VARCHAR(10) DEFAULT 'user' COMMENT '角色：admin/user',
    is_active BOOLEAN DEFAULT TRUE COMMENT '是否激活',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login_time TIMESTAMP NULL COMMENT '最后登录时间'
);

-- 配置项表
CREATE TABLE IF NOT EXISTS config_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    `key` VARCHAR(200) NOT NULL COMMENT '配置键',
    `value` TEXT COMMENT '配置值',
    description VARCHAR(500) COMMENT '配置描述',
    environment VARCHAR(20) NOT NULL COMMENT '环境',
    project VARCHAR(50) NOT NULL COMMENT '项目',
    version INT DEFAULT 1 COMMENT '版本号',
    value_type VARCHAR(20) DEFAULT 'STRING' COMMENT '值类型：STRING/NUMBER/BOOLEAN/JSON',
    is_encrypted BOOLEAN DEFAULT FALSE COMMENT '是否加密',
    created_by VARCHAR(50) COMMENT '创建人',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (`key`, environment, project),
    INDEX idx_project_env (project, environment),
    INDEX idx_updated_at (updated_at)
);

-- 配置变更日志表
CREATE TABLE IF NOT EXISTS config_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_id BIGINT NOT NULL COMMENT '配置ID',
    project VARCHAR(50) NOT NULL COMMENT '项目（冗余，避免JOIN）',
    environment VARCHAR(20) NOT NULL COMMENT '环境（冗余，避免JOIN）',
    operation VARCHAR(10) NOT NULL COMMENT '操作类型：CREATE/UPDATE/DELETE',
    old_value TEXT COMMENT '旧值',
    new_value TEXT COMMENT '新值',
    operator VARCHAR(50) NOT NULL COMMENT '操作人',
    operate_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
    ip_address VARCHAR(45) COMMENT '操作IP',
    INDEX idx_config_id (config_id),
    INDEX idx_clog_project_env (project, environment),
    INDEX idx_operate_time (operate_time)
);

-- 默认数据（兼容 H2 / MySQL）
INSERT INTO environment (code, name, description, `order`, is_default)
SELECT 'dev', '开发环境', '开发测试环境', 1, TRUE
WHERE NOT EXISTS (SELECT 1 FROM environment WHERE code = 'dev');

INSERT INTO environment (code, name, description, `order`, is_default)
SELECT 'test', '测试环境', '测试环境', 2, FALSE
WHERE NOT EXISTS (SELECT 1 FROM environment WHERE code = 'test');

INSERT INTO environment (code, name, description, `order`, is_default)
SELECT 'prod', '生产环境', '生产环境', 3, FALSE
WHERE NOT EXISTS (SELECT 1 FROM environment WHERE code = 'prod');

-- 默认管理员（admin / admin123，首次登录自动从 MD5 升级为 BCrypt）
INSERT INTO `user` (username, password, email, real_name, role, is_active)
SELECT 'admin', '0192023a7bbd73250516f069df18b500', 'admin@xuantong.com', '系统管理员', 'admin', TRUE
WHERE NOT EXISTS (SELECT 1 FROM `user` WHERE username = 'admin');

INSERT INTO project (code, name, description, owner, is_active, created_by)
SELECT 'demo', '演示项目', '演示项目', 'admin', TRUE, 'admin'
WHERE NOT EXISTS (SELECT 1 FROM project WHERE code = 'demo');

-- 演示配置项（供 TestController 使用）
INSERT INTO config_item (`key`, `value`, description, environment, project, version, value_type, is_encrypted, created_by)
SELECT 'demo.aaa', 'hello', '演示字符串配置', 'dev', 'demo', 1, 'STRING', FALSE, 'admin'
WHERE NOT EXISTS (SELECT 1 FROM config_item WHERE `key` = 'demo.aaa' AND environment = 'dev' AND project = 'demo');

INSERT INTO config_item (`key`, `value`, description, environment, project, version, value_type, is_encrypted, created_by)
SELECT 'demo.list', '[{"name":"德莱厄斯","age":18},{"name":"锐雯","age":18}]', '演示列表配置', 'dev', 'demo', 1, 'STRING', FALSE, 'admin'
WHERE NOT EXISTS (SELECT 1 FROM config_item WHERE `key` = 'demo.list' AND environment = 'dev' AND project = 'demo');

INSERT INTO config_item (`key`, `value`, description, environment, project, version, value_type, is_encrypted, created_by)
SELECT 'demo.map', '{"MALE":[{"name":"德莱厄斯","age":18}],"FEMALE":[{"name":"锐雯","age":18}]}', '演示Map配置', 'dev', 'demo', 1, 'STRING', FALSE, 'admin'
WHERE NOT EXISTS (SELECT 1 FROM config_item WHERE `key` = 'demo.map' AND environment = 'dev' AND project = 'demo');

INSERT INTO config_item (`key`, `value`, description, environment, project, version, value_type, is_encrypted, created_by)
SELECT 'demo.aaa.yml', 'solon.cloud.xxx:\n  server: "localhost:8849,localhost:8847" \n  namespace: "3887EBC8-CD24-4BF7-BACF-58643397C138" #nacos\n  contextPath: "nacosx"\n  username: "aaa"\n  password: "bbb"', '演示Solon Cloud Nacos配置', 'dev', 'demo', 1, 'yaml', FALSE, 'admin'
WHERE NOT EXISTS (SELECT 1 FROM config_item WHERE `key` = 'demo.aaa.yml' AND environment = 'dev' AND project = 'demo');
