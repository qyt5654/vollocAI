# vollocAI 项目技术报告

---

## 一、项目概述

构建支持主流 AI 模型的多场景交互平台。用户可选择模型进行文字/图片/语音对话，系统通过意图识别 → 线程池异步 → 流式推送完成响应。支持多轮对话记忆、RAG 知识库、Agent 工具调用与多 Agent 编排。

---

## 二、技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 框架 | Spring Boot 3.4 + MyBatis + Vue 3 | 单体应用，RESTful API |
| AI 框架 | Spring AI 1.0.0-M5 + Alibaba DashScope | Chat / Image / Speech / Embedding |
| 缓存 | Redis 7 | 结果缓存、会话记忆、流式队列 |
| 数据库 | MySQL 8 | 任务状态、用户、模型配置、会话持久化 |
| 向量存储 | 余弦相似度内存引擎 / Milvus（条件启用） | 自研检索 + 生产可切 Milvus |
| 鉴权 | Sa-Token | Token 认证、RBAC 角色权限 |
| 限流 | Redisson + AOP | 分布式原子计数器限流 |
| 前端 | Vue 3 CDN + Marked | 单页应用，Markdown 渲染，SSE 流式 |

---

## 三、系统架构

```
POST /ai/chat
    │
    ├─ 落库 PENDING + 线程池提交 + 返回 taskId
    │
    ├─ EventSource("/ai/stream/{taskId}")  ← SSE 流式订阅
    │
    └─ AiTaskExecutor (线程池异步)
         ├─ 意图识别 (text/image/voice)
         ├─ 模型凭证解析 (database_ai → apiKey/URL/Model)
         ├─ 会话历史加载 (Redis)
         └─ 路由执行:
              ├─ 文本对话 → ReAct (原生 Function Calling + 流式)
              └─ 根因分析 → SupervisorAgent (Plan→Execute→Replan)
                   │
         ┌────────┴────────┐
         ▼                 ▼
    Redis List           Redis "ai:result:{id}"
    BLPOP 逐 token       (轮询兜底)
         │
         ▼
    SSE 端点 → EventSource → 前端逐字渲染

    多轮记忆：Redis (session:{id}:history) + MySQL (chat_session)
    RAG：ReAct → queryInternalDocs → VectorStore 余弦检索
```

---

## 四、核心功能与技术实现

### 4.1 意图识别引擎

一次 LLM 调用完成 text / image / voice 三分类。System Prompt 让模型充当分类器，输出严格 JSON。

**关键代码**: `IntentRecognitionService.java`

### 4.2 动态模型切换

用户从 `database_ai` 表中选择模型，每条请求携带 `modelId`。查表获取 `apiKey + apiUrl + modelName`，动态构建 `OpenAiChatModel` 客户端，而非使用 Spring 单例 Bean。

- **知识点**: Spring AI ChatModel 动态创建、OpenAI 兼容 API 的多厂商适配（DashScope / DeepSeek / OpenAI）
- **关键代码**: `MultimodalAIServiceImpl.java`, `AiTaskExecutor.resolveCredentials()`

### 4.3 线程池异步处理

HTTP 请求由自定义线程池异步执行——提交即返回 taskId，不阻塞请求线程。等待期间前端 SSE 流式接收生成内容，完成后 Redis+DB 双写。

- 状态机: `PENDING → PROCESSING → COMPLETED / FAILED`
- 无 MQ 依赖——单应用内线程池直调，避免消息队列的复杂度和延迟
- **知识点**: 线程池异步编排、状态机设计
- **关键代码**: `AiTaskExecutor.java`

### 4.4 会话记忆（短期 + 长期）

| 层级 | 存储 | TTL | 用途 |
|------|------|-----|------|
| 短期 | Redis `session:{id}:history` | 30min | 热缓存，高速读写 |
| 长期 | MySQL `chat_session` 表 | 永久 | 退出登录后可找回对话 |

- **知识点**: 多轮对话上下文管理、Redis+DB 双写、Redisson 分布式锁防竞态
- **关键代码**: `AiTaskExecutor.loadHistory()/saveHistory()/saveSessionToDb()`, `ChatSessionDao.xml`

### 4.5 管理员模型管理

- `manager="1"` 为管理员，可管理模型配置和用户分配
- `model_assignment` 关联表实现模型-用户多对多分配
- 普通用户只能看到分配给自己的模型，无模型不能对话
- 左侧会话列表实时更新，支持删除
- **知识点**: RBAC 权限、多对多关联设计
- **关键代码**: `AiClientController`, `ModelAssignmentDao.xml`

### 4.6 Tool Calling 工具调用

4 个工具注册，AI 模型自主判断何时调用:

| 工具 | 功能 | 状态 |
|------|------|------|
| `getCurrentDateTime` | 时间感知，解决 LLM "时间失忆" | 真实 |
| `calculate` | 数学表达式求值（递归下降解析器） | 真实 |
| `queryPrometheusAlerts` | Prometheus 告警查询 | Mock |
| `queryLogs` | 应用日志检索 | Mock |

