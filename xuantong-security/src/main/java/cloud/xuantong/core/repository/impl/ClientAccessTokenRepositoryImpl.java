package cloud.xuantong.core.repository.impl;
import cloud.xuantong.core.model.ClientAccessToken;
import cloud.xuantong.core.repository.ClientAccessTokenRepository;
import com.easy.query.api.proxy.client.EasyEntityQuery;
import com.easy.query.solon.annotation.Db;
import org.noear.solon.annotation.Component;
import java.util.List;
@Component
public class ClientAccessTokenRepositoryImpl implements ClientAccessTokenRepository {
    @Db private EasyEntityQuery easyQuery;
    public ClientAccessToken find(Long id) { return easyQuery.queryable(ClientAccessToken.class).whereById(id).firstOrNull(); }
    public ClientAccessToken findByHash(String hash) { return easyQuery.queryable(ClientAccessToken.class).where(o -> o.tokenHash().eq(hash)).firstOrNull(); }
    public List<ClientAccessToken> findAll() { return easyQuery.queryable(ClientAccessToken.class).orderBy(o -> o.id().desc()).toList(); }
    public long save(ClientAccessToken token) { return easyQuery.insertable(token).executeRows(true); }
    public long revoke(Long id) { return easyQuery.updatable(ClientAccessToken.class).setColumns(o -> o.isActive().set(false)).where(o -> o.id().eq(id)).executeRows(); }
    public long countActive() { return easyQuery.queryable(ClientAccessToken.class).where(o -> o.isActive().eq(true)).count(); }
}
