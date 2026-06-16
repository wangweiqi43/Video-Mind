# VideoMind API 接口文档

## 1. 文档说明

本文档描述 VideoMind 后端 REST API 的调用方式、请求参数、响应结构和典型业务流程。

- 后端服务地址：`http://localhost:8080`
- API Base URL：`http://localhost:8080/api`
- 前端开发代理：Vite 将 `/api` 代理到 `http://localhost:8080`
- 当前用户上下文：项目尚未接入真实登录鉴权，后端统一使用 Mock 用户 `userId=1`
- 时间格式：`yyyy-MM-dd'T'HH:mm:ss`，例如 `2026-05-31T23:08:19`

## 2. 通用约定

### 2.1 统一响应结构

除 SSE 流式接口外，所有接口均返回统一 JSON 结构：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| code | integer | 是 | 业务状态码。`0` 表示成功，非 `0` 表示失败 |
| message | string | 是 | 响应消息或错误原因 |
| data | object/array/null | 否 | 业务数据 |

失败响应示例：

```json
{
  "code": 400,
  "message": "上传文件不能为空",
  "data": null
}
```

### 2.2 常见错误码

| code | 说明 |
|---:|---|
| 0 | 请求成功 |
| 400 | 请求参数错误、状态不允许、文件校验失败 |
| 404 | 资源不存在或无权访问 |
| 409 | 资源冲突，例如视频重复上传 |
| 429 | 请求过于频繁或同一资源正在处理中 |
| 500 | 服务端异常、第三方服务异常、中间件异常 |

### 2.3 常用枚举

| 枚举 | 可选值 | 说明 |
|---|---|---|
| UploadStatus | `UPLOADING`, `UPLOADED`, `FAILED` | 视频文件上传状态 |
| UploadSessionStatus | `UPLOADING`, `COMPLETED`, `FAILED` | 分片上传会话状态 |
| TaskStatus | `PENDING`, `PROCESSING`, `SUCCESS`, `FAILED` | 视频解析任务状态 |
| KnowledgeChunkType | `TRANSCRIPTION`, `SUMMARY` | 知识库片段类型 |
| MessageRole | `USER`, `ASSISTANT`, `SYSTEM` | 聊天消息角色 |

## 3. 视频模块

### 3.1 上传视频

- Method：`POST`
- Path：`/api/videos/upload`
- Content-Type：`multipart/form-data`
- 说明：上传单个视频文件。后端会计算 MD5，检查重复文件，写入 MinIO，并在 MySQL 保存视频元数据。

请求参数：

| 参数 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---:|---|
| file | File | form-data | 是 | 视频文件。支持 `mp4`, `mov`, `avi`, `mkv`, `webm`, `flv`, `wmv`, `m4v` |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| videoId | number | 视频 ID |
| filename | string | 原始文件名 |
| fileMd5 | string | 文件 MD5 |
| fileSize | number | 文件大小，单位 byte |
| bucket | string | MinIO bucket |
| objectKey | string | MinIO object key |
| implemented | boolean | 当前功能是否已实现 |
| message | string | 提示信息 |

示例：

```bash
curl -F "file=@demo.mp4" http://localhost:8080/api/videos/upload
```

### 3.2 检查视频 MD5

- Method：`GET`
- Path：`/api/videos/check-md5`
- 说明：上传前检查当前用户下是否已存在相同 MD5 的视频。

请求参数：

| 参数 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---:|---|
| fileMd5 | string | query | 是 | 文件 MD5 |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| exists | boolean | 是否已存在 |
| videoId | number/null | 已存在的视频 ID |
| filename | string/null | 已存在的视频文件名 |
| fileMd5 | string | 请求传入的 MD5 |
| message | string | 提示信息 |

示例：

```bash
curl "http://localhost:8080/api/videos/check-md5?fileMd5=ed0fe5bfcea9976c949fd293d7007afd"
```

### 3.3 查询视频列表

- Method：`GET`
- Path：`/api/videos/list`
- 说明：查询当前用户已上传的视频列表，按创建时间倒序返回。

