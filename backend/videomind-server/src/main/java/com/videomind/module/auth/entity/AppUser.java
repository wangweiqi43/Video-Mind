package com.videomind.module.auth.entity;
import com.baomidou.mybatisplus.annotation.*;import java.time.LocalDateTime;import lombok.Data;
@Data@TableName("app_user")public class AppUser{@TableId(type=IdType.AUTO)private Long id;private String publicId;private String username;private String passwordHash;private Boolean enabled;private LocalDateTime createdAt;}
