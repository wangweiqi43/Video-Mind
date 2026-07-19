# VideoMind

VideoMind 是一个本地可运行的 AI 视频内容理解平台。当前仓库已完成七个阶段：后端基础工程、普通视频上传到 MinIO、RocketMQ 异步任务流转、本地 FFmpeg 音频提取、Redisearch 向量化、智能助手 RAG 问答，以及分片上传、Redisson 锁、限流和重试兜底。

当前已完成 MindAgent 端到端接入：VideoMind 提供真实个人账号、右上角 MindAgent 绑定入口、OAuth2 授权码 + PKCE、令牌加密与自动刷新、历史视频按需同步，以及由 MindAgent 托管的高级会话。普通模式仍使用原有本地链路。

当前也已补充 Vue 3 前端工作台，支持视频上传、AI 总结、知识库向量化和智能助手对话。

项目现已加入 Agent Platform 高级模式：上传、音频提取和 ASR 是普通/高级模式共享的公共预处理；只有用户在当前模式明确点击分析按钮后，才会执行该模式的摘要链路。VideoMind 只向 MindAgent 提交 UTF-8 转录 TXT；MindAgent 完成规则清洗、固定 Token 切分、高完整度高级摘要和报告知识库发布。高级问答优先使用高级摘要知识库，必要时回查隐藏转录原文；普通模式继续使用原有 RAG 链路。

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
- Agent Client 统一处理签名、租户/用户上下文、幂等键、超时重试、SSE、错误码和 Trace ID
- 前端支持普通/高级双模式：切换模式只读取状态，不产生 AI Token 消耗；普通模式明确生成本地摘要，高级模式明确生成 MindAgent 高级摘要总结
- 视频支持 Agent PPT 参数配置、进度、失败重试、下载和历史版本
- `POST /api/videos/multipart/init` 初始化分片上传会话
- `POST /api/videos/multipart/{uploadId}/chunk?partNumber=1` 上传分片，Redis Bitmap 记录断点状态
- `GET /api/videos/multipart/{uploadId}/status` 查询已上传分片
- `POST /api/videos/multipart/{uploadId}/complete` 合并分片、MD5 校验、上传 MinIO 并写入元数据
- Redisson 分布式锁保护分片合并和同 MD5 视频解析，Redisson 令牌桶限制上传/解析频率
- RocketMQ 消费者设置最大重试次数，失败消息由 RocketMQ DLQ 兜底

## 本地启动

推荐使用项目的一键启动脚本：

```powershell
powershell -ExecutionPolicy Bypass -File E:\VideoMind\start.ps1
```

首次本地启动且未配置认证密钥时，脚本会在 Git 忽略的
`runtime/local-secrets.env` 中生成并持久保存开发密钥。生产环境必须通过环境变量或未跟踪的 `.env` 提供真实密钥。

也可以手动启动：

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

数据库结构只由 Flyway 管理，迁移文件位于
`backend/videomind-server/src/main/resources/db/migration`。已有的 pre-Flyway 数据库会以版本 7 建立 baseline，随后执行 V8 及更高版本；空数据库会从 V1 顺序迁移。

MinIO 控制台：

- Console: `http://localhost:9002`
- Endpoint: `http://localhost:9000`
- Username: `minioadmin`
- Password: `minioadmin`
- Bucket: `videomind-videos`

RocketMQ：

- NameServer: `localhost:9876`
- Broker: `localhost:10911`
- Topic: `videomind-video-analyze-topic`

Redis Stack：

- Redis: `localhost:6380`（容器内仍为 `6379`，避免占用本机已有 Redis）
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

MindAgent 尚未启动时保持相关开关为 `false`。启用正式对接时使用 OAuth 2.0 Authorization Code + PKCE S256，至少配置：