响应 `data[]`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | number | 视频 ID |
| userId | number | 用户 ID |
| originalFilename | string | 原始文件名 |
| fileMd5 | string | 文件 MD5 |
| fileSize | number | 文件大小，单位 byte |
| contentType | string | 文件 MIME 类型 |
| minioBucket | string | MinIO bucket |
| minioObjectKey | string | MinIO object key |
| uploadStatus | string | 上传状态，见 `UploadStatus` |
| durationSeconds | number/null | 视频时长，当前可能为空 |
| createdTime | string | 创建时间 |
| updatedTime | string | 更新时间 |
| deleted | integer | 逻辑删除标记 |

示例：

```bash
curl http://localhost:8080/api/videos/list
```

### 3.4 查询视频详情

- Method：`GET`
- Path：`/api/videos/{videoId}`
- 说明：查询指定视频详情。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| videoId | number | 是 | 视频 ID |

响应 `data`：同“查询视频列表”的单个视频对象。

示例：

```bash
curl http://localhost:8080/api/videos/12
```

### 3.5 删除视频

- Method：`DELETE`
- Path：`/api/videos/{videoId}`
- 说明：删除指定视频，并清理关联任务、转录、摘要、知识库向量、分片上传会话和 MinIO 对象。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| videoId | number | 是 | 视频 ID |

响应 `data`：`null`

示例：

```bash
curl -X DELETE http://localhost:8080/api/videos/12
```

## 4. 分片上传模块

### 4.1 初始化分片上传

- Method：`POST`
- Path：`/api/videos/multipart/init`
- Content-Type：`application/json`
- 说明：创建或恢复分片上传会话。如果同一用户下存在相同 MD5 的上传中会话，则返回已有 `uploadId` 和已上传分片。

请求体：

```json
{
  "filename": "demo.mp4",
  "fileMd5": "ed0fe5bfcea9976c949fd293d7007afd",
  "fileSize": 12842060,
  "contentType": "video/mp4",
  "totalParts": 3,
  "chunkSize": 5242880
}
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| filename | string | 是 | 原始文件名 |
| fileMd5 | string | 是 | 完整文件 MD5 |
| fileSize | number | 是 | 文件大小，单位 byte |
| contentType | string | 否 | 文件 MIME 类型 |
| totalParts | integer | 是 | 总分片数，最小值 `1` |
| chunkSize | number | 是 | 单个分片大小，单位 byte |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| uploadId | string | 分片上传会话 ID |
| uploadedParts | integer[] | 已上传分片序号，从 `1` 开始 |
| status | string | 分片上传会话状态，见 `UploadSessionStatus` |

示例：

```bash
curl -X POST http://localhost:8080/api/videos/multipart/init \
  -H "Content-Type: application/json" \
  -d "{\"filename\":\"demo.mp4\",\"fileMd5\":\"ed0fe5bfcea9976c949fd293d7007afd\",\"fileSize\":12842060,\"contentType\":\"video/mp4\",\"totalParts\":3,\"chunkSize\":5242880}"