- **知识点**: Function Calling、Tool 注册与调度
- **关键代码**: `ToolConfig.java`, `ToolRegistry.java`

### 4.7 ReAct Agent（原生 Function Calling + 流式）

基于模型原生 Function Calling 实现，而非正则解析 Prompt 文本。

- **工具注入**：`FunctionCallback` 直接交给 `OpenAiChatModel`，模型自主判断何时调用
- **流式输出**：`model.stream()` — 模型边生成边流式，框架自动处理工具调用循环（暂停流式 → 执行工具 → 回传结果 → 继续流式）
- **与正则 ReAct 的区别**：不再需要手工写正则解析 Thought/Action，工具调用和流式输出同一条原生 API，真正同时支持
- **路由策略**：文本对话统一走 ReAct（不需要工具时第一轮直接 Final Answer），复杂分析走 Supervisor

- **知识点**: Function Calling、ChatModel.stream()、FunctionCallback、工具编排与流式共存
- **关键代码**: `ReActAgentService.java`

### 4.8 RAG 检索增强生成

**两套向量检索引擎，上层接口统一**：

| 模式 | 引擎 | 适用场景 |
|------|------|---------|
| 内存模式（当前） | `VectorStore` — 自研余弦相似度 + 优先队列 Top-K | 数据量 < 10 万条，零依赖 |
| 生产模式（条件启用） | Milvus — IVF_FLAT 索引 + IP 度量 | 大数据量，`@ConditionalOnProperty` 一键切换 |

**流程**：文档分片(500字, overlap 50) → DashScope Embedding(1024维) → 向量存储 → Top-K 检索 → 注入 Prompt → LLM 生成。

**切换方式**：`DocumentService` 接口不变，底层通过 `VectorStore`（默认）或 `MilvusServiceClient`（`milvus.enabled=true`）实现。数据量上来改配置即可，无需改业务代码。

- **知识点**: RAG 架构、Embedding、余弦相似度、向量数据库、分片策略、存储抽象
- **关键代码**: `VectorStore.java`, `DocumentService.java`, `MilvusConfig.java`

### 4.9 Supervisor 多 Agent 编排

```
Supervisor → 分析任务类型，制定计划
    ↓
Planner → 拆解为执行步骤 [STEP1, STEP2, ...]
    ↓
Executor → 执行步骤，收集证据
    ↓
Replanner → 评估: CONTINUE / ADJUST / FINISH
    ↓
生成结构化报告（根因分析 + 处理方案 + 结论）
```

- **知识点**: 多 Agent 协作、Plan-Execute-Replan 模式、Prompt 角色工程
- **关键代码**: `SupervisorAgentService.java`

### 4.10 SSE Token 级流式推送

ReAct 的 `executeStream()` 调用模型原生 `stream()` API，每个 token 实时 `RPUSH` 入 Redis List。SSE 端点 `BLPOP` 阻塞获取，推到前端逐字渲染。工具调用期间流式自动暂停，执行完继续——前端全程无感知。

```
ReAct.executeStream() → model.stream() → token → RPUSH → SSE BLPOP → 前端逐字
                           ↓ (需要工具时自动)
                    FunctionCallback 执行 → 结果回传 → 继续 stream
```

与轮询方案的区别：轮询是固定间隔取批量，BLPOP 是阻塞等新数据，token 一到立刻推送。

- **知识点**: SSE、Redis List 队列、BLPOP 阻塞消费、Flux 响应式编程、Function Calling + Streaming 共存
- **关键代码**: `AiClientController.streamResult()`, `ReActAgentService.executeStream()`

### 4.11 分布式限流

基于 `@RateLimit` 注解 + Redisson `RAtomicLong` 实现全局限流。Key 格式: `rate_limit:类名:方法名:user:用户ID:win:窗口序号`。

- **知识点**: 分布式限流、Redis 原子计数器、AOP 切面编程

---

## 五、简历描述

```markdown
#resume-project(
  title: "AI大模型应用项目",
  duty: "自研项目",
)[
- *项目背景*：\
  构建支持主流AI模型的多场景交互平台，用户可选择模型进行文字/图片/语音对话。
- *技术架构*：\
  SpringBoot + MyBatis + MySQL + Redis + Spring AI + Vue 3
- *工作职责*：\
  *1.*基于SpringBoot+Vue3实现前后端分离应用，完成RESTful API与SSE流式推送前端。\
  *2.*基于 Spring AI 实现意图识别引擎，单次LLM调用完成text/image/voice三分类并路由至对应多模态生成服务。\
  *3.*实现 Agent 体系：ReAct Agent（注册时间/计算/告警/日志 4个Tool，自主判断调用）；Supervisor多Agent编排（Supervisor→Planner→Executor→Replanner循环），输出结构化分析报告。\
  *4.*实现 RAG 检索增强生成：自研余弦相似度内存检索引擎（优先队列Top-K），文档分片→DashScope Embedding→向量检索→注入Prompt；上层接口统一，底层可切 Milvus。\
  *5.*基于 Redisson 实现分布式限流；基于自定义线程池 + Redis List + SSE 实现 Token 级流式推送——提交即返回taskId，AI生成token时实时推送前端逐字渲染，结果Redis+DB双写兜底。\
  *6.*基于 Redis + MySQL 双写实现会话记忆：短期Redis缓存+长期DB持久化，支持退出重登后找回历史对话；管理员可配置多模型并分配给不同用户。\
]
```

