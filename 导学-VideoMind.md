# VideoMind 后端开发岗位导学

> 面向目标：能够脱离项目私有名词，讲清大文件上传、异步任务可靠性、多模态处理、混合检索与受约束智能体工作流，并能主动说明一致性边界、失败路径和验证方法。

## 1. 前置知识

| 知识点 | 为何需要 | 项目位置 | 高频度 |
|---|---|---|---|
| Spring Boot、事务传播与异常回滚 | 任务创建、事件落库和业务记录依赖本地事务；还要判断外部存储操作为什么不能被数据库事务覆盖 | `backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/LocalTaskTransactionServiceImpl.java` | ★★★★★ |
| MySQL 唯一约束、乐观锁、生成列 | 去重不能只靠“先查后写”；任务抢占与状态推进也依赖条件更新 | `backend/videomind-server/src/main/resources/db/migration/V21__unique_video_file_user_md5.sql` | ★★★★★ |
| RocketMQ 事务消息与至少一次投递 | 理解半消息、本地事务、事务回查，以及消费重复和重试为什么必然存在 | `backend/videomind-server/src/main/java/com/videomind/module/task/mq/RocketMqTransactionalTaskMessageProducer.java` | ★★★★★ |
| Redis Bitmap 与 Redisson 分布式锁 | 分片上传需要低成本记录完成状态，并防止同一分片并发覆盖 | `backend/videomind-server/src/main/java/com/videomind/module/video/service/impl/MultipartUploadServiceImpl.java` | ★★★★★ |
| 对象存储与跨资源一致性 | 合并后的文件进入 MinIO，而元数据进入 MySQL，需要理解补偿、幂等和孤儿对象治理 | `backend/videomind-server/src/main/java/com/videomind/module/video/service/impl/MultipartUploadServiceImpl.java` | ★★★★☆ |
| FFmpeg、ASR、OCR 与时间轴 | 视频内容并不是一次模型调用，需要切音频、抽帧、异步识别并恢复绝对时间 | `backend/videomind-server/src/main/java/com/videomind/module/task/analysis/VideoAnalysisHandler.java` | ★★★★☆ |
| Elasticsearch BM25、向量检索与过滤 | RAG 的召回同时依赖关键词精确匹配、语义相似度和用户/知识库隔离 | `backend/videomind-server/src/main/java/com/videomind/module/knowledge/retrieval/ElasticsearchGateway.java` | ★★★★★ |
| RRF 与 Rerank | 两路检索分数不可直接比较，需要先按排名融合，再用更强模型精排 | `backend/videomind-server/src/main/java/com/videomind/module/knowledge/retrieval/HybridRetrievalService.java` | ★★★★★ |
| 幂等、Inbox、租约与检查点 | 面试官通常不会停在“用了 MQ”，而会追问重复消费、进程崩溃和断点恢复 | `backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/TaskEventConsumerServiceImpl.java` | ★★★★★ |
| SSE、取消传播与有界线程池 | AI 请求时间长，需限制并发、响应超时，并在客户端断开后停止无效工作 | `backend/videomind-server/src/main/java/com/videomind/module/agent/workflow/WorkflowDecisionRunner.java` | ★★★☆☆ |

## 2. 重点亮点与学习顺序

1. **先学“大文件可靠上传”**：从分片、Bitmap、分片锁、整文件 MD5 到数据库唯一约束，建立“应用层快速判断 + 数据库最终裁决”的并发思维。关键词：断点续传、幂等、内容去重、软删除唯一性、对象存储补偿。入口：`backend/videomind-server/src/main/java/com/videomind/module/video/service/impl/MultipartUploadServiceImpl.java`。
2. **再学“可靠异步任务框架”**：把事务消息、事件表、消费 Inbox、状态机、CAS 租约和阶段检查点串成一条故障恢复链。重点不只是成功路径，而是任意一步宕机后由谁重试、如何避免重复副作用。入口：`backend/videomind-server/src/main/java/com/videomind/module/task/mq/TaskTransactionListener.java`。
3. **然后学“多模态流水线”**：理解音频分片、ASR 供应商任务持久化、关键帧 OCR、并行编排和时间轴融合，能解释重叠窗口如何去重、OCR 失败为何允许降级。入口：`backend/videomind-server/src/main/java/com/videomind/module/task/analysis/VideoAnalysisHandler.java`。
4. **接着学“混合检索 RAG”**：从语义切块、向量化、索引过滤开始，再理解 BM25、kNN、RRF、Rerank 的职责分工以及单路故障降级。入口：`backend/videomind-server/src/main/java/com/videomind/module/knowledge/retrieval/HybridRetrievalService.java`。
5. **最后学“受约束智能体工作流”**：把规划、执行、证据审查、补证、查询改写、预算和超时看成可控状态机，而不是让模型无限自主调用工具。入口：`backend/videomind-server/src/main/java/com/videomind/module/agent/workflow/PlannerExecutorCriticWorkflow.java`。
6. **用测试和故障演练收尾**：沿真实基础设施脚本复盘 Broker 重启、Redis 故障、长音频、删除和 RAG 全链路，形成“设计—故障—观测—结论”的面试闭环。入口：`ops/e2e-local.ps1`。

