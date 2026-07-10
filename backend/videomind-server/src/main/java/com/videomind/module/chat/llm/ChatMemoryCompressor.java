package com.videomind.module.chat.llm;

import com.videomind.module.chat.entity.ChatMessage;
import java.util.List;

public interface ChatMemoryCompressor {

    String compress(String existingSummary, List<ChatMessage> messages);
}