```

### 4.2 上传分片

- Method：`POST`
- Path：`/api/videos/multipart/{uploadId}/chunk`
- Content-Type：`multipart/form-data`
- 说明：上传指定序号的分片。分片序号从 `1` 开始。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| uploadId | string | 是 | 分片上传会话 ID |

请求参数：

| 参数 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---:|---|
| partNumber | integer | query | 是 | 分片序号，从 `1` 开始 |
| file | File | form-data | 是 | 当前分片文件 |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| uploadId | string | 分片上传会话 ID |
| partNumber | integer | 本次上传的分片序号 |
| uploaded | boolean | 是否上传成功 |
| uploadedPartsCount | integer | 当前已上传分片数量 |

示例：

```bash
curl -F "file=@part-1.bin" "http://localhost:8080/api/videos/multipart/{uploadId}/chunk?partNumber=1"
```

### 4.3 查询分片上传状态

- Method：`GET`
- Path：`/api/videos/multipart/{uploadId}/status`
- 说明：查询指定分片上传会话的进度。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| uploadId | string | 是 | 分片上传会话 ID |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| uploadId | string | 分片上传会话 ID |
| totalParts | integer | 总分片数 |
| uploadedPartsCount | integer | 已上传分片数量 |
| uploadedParts | integer[] | 已上传分片序号 |
| status | string | 分片上传会话状态 |
| videoId | number/null | 上传完成后生成的视频 ID |

示例：

```bash
curl http://localhost:8080/api/videos/multipart/{uploadId}/status
```

### 4.4 完成分片上传

- Method：`POST`
- Path：`/api/videos/multipart/{uploadId}/complete`
- 说明：合并所有分片，校验完整文件 MD5，上传到 MinIO，并写入视频元数据。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| uploadId | string | 是 | 分片上传会话 ID |

响应 `data`：同“上传视频”的 `VideoUploadResponse`。

示例：

```bash
curl -X POST http://localhost:8080/api/videos/multipart/{uploadId}/complete
```

## 5. 视频解析任务模块

### 5.1 创建解析任务

- Method：`POST`
- Path：`/api/tasks/analyze`
- Content-Type：`application/json`
- 说明：为指定视频创建异步解析任务。后端发送 RocketMQ 消息，由消费者执行 FFmpeg 音频提取、ASR、摘要生成，并写入转录和摘要结果。

请求体：

```json
{
  "videoId": 12,
  "autoVectorize": true
}
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| videoId | number | 是 | 视频 ID |
| autoVectorize | boolean | 否 | 解析成功后是否自动向量化入库，默认 `false` |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| taskId | number | 解析任务 ID |
| status | string | 任务状态，见 `TaskStatus` |
| reused | boolean | 是否复用了已有任务或已有成功结果 |

示例：

```bash
curl -X POST http://localhost:8080/api/tasks/analyze \
  -H "Content-Type: application/json" \
  -d "{\"videoId\":12,\"autoVectorize\":true}"
```

### 5.2 查询任务详情

- Method：`GET`
- Path：`/api/tasks/{taskId}`
- 说明：查询解析任务状态和元数据，前端可用该接口轮询任务进度。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| taskId | number | 是 | 解析任务 ID |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | number | 任务 ID |
| userId | number | 用户 ID |
| videoId | number | 视频 ID |
| videoMd5 | string | 视频 MD5 |
| taskStatus | string | 任务状态 |
| autoVectorize | boolean | 是否自动向量化 |
| errorMessage | string/null | 失败原因 |
| startedTime | string/null | 开始处理时间 |
| finishedTime | string/null | 完成时间 |
| createdTime | string | 创建时间 |
| updatedTime | string | 更新时间 |
| deleted | integer | 逻辑删除标记 |

示例：

```bash
curl http://localhost:8080/api/tasks/26
```

### 5.3 查询任务结果

- Method：`GET`
- Path：`/api/tasks/{taskId}/result`
- 说明：查询解析结果，包括 ASR 转录文本和摘要内容。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| taskId | number | 是 | 解析任务 ID |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| taskId | number | 任务 ID |
| videoId | number | 视频 ID |
| status | string | 任务状态 |
| transcriptionText | string/null | ASR 转录文本 |
| summaryText | string/null | 摘要文本 |
| summaryJson | string/null | 摘要 JSON 字符串 |

示例：

```bash
curl http://localhost:8080/api/tasks/26/result
```

### 5.4 查询视频最近一次成功任务

- Method：`GET`
- Path：`/api/tasks/video/{videoId}/latest-success`
- 说明：查询指定视频最近一次解析成功的任务。前端切换视频时可用该接口加载历史结果。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| videoId | number | 是 | 视频 ID |

响应 `data`：同“查询任务详情”的任务对象；如果没有成功任务，可能返回 `null`。

示例：

```bash
curl http://localhost:8080/api/tasks/video/12/latest-success
```

## 6. 知识库模块

### 6.1 向量化任务结果

