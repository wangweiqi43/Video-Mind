# VideoMind

VideoMind 是一个本地可运行的 AI 视频内容理解平台。当前仓库已完成七个阶段：后端基础工程、普通视频上传到 MinIO、RocketMQ 异步任务流转、本地 FFmpeg 音频提取、Redisearch 向量化、智能助手 RAG 问答，以及分片上传、Redisson 锁、限流和重试兜底。

当前也已补充 Vue 3 前端工作台，支持视频上传、AI 总结、知识库向量化和智能助手对话。

## 已完成内容

- Spring Boot 3 + Java 17 后端工程骨架
- MyBatis-Plus 基础配置
- 6 张核心业务表：视频、任务、转录、摘要、会话、消息
- 统一响应、统一异常处理、Mock 用户上下文
- 视频、任务、知识库、智能助手 REST API 骨架
- MySQL 和 MinIO Docker Compose 本地依赖
- `POST /api/videos/upload` 普通视频上传，计算 MD5，写入 MinIO 和 `video_file`
- `POST /api/tasks/analyze` 创建异步解析任务并发送 RocketMQ 消息
- RocketMQ 消费者执行本地 FFmpeg 音频提取、ASR、摘要生成，并写入转录和摘要结果；默认 Mock，可切换真实 API
- `POST /api/knowledge/vectorize/{taskId}` 将转录文本和摘要切片、生成 embedding，并写入 Redisearch；默认 Mock，可切换真实 API
- `GET /api/knowledge/status/{taskId}` 查询向量化状态和 chunk 数量
- `POST /api/chat/message` 生成 query embedding，检索视频知识片段，返回 Mock 回答和引用来源
- 智能助手支持最近窗口记忆，并在长会话时维护轻量历史摘要
- `POST /api/videos/multipart/init` 初始化分片上传会话
- `POST /api/videos/multipart/{uploadId}/chunk?partNumber=1` 上传分片，Redis Bitmap 记录断点状态
- `GET /api/videos/multipart/{uploadId}/status` 查询已上传分片
- `POST /api/videos/multipart/{uploadId}/complete` 合并分片、MD5 校验、上传 MinIO 并写入元数据
- Redisson 分布式锁保护分片合并和同 MD5 视频解析，Redisson 令牌桶限制上传/解析频率
- RocketMQ 消费者设置最大重试次数，失败消息由 RocketMQ DLQ 兜底

## 本地启动

```bash
docker compose up -d mysql minio rocketmq-namesrv rocketmq-broker redis-stack
cd backend/videomind-server
mvn spring-boot:run
```

前端启动：

```bash
cd frontend/videomind-web
npm install
npm run dev
```

访问地址：

- 后端 API：`http://localhost:8080`
- 前端页面：`http://localhost:5173`

默认数据库连接：

- Host: `localhost:3307`
- Database: `videomind`
- Username: `root`
- Password: `root`

MinIO 控制台：

- Console: `http://localhost:9001`
- Endpoint: `http://localhost:9000`
- Username: `minioadmin`
- Password: `minioadmin`
- Bucket: `videomind-videos`

RocketMQ：

- NameServer: `localhost:9876`
- Broker: `localhost:10911`
- Topic: `videomind-video-analyze-topic`

Redis Stack：

- Redis: `localhost:6379`
- RedisInsight: `http://localhost:8001`
- RediSearch index: `idx:videomind_knowledge`

FFmpeg：

- 默认命令：`ffmpeg`
- 可通过环境变量 `FFMPEG_BINARY_PATH` 指定完整路径
- 音频输出目录：`runtime/ffmpeg/task-{taskId}/audio.wav`
- 如需临时回退 Mock 音频提取，可设置 `VIDEOMIND_FFMPEG_MODE=mock`

## 真实 AI API 接入

当前已按 SiliconFlow endpoint 和你选择的模型配置为真实 API 模式。拿到 Key 后，推荐先设置一个通用 `SILICONFLOW_API_KEY`，四个能力都会自动复用它：

```bash
set SILICONFLOW_API_KEY=your_api_key
```

如果你想分别配置不同 Key，也可以使用下面的细分变量：

```bash
# ASR：音频转文字
set VIDEOMIND_ASR_MODE=real
set ASR_API_ENDPOINT=https://api.siliconflow.cn/v1/audio/transcriptions
set ASR_API_KEY=your_api_key
set ASR_MODEL=TeleAI/TeleSpeechASR

# 视频摘要大模型
set VIDEOMIND_SUMMARY_MODE=real
set SUMMARY_API_ENDPOINT=https://api.siliconflow.cn/v1/chat/completions
set SUMMARY_API_KEY=your_api_key
set SUMMARY_MODEL=deepseek-ai/DeepSeek-V4-Flash

# Embedding：知识库向量化和 RAG query embedding
set VIDEOMIND_EMBEDDING_MODE=real
set EMBEDDING_API_ENDPOINT=https://api.siliconflow.cn/v1/embeddings
set EMBEDDING_API_KEY=your_api_key
set EMBEDDING_MODEL=Qwen/Qwen3-Embedding-0.6B
set KNOWLEDGE_EMBEDDING_DIM=1024
set EMBEDDING_DIMENSION=1024

# Chat：智能助手回答生成
set VIDEOMIND_CHAT_MODE=real
set CHAT_API_ENDPOINT=https://api.siliconflow.cn/v1/chat/completions
set CHAT_API_KEY=your_api_key
set CHAT_MODEL=deepseek-ai/DeepSeek-V4-Flash
```

