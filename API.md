# VideoMind 后端 API

本文档以当前后端 Controller 与 DTO 为准。服务默认地址为 `http://localhost:8080`，业务接口前缀为 `/api`。

## 1. 公共约定

### 1.1 鉴权

除 `/api/auth/**` 和 `/actuator/health/**` 外，所有接口都需要登录。登录或注册成功后，服务同时返回访问令牌并写入 HttpOnly Cookie：

- `VM_ACCESS`：访问令牌；
- `VM_REFRESH`：刷新令牌；
- 非浏览器客户端也可发送 `Authorization: Bearer <accessToken>`。

### 1.2 普通响应

除文件流和 SSE 外，响应统一包装为：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

`code=0` 表示成功。常见业务错误码为 `400`（参数或文件错误）、`404`（不存在或无权访问）、`409`（状态冲突）、`429`（限流）、`500`（内部或外部依赖失败）、`503`（检索召回均不可用）。业务异常由响应体 `code` 表示；异步删除成功使用 HTTP 202。

### 1.3 关键状态

- 视频业务任务：`PENDING / PROCESSING / RETRYING / SUCCESS / FAILED`。历史数据可能出现 `CANCEL_REQUESTED / CANCELLED`，当前不再提供视频任务取消入口。
- 处理任务：`PENDING / PROCESSING / RETRY_WAIT / SUCCESS / FAILED / DEAD`，历史取消状态同上。
- 知识库：`EMPTY / UPLOADING / PROCESSING / READY / FAILED / DELETING`。
- 知识库类型：`VIDEO / USER`。
- 回答生成：`RUNNING / CANCEL_REQUESTED / CANCELLED / SUCCESS / FAILED`。

## 2. 认证

### `POST /api/auth/register`

注册并登录。

```json
{"username":"demo","password":"password123"}
```

用户名不能为空，密码长度为 8–128。响应 `data`：

```json
{
  "accessToken":"...",
  "expiresIn":900,
  "subject":"用户公开 ID",
  "username":"demo"
}
```

### `POST /api/auth/login`

请求与响应同注册。

### `POST /api/auth/refresh`

读取 `VM_REFRESH` Cookie，刷新令牌并重写访问/刷新 Cookie。

### `POST /api/auth/logout`

撤销刷新令牌并清空两个 Cookie。

### `GET /api/auth/me`

返回当前用户的 `subject` 与 `username`。

## 3. 视频

### `POST /api/videos/upload`

`multipart/form-data`，字段 `file`。上传视频到 MinIO，并保存视频元数据。`data` 主要字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| videoId | long | 视频 ID |
| filename | string | 原始文件名 |
| fileMd5 | string | 服务端 MD5 |
| fileSize | long | 字节数 |
| bucket / objectKey | string | MinIO 位置 |
| duplicate | boolean | 是否复用已有视频 |
| serverMd5CostMs / serverStorageCostMs / serverTotalCostMs | long | 服务端耗时 |

### `GET /api/videos/check-md5?fileMd5={md5}`

检查当前用户是否已上传相同 MD5，返回 `exists、videoId、filename、fileMd5、message`。

### `POST /api/videos/multipart/init`

初始化或恢复分片上传。

```json
{
  "filename":"demo.mp4",
  "fileMd5":"完整文件 MD5",
  "fileSize":12842060,
  "contentType":"video/mp4",
  "totalParts":3,
  "chunkSize":5242880
}
```

返回 `uploadId、uploadedParts、status、video`；命中已完成文件时 `video` 可直接返回视频信息。

### `POST /api/videos/multipart/{uploadId}/chunk`

`multipart/form-data`：

- query `partNumber`：从 1 开始；
- query `chunkMd5`：本分片 MD5；
- form `file`：分片内容。

返回 `uploadId、partNumber、uploaded、skipped、uploadedPartsCount、chunkMd5`。

### `GET /api/videos/multipart/{uploadId}/status`

返回 `uploadId、totalParts、uploadedPartsCount、uploadedParts、status、videoId`。

### `POST /api/videos/multipart/{uploadId}/complete`

合并分片、校验完整 MD5、写入 MinIO 和 MySQL，返回与普通上传相同的 `VideoUploadResponse`。

### `GET /api/videos/list`

返回当前用户的视频列表。

### `GET /api/videos/{videoId}`

返回视频详情，包括文件元数据、时长、`transcriptVersion、summaryStatus、summaryVersion` 等。

