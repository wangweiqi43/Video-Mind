package com.videomind;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({
        "com.videomind.module.video.mapper",
        "com.videomind.module.task.mapper",
        "com.videomind.module.chat.mapper",
        "com.videomind.module.auth.mapper",
        "com.videomind.module.agent.mapper"
})
@SpringBootApplication
@EnableScheduling
public class VideoMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoMindApplication.class, args);
    }
}
