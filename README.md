# VideoMind

VideoMind 是一个本地优先的 AI 视频理解与多知识库问答平台。系统把视频语音、关键帧文字和用户上传文档统一写入本地知识库，通过 Elasticsearch 混合检索与有边界的 Planner–Executor–Critic 工作流生成带来源的回答。

项目只保留一种本地运行模式，不依赖远程 Agent 平台，知识向量统一写入 Elasticsearch。

## 核心能力

- 视频上传：普通上传、MD5 去重和 Redis Bitmap 分片续传，原件存入 MinIO。
- 可恢复分析：RocketMQ 事务消息、Inbox 去重、CAS Lease、Checkpoint 和唯一业务指纹共同保证消费幂等。
- 多模态时间轴：腾讯云 ASR 返回时间戳分段，本机 PaddleOCR 提取关键帧文字，按时间窗口融合为 `timeline.md`。
- 本地知识库：每个视频自动拥有系统知识库；用户可创建多个文档库并上传 PDF 等附件，由本机 MinerU CPU pipeline 解析。
- 混合检索：Elasticsearch BM25 + kNN 召回、RRF 融合、BGE Rerank，支持单路失败降级。
- 智能工作流：普通问答使用规则 Planner，深度思考使用结构化 LLM Planner/Critic；工具范围固定，不提供联网搜索或任意代码执行。
- 热会话上下文：Cache Redis 保存摘要、摘要右边界、完成轮数、最近对话和固定知识库范围，失效时从 MySQL 重建。
- 证据审计：回答引用知识库、文档版本和 chunk；视频引用额外包含开始、结束时间。
- 可恢复删除：知识库和视频通过异步事务任务物理清理 ES、MinIO、MySQL 和缓存。

## 端到端链路

```text
上传视频
  → RocketMQ 事务半消息
  → MySQL 本地事务创建 processing_task / mq_transaction_event / task_record
  → 提交事务消息
  → 消费者 Inbox 去重 + CAS Lease
  → FFmpeg 提取音频并记录实际音轨时长
  → 120 秒逻辑分片（两侧各 1 秒上下文）
  → 腾讯云 Base64 时间戳 ASR + 分片 TaskId 续跑
  → PaddleOCR 关键帧识别
  → 语音/OCR 时间轴融合
  → timeline.md + 视频系统知识库
  → Embedding + Elasticsearch 索引
  → 视频摘要与 Evidence 发布

上传文档
  → MinIO 保存原件
  → MinerU CPU pipeline 解析
  → Chunk + Embedding
  → Elasticsearch 索引

固定范围会话
  → Cache Redis 热快照 / MySQL 回源
  → Planner
  → 视频时间轴检索 + 用户文档检索
  → BM25 + kNN + RRF + Rerank
  → Critic 证据检查
  → 带引用回答
```

## 运行要求

- Windows 10/11 与 Docker Desktop。
- Java 17、Maven 3.9、Node.js 20 或兼容版本。
- FFmpeg/ffprobe。`start.ps1` 优先使用 Git 忽略目录中的 `runtime/tools/ffmpeg-8.1.2-essentials_build/bin`，其次使用 `E:/FFmpeg/bin`。
- 16 GB-class 物理内存。MinerU 使用 CPU `pipeline`、单 worker、单并发，不占用 GPU。
- 首次运行 MinerU/PaddleOCR 时需要下载模型，完成后缓存到 `VIDEOMIND_DATA_ROOT`。
- 可用的腾讯云录音文件识别凭据和 SiliconFlow API Key。

## 快速启动

1. 创建本机配置：

```powershell
Set-Location E:\VideoMind
Copy-Item .env.example .env
```

2. 将真实凭据写入当前 Windows 用户环境。不要把值写入仓库、日志或聊天记录：

```powershell
[Environment]::SetEnvironmentVariable("TENCENT_CLOUD_SECRET_ID", "<SecretId>", "User")
[Environment]::SetEnvironmentVariable("TENCENT_CLOUD_SECRET_KEY", "<SecretKey>", "User")
[Environment]::SetEnvironmentVariable("SILICONFLOW_API_KEY", "<API Key>", "User")
```

3. 一键启动：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\start.ps1
```

启动脚本会：

- 检查 Docker、内存、Java、Maven、Node、FFmpeg 和 ffprobe。
- 启动 MySQL、MinIO、RocketMQ、Redis Stack、Cache Redis、Elasticsearch、MinerU 和 PaddleOCR。
- 等待所有基础设施健康后隐藏启动后端和前端。
- 在 `runtime/local-secrets.env` 生成 Git 忽略的本地 JWT/令牌加密密钥（仅开发环境）。

访问地址：

| 服务 | 地址 |
| --- | --- |
| 前端 | `http://localhost:5173` |
| 后端 | `http://localhost:8080` |
| 后端健康检查 | `http://localhost:8080/actuator/health` |
| MySQL | `localhost:3307` |
| MinIO API / Console | `localhost:9000` / `http://localhost:9002` |
| RocketMQ NameServer / Broker | `localhost:9876` / `localhost:10911` |
| Redis Stack / RedisInsight | `localhost:6380` / `http://localhost:8001` |
| Cache Redis | `localhost:6382` |
| Elasticsearch | `http://localhost:9201` |
| MinerU | `http://localhost:8003` |
| PaddleOCR | `http://localhost:8868` |