## 3. 必备知识点

- [ ] 能画出上传、任务创建、消息投递、消费、解析、索引和问答的完整数据流。
- [ ] 能说明事务消息解决的是哪一段一致性，以及为什么它不等于端到端 exactly-once。
- [ ] 能区分业务幂等键、消费 Inbox、CAS 租约和阶段检查点各自防什么问题。
- [ ] 能解释数据库唯一约束为什么是并发去重的最终防线，以及软删除记录如何兼容重新上传。
- [ ] 能说明 Redis Bitmap 丢失、分片锁过期、MinIO 成功但数据库失败时会发生什么。
- [ ] 能讲清 ASR 音频切片的逻辑窗口、物理重叠和中点归属去重算法。
- [ ] 能说明 ASR 与 OCR 为什么并行、为什么 OCR 可降级而 ASR 为空需要失败。
- [ ] 能从时间戳、文本相似度和置信度解释语音与视觉文本的融合过程。
- [ ] 能解释 BM25 和向量分数为什么不能直接相加，以及 RRF 的排名融合思想。
- [ ] 能说出混合检索在向量服务、关键词检索或精排服务故障时的降级顺序。
- [ ] 能解释规划器、执行器、审查器的边界，以及工具白名单、次数预算和截止时间的价值。
- [ ] 能主动指出当前租约没有周期续期、跨存储事务较长、缓存状态不可重建等工程债务。
- [ ] 能把“功能可用”与“性能达标”分开：前者由自动化测试证明，后者必须用压测数据证明。

## 4. 推荐阅读

