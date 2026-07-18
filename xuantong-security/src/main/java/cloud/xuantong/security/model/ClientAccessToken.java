package cloud.xuantong.security.model;

import cloud.xuantong.security.model.proxy.ClientAccessTokenProxy;
import com.easy.query.core.annotation.Column;
import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.annotation.Table;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import lombok.Data;
import java.util.Date;

@Data @EntityProxy @Table("client_access_token")
public class ClientAccessToken implements ProxyEntityAvailable<ClientAccessToken, ClientAccessTokenProxy> {
    @Column(primaryKey = true, generatedKey = true) private Long id;
    @Column private String tokenName;
    @Column private String tokenHash;
    @Column private String tenant;
    @Column private String namespaceId;
    @Column private String groupName;
    @Column private Boolean isActive;
    @Column private String createdBy;
    @Column private Date createdAt;
    @Column private Date expiresAt;
}
