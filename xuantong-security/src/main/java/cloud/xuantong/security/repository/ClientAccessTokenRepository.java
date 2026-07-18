package cloud.xuantong.security.repository;
import cloud.xuantong.security.model.ClientAccessToken;
import java.util.List;
public interface ClientAccessTokenRepository {
    ClientAccessToken find(Long id);
    ClientAccessToken findByHash(String tokenHash);
    List<ClientAccessToken> findAll();
    long save(ClientAccessToken token);
    long revoke(Long id);
    long countActive();
}
