package cloud.xuantong.server.admin.security;

import cloud.xuantong.security.model.User;
import org.noear.solon.core.handle.Context;

public final class AdminSecurityContext {
    static final String USER_ATTRIBUTE = "xuantong.admin.user";

    private AdminSecurityContext() {
    }

    public static User currentUser(Context context) {
        return context == null ? null : context.attr(USER_ATTRIBUTE);
    }

    static void bind(Context context, User user) {
        context.attrSet(USER_ATTRIBUTE, user);
    }
}
