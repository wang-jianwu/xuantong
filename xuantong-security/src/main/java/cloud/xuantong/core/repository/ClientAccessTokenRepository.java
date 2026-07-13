package cloud.xuantong.core.repository;
import cloud.xuantong.core.model.ClientAccessToken;
import java.util.List;
public interface ClientAccessTokenRepository {
    ClientAccessToken find(Long id);
    ClientAccessToken findByHash(String tokenHash);
    List<ClientAccessToken> findAll();
    long save(ClientAccessToken token);
    long revoke(Long id);
    long countActive();
}