---

## 六、面试要点

### Q1: 为什么用线程池异步而不是 MQ？

单应用场景下，MQ 增加运维复杂度（需要额外部署 RocketMQ），且引入中间延迟（生产→Broker→消费）。自定义线程池直调 AI 服务，token 生成时实时写入 Redis List，SSE 端点 BLPOP 阻塞获取推送，延迟极低。MQ 更适合多服务解耦或高吞吐场景。

### Q2: ReAct Agent 怎么实现的？

基于模型原生 Function Calling + Streaming API。`FunctionCallback` 直接交给 `OpenAiChatModel`，模型自主判断何时调工具。`model.stream()` 边生成边流式——需要工具时框架自动暂停流式、执行工具、回传结果、继续流式。前端看到的是 token 级实时输出，工具调用过程对用户透明。

### Q3: RAG 怎么实现的？为什么没用 Milvus？

- 检索引擎：自研余弦相似度内存引擎，优先队列 Top-K 堆排，100 行代码。和 Milvus 的 IP 度量数学上等价
- 分片: 500 字 + 50 字 overlap，在句号/换行处断句，避免切断语义
- Embedding: DashScope text-embedding-v2, 1024维
- 架构抽象：`DocumentService` 接口不变，底层 `VectorStore`（默认）或 `MilvusServiceClient`（`milvus.enabled=true` 条件启用）。数据量上来改配置切换，零代码改动
- 面试加分：手写余弦相似度 + Top-K 比调 Milvus API 更体现算法能力

### Q4: 流式推送怎么实现的？和 Function Calling 能同时用吗？

能同时用。ReAct 的 `executeStream()` 调用 `model.stream()` + `FunctionCallback`，框架自动处理：token 流式输出 → 遇到工具调用 → 暂停流式 → 执行工具 → 结果回传 → 继续流式。每个 token 通过 `RPUSH` 入 Redis List，SSE 端点 `BLPOP` 阻塞获取推前端。工具调用和流式输出同一条原生 API，不需要自己写正则解析。

### Q5: 会话记忆怎么存的？

双层: Redis `session:{id}:history` 存最近 10 轮（热缓存，快）；MySQL `chat_session` 表存全量（持久化，登录后可找回）。前端每次请求带 `sessionId`，发送消息后侧边栏实时更新会话列表。

---

## 七、项目文件清单

| 类别 | 文件 | 说明 |
|------|------|------|
| **Agent** | `ReActAgentService.java` | ReAct 推理循环 |
| | `SupervisorAgentService.java` | 多 Agent 编排 |
| **AI 服务** | `MultimodalAIServiceImpl.java` | 动态模型调用(chat/image/voice/stream) |
| | `IntentRecognitionService.java` | 意图三分类 |
| | `AiTaskExecutor.java` | 线程池异步执行器（替代 MQ 消费者） |
| **工具** | `ToolConfig.java` | 4 个 Tool 定义与注册 |
| | `ToolRegistry.java` | 工具注册表 |
| **RAG** | `VectorStore.java` | 余弦相似度内存检索引擎 |
| | `DocumentService.java` | 分片+Embedding+检索（接口层） |
| | `MilvusConfig.java` | Milvus 连接（条件启用，生产切换） |
| **限流** | `RateLimitAspect.java` | Redisson 分布式限流 |
| | `@RateLimit` | 自定义限流注解 |
| **控制器** | `AiClientController.java` | /chat, /stream, /result, /sessions, /admin/* |
| | `UserController.java` | 登录/注册(含角色返回) |
| **鉴权** | `SaTokenConfigure.java` | Sa-Token 拦截器配置 |
| | `LoginInterceptor.java` | 登录上下文注入 |
| **实体** | `AiTask.java`, `ChatSession.java`, `DatabaseAi.java`, `ModelAssignment.java` | 核心实体 |
| **DAO** | `AiTaskDao.xml`, `ChatSessionDao.xml`, `DatabaseAiDao.xml`, `ModelAssignmentDao.xml` | MyBatis 映射 |
| **SQL** | `AI任务表.sql`, `会话表.sql`, `用户表结构.sql`, `用户-模型关系表.sql`, `模型分配表.sql` | 建表脚本 |
| **前端** | `static/index.html` | Vue3 单页(含 Markdown 渲染、SSE、管理面板) |
| **配置** | `application.yaml` | 全部中间件+模型配置 |
| | `docker-compose.yml` | Redis（+ Milvus 可选） |