### `GET /api/videos/{videoId}/transcription`

读取最新共享转录：

```json
{
  "videoId":12,
  "transcriptVersion":3,
  "status":"READY",
  "language":"zh",
  "transcriptionText":"...",
  "updatedTime":"2026-08-15T12:00:00"
}
```

尚无转录时仍成功返回，`status=UNAVAILABLE`。

### `GET /api/videos/{videoId}/stream`

直接流式读取视频对象，响应 Content-Type 为视频原 MIME 类型，不使用 `ApiResponse` 包装。

### `DELETE /api/videos/{videoId}`

返回 HTTP 202，创建可恢复的物理删除任务：

```json
{"code":0,"message":"success","data":{"eventId":"...","taskId":91,"status":"PENDING"}}
```

删除任务会幂等清理 MinIO、Elasticsearch、MySQL 和相关缓存。当前不提供取消接口。

## 4. 视频分析任务

### `POST /api/tasks/analyze`

事务投递视频分析任务。

```json
{"videoId":12}
```

返回：

```json
{"code":0,"message":"success","data":{"taskId":26,"status":"PENDING","reused":false}}
```

内部通过 RocketMQ 事务消息、Inbox、CAS Lease、Checkpoint 和唯一业务指纹保证幂等；ASR、OCR、时间轴、摘要和 ES 入库均在后台执行。

### `GET /api/tasks/{taskId}`

视频分析任务返回 `TaskRecord`；知识库/视频删除任务返回 `DeletionTaskResponse`。两种结构都包含可轮询状态。

### `GET /api/tasks/{taskId}/result`

返回 `taskId、videoId、status、transcriptionText、summaryText、summaryJson`。未成功时正文可能为空。

### `GET /api/tasks/video/{videoId}/latest-success`

返回当前用户该视频最近一次成功任务；不存在时 `data=null`。

> 已删除：`POST /api/tasks/{taskId}/cancel`。该路径返回 404。

## 5. 本地知识库

### `POST /api/knowledge-bases`

创建用户文档知识库。

```json
{"name":"项目资料"}
```

名称非空且不超过 255 字符。返回：

```json
{
  "id":31,
  "type":"USER",
  "videoId":null,
  "name":"项目资料",
  "status":"EMPTY",
  "documentCount":0,
  "createdTime":"...",
  "updatedTime":"..."
}
```

### `GET /api/knowledge-bases`

列出当前用户的视频系统知识库和用户文档库。

### `GET /api/knowledge-bases/{knowledgeBaseId}`

查询知识库详情并校验归属。

### `POST /api/knowledge-bases/{knowledgeBaseId}/documents`

`multipart/form-data`，字段 `file`。原件写入 MinIO，随后事务投递 MinerU 本机解析和 ES 入库任务。返回：

```json
{
  "documentId":41,
  "versionId":42,
  "title":"guide.pdf",
  "sha256":"...",
  "status":"PROCESSING",
  "processingStage":"QUEUED",
  "duplicated":false,
  "eventId":"...",
  "taskId":92,
  "reusedTask":false
}
```

### `DELETE /api/knowledge-bases/{knowledgeBaseId}`

返回 HTTP 202 和 `DeletionTaskResponse`。只能独立删除 `USER` 知识库；视频系统库随视频删除。当前不提供删除任务取消接口。

> 已删除：旧 `/api/knowledge/vectorize/**` 与 `/api/knowledge/status/**`，均返回 404。知识入库已并入异步处理链路，向量存储为 Elasticsearch。

## 6. 会话与问答

### `POST /api/chat/session`

创建固定知识库范围会话。

```json
{"videoId":12,"knowledgeBaseIds":[31,32]}
```

- `knowledgeBaseIds` 最多 20 个；只传用户文档库即可；
- 后端自动把当前视频系统知识库放在范围第一项；
- 用户文档库必须归当前用户且为 `READY`；
- 创建后范围不可修改。

返回 `sessionId、videoId、title、knowledgeBaseIds`，其中最后一个字段是包含视频系统库的最终固定范围。

### `GET /api/chat/session/list?videoId={videoId}`

返回该视频的会话列表：`id、videoId、title、lastMessagePreview、knowledgeBaseIds、createdTime、updatedTime`。

### `GET /api/chat/session/{sessionId}/messages?videoId={videoId}`

返回会话历史消息，按时间升序排列。

### `POST /api/chat/message`