- Method：`POST`
- Path：`/api/knowledge/vectorize/{taskId}`
- 说明：将指定成功任务的转录和摘要切片，调用 Embedding 模型生成向量，并写入 Redis Stack / RediSearch。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| taskId | number | 是 | 解析任务 ID |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| taskId | number | 任务 ID |
| vectorized | boolean | 是否已完成向量化 |
| status | string | 向量化状态，例如 `VECTORIZED`, `NOT_VECTORIZED`, `VECTORIZE_FAILED`, `VECTOR_INDEX_MISSING` |
| message | string | 状态说明 |
| chunkCount | integer/null | 知识片段数量 |
| updatedTime | string/null | 状态更新时间 |

示例：

```bash
curl -X POST http://localhost:8080/api/knowledge/vectorize/26
```

### 6.2 查询向量化状态

- Method：`GET`
- Path：`/api/knowledge/status/{taskId}`
- 说明：查询指定任务是否已加入知识库。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| taskId | number | 是 | 解析任务 ID |

响应 `data`：同“向量化任务结果”的 `KnowledgeStatusResponse`。

示例：

```bash
curl http://localhost:8080/api/knowledge/status/26
```

## 7. 智能问答模块

### 7.1 创建聊天会话

- Method：`POST`
- Path：`/api/chat/session`
- 说明：创建一个新的聊天会话。

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| sessionId | number | 会话 ID |
| title | string | 会话标题，默认 `新会话` |

示例：

```bash
curl -X POST http://localhost:8080/api/chat/session
```

### 7.2 查询聊天会话列表

- Method：`GET`
- Path：`/api/chat/session/list`
- 说明：查询当前用户的聊天会话列表，按更新时间倒序返回。

响应 `data[]`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | number | 会话 ID |
| userId | number | 用户 ID |
| title | string | 会话标题 |
| memorySummary | string/null | 长会话历史摘要记忆 |
| createdTime | string | 创建时间 |
| updatedTime | string | 更新时间 |
| deleted | integer | 逻辑删除标记 |

示例：

```bash
curl http://localhost:8080/api/chat/session/list
```

### 7.3 发送聊天消息

- Method：`POST`
- Path：`/api/chat/message`
- Content-Type：`application/json`
- 说明：发送非流式聊天消息。后端会生成问题 Embedding，检索当前视频知识片段，并调用 Chat 模型生成回答。

请求体：

```json
{
  "sessionId": 16,
  "videoId": 12,
  "question": "这个视频主要讲了什么？"
}
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| sessionId | number | 是 | 会话 ID |
| videoId | number | 是 | 当前选中的视频 ID |
| question | string | 是 | 用户问题 |

响应 `data`：

| 字段 | 类型 | 说明 |
|---|---|---|
| messageId | number | 助手消息 ID |
| answer | string | 助手回答 |
| references | object[] | RAG 引用片段列表 |
| referencesJson | string | 引用片段 JSON 字符串 |
| createdTime | string | 助手消息创建时间 |

`references[]` 字段：

| 字段 | 类型 | 说明 |
|---|---|---|
| videoId | number | 引用所属视频 ID |
| taskId | number | 引用所属任务 ID |
| chunkType | string | 片段类型，见 `KnowledgeChunkType` |
| chunkIndex | integer | 片段序号 |
| chunkText | string | 片段文本 |
| score | number | 相似度分数 |

示例：

```bash
curl -X POST http://localhost:8080/api/chat/message \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":16,\"videoId\":12,\"question\":\"这个视频主要讲了什么？\"}"
```

### 7.4 流式发送聊天消息

- Method：`GET`
- Path：`/api/chat/message/stream`
- Response Content-Type：`text/event-stream`
- 说明：通过 SSE 流式返回助手回答。前端当前使用浏览器原生 `EventSource` 调用该接口。

请求参数：

| 参数 | 类型 | 位置 | 必填 | 说明 |
|---|---|---|---:|---|
| sessionId | number | query | 是 | 会话 ID |
| videoId | number | query | 是 | 当前选中的视频 ID |
| question | string | query | 是 | 用户问题 |

SSE 事件：

| 事件名 | data 类型 | 说明 |
|---|---|---|
| delta | string | 单次增量文本 |
| done | object | 完整 `ChatMessageResponse`，表示回答完成 |
| error | string | 错误信息 |

示例：

```js
const params = new URLSearchParams({
  sessionId: '16',
  videoId: '12',
  question: '这个视频主要讲了什么？'
})

