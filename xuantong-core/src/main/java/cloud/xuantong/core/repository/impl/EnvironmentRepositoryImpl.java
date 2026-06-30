package cloud.xuantong.core.repository.impl;

import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import cloud.xuantong.core.model.Environment;
import cloud.xuantong.core.model.proxy.EnvironmentProxy;
import cloud.xuantong.core.repository.EnvironmentRepository;
import org.noear.solon.annotation.Component;

import java.util.List;

@Component
public class EnvironmentRepositoryImpl implements EnvironmentRepository {
    @Db
    private EasyEntityQuery easyQuery;

    @Override
    public List<Environment> findAll() {
        EnvironmentProxy table = EnvironmentProxy.TABLE;
        return easyQuery.queryable(table)
                .orderBy(o -> o.order().asc())
                .toList();
    }

    @Override
    public Environment findByCode(String code) {
        EnvironmentProxy table = EnvironmentProxy.TABLE;
        return easyQuery.queryable(table)
                .where(o -> o.code().eq(code))
                .firstOrNull();
    }

    @Override
    public long save(Environment env) {
        return easyQuery.insertable(env).executeRows();
    }

    @Override
    public long update(Environment env) {
        return easyQuery.updatable(env).executeRows();
    }

    @Override
    public long setDefault(String code) {
        // 先取消所有默认设置
        easyQuery.updatable(Environment.class)
                .setColumns(e -> e.isDefault().set(false))
                .where(e ->  e.isDefault().eq(true))
                .executeRows();

        // 设置新的默认环境
        return easyQuery.updatable(Environment.class)
                .setColumns(e -> e.isDefault().set(true))
                .where(o -> o.code().eq(code))
                .executeRows();
    }

    @Override
    public long delete(String code) {
        return easyQuery.deletable(Environment.class)
                .allowDeleteStatement(true)
                .where(o -> o.code().eq(code))
                .executeRows();
    }
}