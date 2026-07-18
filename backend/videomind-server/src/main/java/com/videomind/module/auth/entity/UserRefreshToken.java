package com.videomind.module.auth.entity;
import com.baomidou.mybatisplus.annotation.*;import java.time.LocalDateTime;import lombok.Data;
@Data@TableName("user_refresh_token")public class UserRefreshToken{@TableId(type=IdType.AUTO)private Long id;private Long userId;private String tokenHash;private LocalDateTime expiresAt;private LocalDateTime revokedAt;private LocalDateTime createdAt;}
