package cloud.xuantong.security.repository;
import cloud.xuantong.common.page.PageQuery;
import cloud.xuantong.common.page.PageResult;
import cloud.xuantong.security.model.ClientAccessToken;
import java.util.List;
public interface ClientAccessTokenRepository {
    ClientAccessToken find(Long id);
    ClientAccessToken findByHash(String tokenHash);
    List<ClientAccessToken> findAll();
    PageResult<ClientAccessToken> findPage(
            String keyword, Boolean active, PageQuery pageQuery);
    long save(ClientAccessToken token);
    long revoke(Long id);
    long countActive();
}