| 主题 | 通用技术点 | 位置 | 预计时间 | 读完能回答什么 |
|---|---|---|---:|---|
| 总体链路 | 分层架构、异步数据流 | `README.md` | 20 分钟 | 一次视频上传后经过哪些后端阶段？ |
| 分片上传 | Bitmap、分布式锁、原子落盘、完整性校验 | `backend/videomind-server/src/main/java/com/videomind/module/video/service/impl/MultipartUploadServiceImpl.java` | 45 分钟 | 分片重复上传和并发合并如何处理？ |
| 数据库去重 | 唯一约束、生成列、软删除 | `backend/videomind-server/src/main/resources/db/migration/V21__unique_video_file_user_md5.sql` | 15 分钟 | 为什么不能只用 Redis 或先查后插去重？ |
| 事务消息 | 半消息、本地事务、事务回查 | `backend/videomind-server/src/main/java/com/videomind/module/task/mq/TaskTransactionListener.java` | 40 分钟 | 数据库提交和消息可见如何协同？ |
| 本地任务事务 | 业务指纹、事件表、原子落库 | `backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/LocalTaskTransactionServiceImpl.java` | 40 分钟 | 重复请求如何复用同一个活动任务？ |
| 消费可靠性 | Inbox、CAS 租约、重试、状态投影 | `backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/TaskEventConsumerServiceImpl.java` | 60 分钟 | 消息重复、消费者崩溃和并发抢占如何处理？ |
| 状态机 | 合法状态迁移、版本号、最大尝试次数 | `backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/ProcessingTaskStateMachineImpl.java` | 35 分钟 | 如何防止旧请求覆盖新状态？ |
| 阶段检查点 | 阶段幂等、校验和、断点续跑 | `backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/TaskCheckpointServiceImpl.java` | 30 分钟 | 任务重试为什么不用从头解析？ |
| 视频编排 | 可恢复流水线、并行与降级 | `backend/videomind-server/src/main/java/com/videomind/module/task/analysis/VideoAnalysisHandler.java` | 60 分钟 | 哪些阶段可以复用，哪些失败必须终止？ |
| 并行解析 | CompletableFuture、主次依赖 | `backend/videomind-server/src/main/java/com/videomind/module/task/analysis/ParallelVideoAnalysisStage.java` | 25 分钟 | ASR 与 OCR 的失败语义为什么不同？ |
| ASR 分片 | 重叠窗口、供应商任务持久化、时间恢复 | `backend/videomind-server/src/main/java/com/videomind/module/task/analysis/tencent/TencentAsrChunkTranscriber.java` | 60 分钟 | 外部异步任务在宕机后如何继续轮询？ |
| 时间轴融合 | 时序归一、文本相似度、置信度过滤 | `backend/videomind-server/src/main/java/com/videomind/module/knowledge/timeline/TimelineFusionService.java` | 50 分钟 | 语音和画面文字如何组成可检索上下文？ |
| 索引物化 | 版本化发布、语义切块、嵌入 | `backend/videomind-server/src/main/java/com/videomind/module/knowledge/timeline/TimelineKnowledgeIndexer.java` | 45 分钟 | 重建索引时如何避免用户读到半成品？ |
| ES 建模 | BM25、dense vector、租户过滤 | `backend/videomind-server/src/main/java/com/videomind/module/knowledge/retrieval/ElasticsearchGateway.java` | 50 分钟 | 关键词和向量检索分别如何查询？ |
| 混合检索 | RRF、Rerank、故障降级 | `backend/videomind-server/src/main/java/com/videomind/module/knowledge/retrieval/HybridRetrievalService.java` | 60 分钟 | 多路结果如何融合并控制最终上下文数量？ |
| 智能体工作流 | Planner–Executor–Critic、有界自主性 | `backend/videomind-server/src/main/java/com/videomind/module/agent/workflow/PlannerExecutorCriticWorkflow.java` | 60 分钟 | 如何防止无关检索、无限补证和错误引用？ |
| 查询改写 | 关键实体保护、改写数量限制 | `backend/videomind-server/src/main/java/com/videomind/module/agent/workflow/QueryRewriteGuard.java` | 25 分钟 | 改写如何避免丢失编号、数字和专有词？ |
| 对话缓存 | Cache Aside、固定检索范围、故障回源 | `backend/videomind-server/src/main/java/com/videomind/module/chat/service/impl/ConversationContextServiceImpl.java` | 40 分钟 | Redis 故障时如何保证正确性而非只追求命中？ |
| 真实故障验证 | E2E、依赖重启、降级与清理 | `ops/e2e-local.ps1` | 60 分钟 | 你如何证明设计在故障下仍然成立？ |

## 5. 自学提醒

遇到看不懂的文件、状态迁移或原理时，直接把对应仓库相对路径和疑问交给 AI，让它结合上下文解释。本文提供的是学习路径、关键问题和面试抓手，不做逐行源码讲解；你仍需要亲自沿推荐路径阅读实现，并用断点、日志和测试验证自己的理解。

## 6. 项目技术定位

VideoMind 的后端本质上是一个**面向长耗时、多外部依赖任务的内容处理与检索系统**。它不只是“调用大模型总结视频”，而是把大文件可靠接入、异步任务一致性、多模态内容生产、知识索引和受约束问答组织成可恢复的流水线。

从岗位能力看，这个项目最能证明四类后端素质：第一，能用数据库约束和状态机处理并发，而不是依赖单机判断；第二，能接受消息至少一次、外部接口超时和跨存储不原子的现实，并通过幂等、检查点和补偿收敛；第三，能把搜索算法放进工程约束中，兼顾隔离、降级和可观测；第四，能诚实识别系统边界，例如长任务租约续期尚未接入、Redis 分片状态尚不能从磁盘重建、跨 MinIO/MySQL 操作仍需更完善的清理机制。

## 7. 核心原理解析

### 7.1 任务创建与消息投递的一致性

