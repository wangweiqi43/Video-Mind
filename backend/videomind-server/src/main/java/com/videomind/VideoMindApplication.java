package com.videomind;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@MapperScan({
        "com.videomind.module.video.mapper",
        "com.videomind.module.task.mapper",
        "com.videomind.module.chat.mapper"
})
@SpringBootApplication
public class VideoMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(VideoMindApplication.class, args);
    }
}
