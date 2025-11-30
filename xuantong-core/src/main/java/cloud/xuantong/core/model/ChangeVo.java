package cloud.xuantong.core.model;

import com.easy.query.core.annotation.EntityProxy;
import com.easy.query.core.proxy.ProxyEntityAvailable;
import cloud.xuantong.core.model.proxy.ChangeVoProxy;
import lombok.Data;

import java.util.Date;

/**
 * author wangjianwu
 * date 2025/11/25 00:36
 */
@Data
@EntityProxy
public class ChangeVo implements ProxyEntityAvailable<ChangeVo , ChangeVoProxy> {
    private String operator;
    private Date operateTime;
    private String project;
    private String environment;
    private String key;
}