当前 `application.yml` 已按上述 SiliconFlow endpoint 和模型名提供默认值，并且默认启用 `real` 模式。如果暂时没有 Key、只想继续用 Mock 跑本地流程，可以设置 `VIDEOMIND_ASR_MODE=mock`、`VIDEOMIND_SUMMARY_MODE=mock`、`VIDEOMIND_EMBEDDING_MODE=mock`、`VIDEOMIND_CHAT_MODE=mock`。

真实接口接入点集中在以下文件：

- ASR 请求和响应映射：[RealSpeechToTextClient.java](C:/Users/Administrator/Documents/VideoMind/backend/videomind-server/src/main/java/com/videomind/module/task/analysis/real/RealSpeechToTextClient.java)
- 视频摘要请求和响应映射：[RealVideoSummaryClient.java](C:/Users/Administrator/Documents/VideoMind/backend/videomind-server/src/main/java/com/videomind/module/task/analysis/real/RealVideoSummaryClient.java)
- Embedding 请求和向量字段映射：[RealEmbeddingClient.java](C:/Users/Administrator/Documents/VideoMind/backend/videomind-server/src/main/java/com/videomind/module/knowledge/embedding/RealEmbeddingClient.java)
- 智能助手 Chat 请求和响应映射：[RealChatAnswerClient.java](C:/Users/Administrator/Documents/VideoMind/backend/videomind-server/src/main/java/com/videomind/module/chat/llm/RealChatAnswerClient.java)
- 统一配置项：[AiProperties.java](C:/Users/Administrator/Documents/VideoMind/backend/videomind-server/src/main/java/com/videomind/config/AiProperties.java)
- 默认配置占位：[application.yml](C:/Users/Administrator/Documents/VideoMind/backend/videomind-server/src/main/resources/application.yml)

当前真实客户端默认兼容常见的 Bearer Token、OpenAI-like `messages`、`choices[0].message.content` 和 `data[0].embedding` 响应格式。如果你提供的 API 字段不同，只需要修改对应 real client 的 `buildRequest` 或 `parseResponse/parseContent/parseVector` 方法。

## 基础验证

```bash
curl http://localhost:8080/api/videos/list
curl -X POST http://localhost:8080/api/chat/session
curl http://localhost:8080/api/chat/session/list
curl -F "file=@/path/to/demo.mp4" http://localhost:8080/api/videos/upload
curl -X POST http://localhost:8080/api/tasks/analyze \
  -H "Content-Type: application/json" \
  -d "{\"videoId\":1,\"autoVectorize\":false}"
curl http://localhost:8080/api/tasks/1
curl http://localhost:8080/api/tasks/1/result
curl -X POST http://localhost:8080/api/knowledge/vectorize/1
curl http://localhost:8080/api/knowledge/status/1
curl -X POST http://localhost:8080/api/chat/session
curl -X POST http://localhost:8080/api/chat/message \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":1,\"question\":\"总结一下这个视频\"}"
```

分片上传示例：

```bash
curl -X POST http://localhost:8080/api/videos/multipart/init \
  -H "Content-Type: application/json" \
  -d "{\"filename\":\"demo.mp4\",\"fileMd5\":\"<md5>\",\"fileSize\":12345,\"contentType\":\"video/mp4\",\"totalParts\":2,\"chunkSize\":8192}"
curl -F "file=@part-1.bin" "http://localhost:8080/api/videos/multipart/<uploadId>/chunk?partNumber=1"
curl http://localhost:8080/api/videos/multipart/<uploadId>/status
curl -X POST http://localhost:8080/api/videos/multipart/<uploadId>/complete
```

## 阶段边界

当前已预留真实 ASR、摘要大模型、Embedding 和 Chat API 接入骨架，默认仍以 Mock 模式运行。后续可继续完善前端分片上传页面、生产监控、API 供应商适配和鉴权体系。

## Docker 注意事项

- 如果本机已有 MySQL 占用 `3306`，本项目 Docker MySQL 映射到 `3307`。
- RocketMQ 使用 `apache/rocketmq:4.9.7`，本地开发下以 root 用户运行，避免 Windows Docker volume 权限导致 broker 反复重启。
- 如果 Docker Desktop 提示 WSL integration stopped，可执行 `wsl --shutdown` 后重启 Docker Desktop；必要时重启 Docker Desktop 进程。