## 数据职责

| 组件 | 职责 |
| --- | --- |
| MySQL | 用户、视频、任务状态机、Checkpoint、知识库版本、chunk 元数据、会话、消息和工作流审计 |
| MinIO | 视频原件、用户文档、解析资产和 `timeline.md` |
| Elasticsearch | 视频与文档 chunk 的 BM25/kNN 索引 |
| Redis Stack | 分布式锁、限流和分片上传 Bitmap |
| Cache Redis | 会话热快照和最近上下文；不可用时聊天回退 MySQL |
| RocketMQ | 事务任务投递和至少一次消费 |

Docker 持久化数据默认位于 `E:/VideoMindData`，可通过 `VIDEOMIND_DATA_ROOT` 修改。数据库结构仅由 Flyway 管理，迁移文件位于 `backend/videomind-server/src/main/resources/db/migration`。

## 视频分析状态机

`POST /api/tasks/analyze` 对外继续返回业务 `taskId`。内部事务执行器在同一 MySQL 事务中创建或复用处理任务、事务事件和视频业务任务。

视频处理 Checkpoint 固定为：

```text
AUDIO_EXTRACTED
→ ASR_PERSISTED
→ OCR_PERSISTED
→ TIMELINE_INDEXED
→ SUMMARY_SAVED
→ PUBLISHED
```

处理状态：

```text
PENDING → PROCESSING → SUCCESS
                    ↘ RETRY_WAIT → PROCESSING
                    ↘ FAILED / DEAD
                    ↘ CANCEL_REQUESTED → CANCELLED
```

任务幂等分为三层：

1. 业务指纹避免同一视频与同一模型配置并发创建重复任务。
2. Inbox 记录避免同一 RocketMQ 事件重复消费。
3. CAS Lease 与 Checkpoint 保证崩溃恢复时只续跑未完成阶段。

取消是协作式的：处理器会在阶段边界、ASR 轮询和逐帧 OCR 时检查取消状态。已进入破坏性删除阶段的任务不可取消。

### 腾讯云 ASR 输入

- 所有音频都使用 `SourceType=1`，直接提交 Base64 `Data` 和编码前 `DataLen`，腾讯云不需要访问本机 MinIO。
- 音轨按 120 秒逻辑窗口切分，每片前后各保留 1 秒上下文；16 kHz、单声道、16-bit PCM 的最长分片约 3.9 MiB，低于 5 MiB 限制。
- 合并时只保留句子中点属于当前逻辑窗口的结果，消除重叠区重复文本，再叠加分片起点得到原视频绝对时间。
- `video_asr_chunk` 保存每片的音频摘要、模型签名、提交次数、腾讯 TaskId 和结果；状态为 `PLANNED → SUBMITTING → SUBMITTED → SUCCEEDED/FAILED`。
- 消费者崩溃后，已有 TaskId 的分片继续轮询而不重新提交；超过腾讯任务 24 小时有效期后才重新创建任务。
- 分片串行提交，默认并发为 1。视频流时长和实际音轨时长分别记录，ASR 只按实际音轨规划分片。
- 默认引擎为 `16k_zh_en_2.0`，可通过 `TENCENT_ASR_ENGINE_MODEL` 调整。

可调参数：

```dotenv
TENCENT_ASR_CHUNK_SECONDS=120
TENCENT_ASR_CHUNK_OVERLAP_MS=1000
TENCENT_ASR_MAX_INLINE_BYTES=5242880
TENCENT_ASR_SUBMISSION_UNKNOWN_TIMEOUT_SECONDS=120
TENCENT_ASR_PROVIDER_TASK_TTL_HOURS=24
```

## 知识库与时间轴

- 每个视频对应一个系统知识库，视频会话的范围第一项始终是该系统库。
- 用户可创建多个文档知识库并上传附件；上传库必须属于当前用户且达到 `READY`。
- 会话创建后知识库范围不可修改，范围指纹会同时校验 Redis 热快照和 MySQL 数据。
- ASR 分段和 OCR 观测按照重叠时间窗融合；同一时间段可同时包含语音与视觉证据。
- `timeline.md`、chunk、版本和 ES 文档都通过唯一键或 UPSERT 保证重试安全。

检索降级规则：

- BM25 失败时使用 kNN。
- Embedding/kNN 失败时使用 BM25。
- BGE Rerank 失败或返回无效索引时使用 RRF Top 6。
- 两路召回都失败才返回服务不可用。
- 指标模式为 `FULL_HYBRID`、`BM25_ONLY`、`KNN_ONLY` 或 `RRF_ONLY`。

## Planner–Executor–Critic

普通问答：

- 规则 Planner。
- 最多 2 次工具调用、1 次重规划、20 秒预算。

深度思考：