const source = new EventSource(`/api/chat/message/stream?${params.toString()}`)
source.addEventListener('delta', (event) => {
  console.log(event.data)
})
source.addEventListener('done', (event) => {
  console.log(JSON.parse(event.data))
  source.close()
})
source.addEventListener('error', (event) => {
  console.error(event.data)
  source.close()
})
```

后端同时提供 `POST /api/chat/message/stream`，请求体与“发送聊天消息”一致，响应同样为 SSE。由于浏览器原生 `EventSource` 不支持 POST，前端当前默认使用 GET 版本。

### 7.5 查询会话消息

- Method：`GET`
- Path：`/api/chat/session/{sessionId}/messages`
- 说明：查询指定会话下的历史消息，按创建时间升序返回。

路径参数：

| 参数 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| sessionId | number | 是 | 会话 ID |

响应 `data[]`：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | number | 消息 ID |
| sessionId | number | 会话 ID |
| userId | number | 用户 ID |
| role | string | 消息角色，见 `MessageRole` |
| content | string | 消息内容 |
| referencesJson | string/null | 助手消息引用片段 JSON |
| createdTime | string | 创建时间 |
| deleted | integer | 逻辑删除标记 |

示例：

```bash
curl http://localhost:8080/api/chat/session/16/messages
```

## 8. 典型业务流程

### 8.1 普通上传到视频问答流程

1. 前端读取视频文件并计算 MD5。
2. 调用 `GET /api/videos/check-md5` 检查是否重复上传。
3. 若不存在，调用 `POST /api/videos/upload` 上传视频。
4. 调用 `POST /api/tasks/analyze` 创建异步解析任务。
5. 前端轮询 `GET /api/tasks/{taskId}`，直到任务状态为 `SUCCESS` 或 `FAILED`。
6. 若任务成功，调用 `GET /api/tasks/{taskId}/result` 获取转录和摘要。
7. 调用 `POST /api/knowledge/vectorize/{taskId}` 将解析结果加入知识库。
8. 调用 `POST /api/chat/session` 创建聊天会话。
9. 调用 `GET /api/chat/message/stream` 基于当前视频进行 RAG 流式问答。

### 8.2 分片上传流程

1. 前端计算完整文件 MD5，并按固定大小切片。
2. 调用 `POST /api/videos/multipart/init` 初始化分片上传会话。
3. 根据返回的 `uploadedParts` 跳过已上传分片。
4. 对未上传分片逐个调用 `POST /api/videos/multipart/{uploadId}/chunk?partNumber={n}`。
5. 可调用 `GET /api/videos/multipart/{uploadId}/status` 查询上传进度。
6. 所有分片上传后，调用 `POST /api/videos/multipart/{uploadId}/complete` 合并文件并写入视频元数据。

### 8.3 切换视频加载历史解析结果流程

1. 前端调用 `GET /api/videos/list` 获取视频列表。
2. 用户选择某个视频。
3. 前端调用 `GET /api/tasks/video/{videoId}/latest-success` 查询该视频最近一次成功任务。
4. 如果存在成功任务，调用 `GET /api/tasks/{taskId}/result` 加载转录和摘要。
5. 调用 `GET /api/knowledge/status/{taskId}` 加载知识库状态。

## 9. 接口安全与开发注意事项

- 当前项目使用 `MockUserContext` 固定用户 ID，生产环境应替换为 JWT、Session 或 OAuth2 鉴权。
- 视频文件存储在 MinIO，数据库只保存元数据和对象存储 key。
- 视频解析是耗时任务，调用 `POST /api/tasks/analyze` 后应通过轮询查询任务状态，不应阻塞等待解析完成。
- 流式聊天 GET 接口会将 `question` 放入 URL query，生产环境建议改为 POST 流式读取或使用短期 token 避免敏感内容出现在日志中。
- 当前知识库向量化依赖 Redis Stack / RediSearch；如果 RediSearch KNN 查询失败，后端会降级为 Redis hash 扫描检索，数据量增大后需要优化。
