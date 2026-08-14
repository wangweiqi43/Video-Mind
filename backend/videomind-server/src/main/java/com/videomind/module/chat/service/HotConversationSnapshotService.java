package com.videomind.module.chat.service;

import com.videomind.module.chat.dto.HotConversationSnapshot;
import java.util.Optional;

public interface HotConversationSnapshotService {
    WriteResult write(HotConversationSnapshot snapshot);

    Optional<HotConversationSnapshot> get(Long conversationId);

    void evict(Long conversationId);

    enum WriteResult {
        UPDATED,
        STALE_REJECTED,
        SCOPE_MISMATCH
    }
}