```dotenv
VIDEOMIND_AGENT_ENABLED=true
VIDEOMIND_AGENT_INGEST_ENABLED=true
VIDEOMIND_AGENT_CHAT_ENABLED=false
VIDEOMIND_AGENT_WEB_SEARCH_ENABLED=false
VIDEOMIND_AGENT_ADVANCED_REPORT_ENABLED=false
VIDEOMIND_AGENT_PRESENTATION_ENABLED=false
AGENT_PLATFORM_BASE_URL=http://localhost:8090
AGENT_PLATFORM_FRONTEND_URL=http://localhost:5174
AGENT_PLATFORM_OAUTH_CLIENT_ID=videomind
AGENT_PLATFORM_OAUTH_CLIENT_SECRET=<与 MindAgent 的 VIDEOMIND_CLIENT_SECRET 一致>
AGENT_PLATFORM_OAUTH_REDIRECT_URI=http://localhost:8080/api/integrations/mindagent/callback
AGENT_PLATFORM_WEBHOOK_SECRET=<与 MindAgent 的 VIDEOMIND_WEBHOOK_SECRET 一致>
MINIO_PRESIGN_ENDPOINT=http://host.docker.internal:9000
AGENT_PRESIGNED_URL_EXPIRY_SECONDS=900
AGENT_TASK_POLL_INTERVAL_SECONDS=5
```

这些值可写入被 Git 忽略的项目根目录 `.env`，`start.ps1` 会自动加载。VideoMind 继续通过 `MINIO_ENDPOINT`（默认 `localhost:9000`）读写对象；`MINIO_PRESIGN_ENDPOINT` 专门用于生成 MindAgent 可下载且 Host 签名一致的短期 URL。Agent 主开关开启时，后端会校验 URL、OAuth、Webhook 和入库预签名端点；任一子能力开启时主开关必须开启，联网搜索还要求高级聊天同时开启。

OAuth access/refresh token 仅以加密形式保存在 `mindagent_binding`。access token 将在到期前刷新；MindAgent 轮换 refresh token 后会立即替换本地密文。上游因无效或过期 Bearer JWT 返回 401/403 时只强制刷新并重放一次。刷新令牌永久失效时绑定进入 `REAUTH_REQUIRED`，用户可在右上角重新绑定。`AGENT_PLATFORM_API_KEY` 和 `AGENT_PLATFORM_SIGNING_SECRET` 只保留给旧兼容客户端，不是正式对接的鉴权方式。

其中 `VIDEOMIND_AGENT_WEB_SEARCH_ENABLED` 只控制能力是否对前端开放；真正是否搜索由每次高级聊天请求的 `webSearchEnabled` 决定，并以 `toolPolicy.webSearch` 传给 Agent。

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

上传视频不会自动解析，切换模式也不会产生请求或 Token 消耗。普通模式的摘要和本地知识库完全由 VideoMind 执行，不调用 MindAgent；高级模式只有在用户点击“生成高级摘要总结”后才复用或生成 ASR、幂等同步转录并启动 MindAgent 高级摘要。两种模式只共享 ASR，不共享摘要状态；不会传输视频、音频或普通摘要。

MindAgent 为每个视频维护隐藏的转录素材库和可见的高级摘要知识库。转录使用规则清洗与 `600 token / 80 token` 重叠切分；高级摘要使用 `VIDEOMIND_STUDY_NOTES_V1` 分段建立事实台账，保留数字、页码、人物、条件和例外，最终 Markdown 使用标题语义切分。高级聊天优先使用正式高级摘要库，覆盖不足时由 MindAgent 回查转录素材。自动高级摘要默认不开启联网搜索。

## Docker 注意事项

- 如果本机已有 MySQL 占用 `3306`，本项目 Docker MySQL 映射到 `3307`。
- Docker 持久化数据默认保存在 `E:/VideoMindData`，可通过 `VIDEOMIND_DATA_ROOT` 调整。
- 数据库名默认是 `videomind`，可通过 `MYSQL_DATABASE` 调整；MySQL 容器只负责创建数据库，禁止再挂载初始化 SQL。
- 本机工具默认使用 `E:/Java`、`E:/Maven`、`E:/NodeJS` 和 `E:/FFmpeg`。
- RocketMQ 使用 `apache/rocketmq:4.9.7`，本地开发下以 root 用户运行，避免 Windows Docker volume 权限导致 broker 反复重启。
- 如果 Docker Desktop 提示 WSL integration stopped，可执行 `wsl --shutdown` 后重启 Docker Desktop；必要时重启 Docker Desktop 进程。
