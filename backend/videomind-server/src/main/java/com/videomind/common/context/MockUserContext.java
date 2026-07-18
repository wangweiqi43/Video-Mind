package com.videomind.common.context;

/** @deprecated 名称仅为兼容既有调用保留，当前返回经过 JWT 认证的真实用户。 */
@Deprecated
public final class MockUserContext {

    private MockUserContext() {
    }

    public static Long currentUserId() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Long userId)) {
            throw new com.videomind.common.exception.BizException(401, "请先登录");
        }
        return userId;
    }
}