**问题**：若先提交任务再发消息，发送失败会留下永不执行的任务；若先发消息再提交任务，消费者可能查不到业务数据。
**机制**：生产者先发送半消息，Broker 暂不向消费者投递；本地事务由活动业务指纹唯一约束裁决。获胜者将新任务、规范事件和业务关联一并落库并提交消息；并发输家复用获胜任务及其规范事件，不写新事件，回滚自己的半消息但向接口正常返回复用结果。Broker 对获胜消息状态不确定时，通过事务回查读取持久化事件状态。
**项目落点**：`backend/videomind-server/src/main/java/com/videomind/module/task/mq/RocketMqTransactionalTaskMessageProducer.java`、`backend/videomind-server/src/main/java/com/videomind/module/task/mq/TaskTransactionListener.java`。这既解决创建侧一致性，也避免重复请求产生第二个有效事件；同一规范事件在消费侧仍按至少一次处理。

### 7.2 重复消费与断点续跑

**问题**：消息可能重复，消费者可能在“业务完成但确认前”崩溃，长任务重试若从头执行会浪费大量算力。
**机制**：Inbox 记录同一消费组对事件的处理状态；状态机通过版本号条件更新抢占执行权；阶段检查点以任务和阶段唯一定位，并保存结果校验信息。重试先验证已有产物，只补做缺失阶段。
**项目落点**：`backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/ConsumerInboxServiceImpl.java`、`backend/videomind-server/src/main/java/com/videomind/module/task/service/impl/TaskCheckpointServiceImpl.java`。当前状态机虽定义租约续期能力，但主处理链尚未周期调用，超长任务需要补充心跳续约。

### 7.3 分片上传与并发去重

**问题**：大视频在弱网下整文件重传成本高，同一用户并发上传相同内容又可能产生重复记录和对象。
**机制**：客户端上传分片，服务端校验分片摘要后原子落盘，用 Bitmap 标记进度，并用分片粒度锁保证同一分片幂等。完成时合并并校验整文件 MD5。应用层可快速复用已有文件，数据库唯一约束负责裁决并发竞态。
**项目落点**：`backend/videomind-server/src/main/java/com/videomind/module/video/service/impl/MultipartUploadServiceImpl.java`、`backend/videomind-server/src/main/resources/db/migration/V21__unique_video_file_user_md5.sql`。Bitmap 是加速状态而非完整事实源，Redis 丢失后的恢复仍是改进项。

### 7.4 多模态时序融合

**问题**：ASR 描述“说了什么”，OCR 描述“画面出现什么”，两者时间粒度不同，直接拼接会破坏上下文顺序并制造重复。
**机制**：音频按带重叠的物理窗口识别，再用片段中点归属逻辑窗口去重并恢复绝对时间；关键帧同时采用场景变化和最大间隔覆盖。融合时清洗并排序语音与 OCR，根据静默间隔、长度、相似度和置信度合并，再按时间交错形成可检索文本。
**项目落点**：`backend/videomind-server/src/main/java/com/videomind/module/task/analysis/chunk/AsrChunkResultMerger.java`、`backend/videomind-server/src/main/java/com/videomind/module/knowledge/timeline/TimelineFusionService.java`。

### 7.5 混合检索与证据收敛

**问题**：关键词检索对实体和专有词敏感，但不理解同义表达；向量检索能找语义近邻，却可能忽略精确编号。两路原始分数尺度不同，无法可靠直接相加。
**机制**：分别执行 BM25 和向量召回，用倒数排名融合消除分数尺度差异，再用精排模型评估查询与候选的细粒度相关性。其上层工作流限制可调用工具、调用次数、重规划次数和截止时间，并由证据审查决定接受还是定向补证。
**项目落点**：`backend/videomind-server/src/main/java/com/videomind/module/knowledge/retrieval/HybridRetrievalService.java`、`backend/videomind-server/src/main/java/com/videomind/module/agent/workflow/PlannerExecutorCriticWorkflow.java`。

## 8. 关键设计决策