- 结构化 LLM Planner 与 Critic。
- 最多 6 次工具调用、2 次重规划、60 秒预算。
- Planner JSON 无效或超时时降级到规则 Planner，不进入无边界 Agent 循环。

允许的 Executor 工具只有：

- 全范围混合检索。
- 视频时间轴检索。
- 用户文档检索。
- 当前会话上下文读取。

Critic 检查子问题覆盖、证据存在、引用完整性和明显冲突，只允许 Query Rewrite 或有界 Replan。`chat_generation`、`agent_execution` 和 `agent_step` 保存计划版本、工具、耗时、状态及 Evidence ID，不保存隐藏思维链。

流式接口保留 `delta/done/error` 事件，并增加：

```json
{"event":"workflow","phase":"...","stepId":"...","status":"...","message":"..."}
```

## 主要 API

所有业务接口均需要先通过 `/api/auth/register` 或 `/api/auth/login` 获取访问令牌。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/videos/upload` | 上传视频 |
| POST | `/api/videos/multipart/init` | 初始化分片上传 |
| POST | `/api/tasks/analyze` | 事务投递视频分析 |
| GET | `/api/tasks/{taskId}` | 查询任务状态 |
| GET | `/api/tasks/{taskId}/result` | 查询分析结果 |
| POST | `/api/tasks/{taskId}/cancel` | 协作式取消 |
| POST | `/api/knowledge-bases` | 创建用户知识库 |
| POST | `/api/knowledge-bases/{id}/documents` | 上传文档并启动 MinerU 解析 |
| DELETE | `/api/knowledge-bases/{id}` | 返回 HTTP 202 的异步物理删除任务 |
| POST | `/api/chat/session` | 创建固定知识库范围会话 |
| POST | `/api/chat/message` | 非流式问答 |
| POST/GET | `/api/chat/message/stream` | SSE 流式问答与 workflow 事件 |
| DELETE | `/api/videos/{videoId}` | 返回 HTTP 202 的异步物理删除任务 |

## 测试与真实验收

后端：

```powershell
Set-Location backend\videomind-server
mvn test
```

前端：

```powershell
Set-Location frontend\videomind-web
npm test
npm run build
```

Compose 与代码差异：

```powershell
Set-Location E:\VideoMind
docker compose config
git diff --check
```

本地 E2E 脚本只接受 loopback URL，不会输出凭据值：

```powershell
# 静态校验，不读取凭据、不改服务
.\ops\e2e-local.ps1 -ValidateOnly

# 生成并验证 40 秒视频和短 PDF，不调用外部 API
.\ops\e2e-local.ps1 -FixtureOnly

# 生成并验证带 120/240 秒边界语音的 250 秒素材，不调用外部 API
.\ops\e2e-local.ps1 -FixtureOnly -LongAsr

# 检查凭据是否存在、内存、工具和九项本地服务；不调用外部 API
.\ops\e2e-local.ps1 -PreflightOnly

# 完整真实验收，同时注入 Broker 重启和 Cache Redis 故障
.\ops\e2e-local.ps1 -ExerciseBrokerRestart

# 长视频真实验收：三片 ASR，在第二片保存 TaskId 后重启 Broker
.\ops\e2e-local.ps1 -LongAsr -ExerciseBrokerRestart -TimeoutSeconds 3600
```

完整 E2E 会验证：

- 腾讯云返回时间戳 ASR 分段。
- PaddleOCR 命中关键帧文字，时间轴同时包含语音与视觉证据。
- PDF 经 MinerU 解析，ES 同时存在视频和文档 chunk。
- 联合回答至少引用一条视频时间段证据和一条用户文档证据。
- 重复分析复用原任务，不产生重复时间轴。
- Broker 重启后任务恢复，Cache Redis 停止时聊天回退 MySQL，恢复后热快照回填。
- 异步删除完成后，测试视频、知识库、ES 文档和解析记录全部清理。
- `-LongAsr` 额外验证至少三个分片、120/240 秒边界、分片续轮询、重叠去重和分片状态物理清理。

## 故障排查

- `FailedOperation.UserHasNoAmount`：当前 SecretId 所属腾讯云主账号没有可用于录音文件识别的资源包或后付费额度。
- MinerU 首次解析较慢：查看 `docker compose logs -f mineru`，首次会下载 PDF-Extract-Kit 模型并持久化缓存。
- OCR 健康但无结果：检查 `http://localhost:8868/health`、关键帧阈值和 `OCR_MAX_FRAMES`。
- ES 首次索引超时：Docker Desktop bind mount 冷启动可能较慢，可调整 `ELASTICSEARCH_READ_TIMEOUT_MILLIS`。
- 8080 或 5173 被占用：`start.ps1` 会报告占用进程，不会终止未知进程。
- Cache Redis 故障：聊天应继续从 MySQL 重建；恢复后下一轮请求回填热快照。
- ASR 分片仍超过 5 MiB：减小 `TENCENT_ASR_CHUNK_SECONDS`，并确认 FFmpeg 输出为 16 kHz、单声道、16-bit PCM；不要改回公网 URL 模式。

真实凭据、`.env`、`runtime/`、模型缓存和 E2E 产物都不得提交到 Git。
