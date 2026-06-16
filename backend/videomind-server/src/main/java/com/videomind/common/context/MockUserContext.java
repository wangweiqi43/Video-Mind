package com.videomind.common.context;

/**
 * 第一阶段尚未接入登录鉴权，统一使用固定用户，后续可替换为 JWT / Session 上下文。
 */
public final class MockUserContext {

    private static final Long MOCK_USER_ID = 1L;

    private MockUserContext() {
    }

    public static Long currentUserId() {
        return MOCK_USER_ID;
    }
}