非流式问答：

```json
{
  "sessionId":16,
  "videoId":12,
  "question":"结合视频和文档说明结论",
  "answerScope":"KNOWLEDGE_EXTENDED",
  "webSearchEnabled":false,
  "deepThinkingEnabled":false
}
```

- `answerScope`：`KNOWLEDGE_ONLY` 或 `KNOWLEDGE_EXTENDED`；
- `deepThinkingEnabled=false` 使用普通有界工作流，`true` 使用深度 Planner/Critic；
- `webSearchEnabled` 为兼容字段；当前 Executor 不提供联网搜索工具。

返回 `messageId、answer、references、referencesJson、createdTime`。

`references[]` 是可审计 Evidence，主要字段包括：

- `evidenceId、knowledgeBaseId、documentId、documentVersionId`；
- `chunkType、chunkIndex、chunkText、score、sourceType`；
- 视频 Evidence 额外包含 `videoId、taskId、startSeconds、endSeconds`；
- 外部来源兼容字段为 `title、domain、publishedAt、url`。

### `POST /api/chat/message/stream`

请求体与非流式接口相同，响应为 `text/event-stream`。

### `GET /api/chat/message/stream`

供原生 `EventSource` 使用。query 参数：

- 必填：`sessionId、videoId、question`；
- 可选：`answerScope`（默认 `KNOWLEDGE_EXTENDED`）、`webSearchEnabled`（默认 `false`）、`deepThinkingEnabled`（默认 `false`）。

SSE 事件顺序与数据：

| event | data | 说明 |
| --- | --- | --- |
| `generation` | `{"generationId":61,"status":"RUNNING"}` | 首个生成标识；客户端用它停止回答 |
| `workflow` | `{"phase":"PLAN","stepId":"...","status":"STARTED","message":"..."}` | 可展示的工作流进度，不含隐藏思维链 |
| `delta` | `{"delta":"增量文本"}` | 模型文本增量 |
| `done` | `ChatMessageResponse` | 成功终态，随后连接完成 |
| `cancelled` | `{"generationId":61,"status":"CANCELLED"}` | 取消终态，随后连接完成 |
| `error` | JSON 字符串 | 失败终态，随后连接完成 |

`workflow` 可能出现多次，`delta` 可能出现零到多次。终态事件只会是 `done / cancelled / error` 之一。

### `POST /api/chat/generations/{generationId}/cancel`

停止当前用户的一次回答生成。`generationId` 来自流式接口的 `generation` 事件。

响应示例：

```json
{"code":0,"message":"success","data":{"generationId":61,"status":"CANCEL_REQUESTED"}}
```

语义：

1. 只允许取消当前用户自己的生成，越权按 404 处理；
2. `RUNNING` 通过 CAS 转为 `CANCEL_REQUESTED`，后端通知当前工作流并关闭模型响应流；
3. 流式任务收口为 `CANCELLED`，已接收部分文本仅保存到审计表，不写入助手消息，也不计入已完成会话轮次；
4. 重复取消是幂等的；若已 `SUCCESS / FAILED / CANCELLED`，直接返回当前终态；
5. 服务重启时，遗留 `CANCEL_REQUESTED` 自动收口为 `CANCELLED`，遗留 `RUNNING` 标记为 `FAILED/SERVER_RESTARTED`。

## 7. 健康检查

### `GET /actuator/health`

无需登录，返回整体健康状态；不会暴露凭据或内部异常栈。

## 8. 推荐调用链路

1. 注册或登录；
2. 上传视频并调用 `/api/tasks/analyze`；
3. 轮询 `/api/tasks/{taskId}` 直到 `SUCCESS`，再读取结果；
4. 可创建用户知识库并上传文档，轮询上传响应中的处理 `taskId`；
5. 待知识库 `READY` 后，以视频和用户知识库创建固定范围会话；
6. 调用 SSE 问答，保存首个 `generationId`；
7. 需要停止时调用回答取消接口；正常完成时读取 `done.references`；
8. 删除视频或用户知识库时接收 HTTP 202，并轮询返回的删除 `taskId`。

## 9. 已移除接口

以下旧接口不再映射，预期返回 404：

- MindAgent 绑定、同步、OAuth、Webhook、报告和演示文稿接口；
- `POST /api/knowledge/vectorize/{taskId}`；
- `GET /api/knowledge/status/{taskId}`；
- `POST /api/tasks/{taskId}/cancel`。