| 决策 | 备选方案 | 取舍 | 主要风险 | 如何验证 |
|---|---|---|---|---|
| RocketMQ 事务消息 + 本地事件 | 定时扫任务表；普通消息配补偿 | 降低创建到投递延迟，并让 Broker 回查数据库事实；增加中间状态和运维复杂度 | 回查逻辑错误导致误提交或长期未知 | 注入发送异常、重启 Broker，核对任务、事件与最终消费结果 |
| 至少一次 + 多层幂等 | 追求端到端 exactly-once | 符合 MQ 和外部 API 现实，可通过业务键、Inbox、CAS、检查点收敛 | 幂等键选错或副作用未覆盖 | 重复投递同一事件、并发启动消费者、在阶段边界强杀进程 |
| Redis Bitmap 记录分片 | MySQL 每分片一行；扫描磁盘 | 内存紧凑、查询快；但 Redis 不再只是纯缓存 | 过期或数据丢失后无法根据临时文件自动重建 | 上传一半后清空 Redis，观察恢复行为并补充重建方案 |
| 用户 + 文件 MD5 数据库唯一约束 | 仅先查后插；全局 MD5 唯一 | 用户维度隔离，并允许不同用户拥有同内容；数据库解决并发竞态 | MD5 碰撞理论风险、软删除语义复杂 | 并发完成同一文件，断言仅一个活动记录且失败方对象被清理 |
| ASR 与 OCR 并行，OCR 可降级 | 完全串行；任一路失败即失败 | 缩短关键路径，并保证无视觉文本时仍可提供语音内容 | OCR 长时间占用资源、降级质量不可见 | 模拟 OCR 超时/异常，检查 ASR 结果、状态和告警 |
| RRF 后再 Rerank | 原始分数加权；只做向量检索 | 对异构分数稳定，精排只处理小候选集 | 精排服务延迟和故障影响尾延迟 | 构造实体词、同义词、混合问题，对比 Recall@K、MRR、nDCG 与 P95 |
| 有界 Planner–Executor–Critic | 单次固定检索；完全自治 Agent | 可针对证据缺口补检索，同时限制成本和循环 | 规划器不可用时当前缺少规则式兜底 | 注入非法计划、重复工具调用、超时和低相关证据，核对终止原因 |
| 版本化索引后发布 | 原地覆盖全部文档 | 避免用户读取重建中的半成品 | 旧版本清理和存储膨胀 | 在重建中并发查询，检查只命中已发布版本，再核对旧版本回收 |

## 9. 量化与验证（含待测）

当前可以引用的事实是：后端自动化测试最近一次执行为 **247 个测试通过、0 失败、0 错误，7 个依赖真实基础设施的集成测试跳过**；前端已有测试与构建验证。它证明了功能回归基线，但不能替代真实环境的性能与故障数据。以下指标统一标记为待测，简历和面试中不应提前编造数值。

| 目标 | 指标 | 测量方法 | 当前状态 |
|---|---|---|---|
| 分片上传可靠性 | 分片成功率、重传率、完成耗时 P50/P95/P99 | 使用不同文件尺寸、并发数、延迟和丢包率压测；随机重复分片并中断续传 | 待测 |
| 上传并发去重 | 重复记录数、孤儿对象数、冲突请求收敛时间 | 同一用户并发完成相同 MD5，核对 MySQL 活动记录和 MinIO 对象清单 | 约束与测试已实现，真实 MinIO/MySQL 并发待测 |
| 任务恢复能力 | 故障恢复时间、重复副作用次数、检查点复用率 | 在消息提交、阶段完成、确认消费前等位置强杀进程并重放 | 脚本具备部分场景，系统化统计待测 |
| 租约安全性 | 超租约任务比例、重复执行数 | 构造超过默认租期的长视频并并发重投；接入续约后对比 | 已识别缺口，待实现与测量 |
| 多模态流水线性能 | 各阶段耗时、并行加速比、降级率 | 分别记录音频提取、ASR、OCR、融合、索引耗时，对比串行基线 | 待测 |
| ASR 切片正确性 | 边界漏词率、重复片段率、时间戳误差 | 构造跨切片说话样本，与人工标注时间轴对齐 | 算法与单测存在，真实语料待测 |
| 检索质量 | Recall@K、MRR、nDCG、引用准确率 | 建立包含实体词、同义表达、跨文档问题的人工标注集，分别消融 BM25、向量、RRF、Rerank | 待测 |
| 检索可用性 | 成功率、降级率、P95/P99、外部调用成本 | 分别注入向量、关键词和精排故障，统计降级路径与返回质量 | 降级逻辑已实现，基准待测 |
| 智能体收益 | 首轮接受率、平均工具调用数、补证成功率、无依据引用率 | 对同一评测集比较单次检索与工作流，记录每次决策事件 | 待测 |
| 缓存韧性 | 命中率、回源耗时、Redis 故障成功率 | 预热后压测，再停 Redis 验证 MySQL 回源与恢复回填 | E2E 有故障路径，量化待测 |

面试时建议采用固定表述：**“我已经用自动化测试验证功能和状态收敛；吞吐、尾延迟和检索质量尚未形成可公开的基准数值，我会按上述场景测量后再写入简历。”**
