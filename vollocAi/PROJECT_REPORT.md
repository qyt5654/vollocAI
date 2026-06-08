# vollocAI 项目技术报告

---

## 一、项目概述

基于 Spring Boot 3 + Spring AI + Vue 3 的智能对话平台。支持 SIMPLE（日常问答）和 DEEP（多步 RAG+工具协作）双模式 Agent，SSE 流式逐字推送，Redis+MySQL 双写会话记忆，MCP 外部工具自动发现，联网搜索。

---

## 二、技术栈

| 层级 | 技术 |
|------|------|
| 框架 | Spring Boot 3.4 + MyBatis |
| AI | Spring AI 1.0.0-M5 + DashScope / DeepSeek / OpenAI 兼容 |
| 缓存 | Redis 7（会话/流式队列/结果缓存） |
| 数据库 | MySQL 8（任务/用户/模型配置/会话持久化） |
| 向量 | 余弦相似度内存引擎 + Milvus 双模 |
| 鉴权 | Sa-Token（Token + RBAC） |
| 限流 | Redisson + AOP |
| 前端 | Vue 3 CDN + marked.js + KaTeX（暗色主题 + 粒子背景） |

---

## 三、系统架构

```
POST /ai/chat
    │
    ├─ 落库 PENDING + 线程池提交 + 返回 taskId
    │
    ├─ EventSource("GET /ai/stream/{taskId}")  ← SSE 长连接
    │
    └─ AiTaskExecutor（aiPool 子线程，异步）
         ├─ 意图识别（text/image/voice → LLM 分类）
         ├─ 模型凭证解析（database_ai → apiKey/URL/Model）
         ├─ 会话历史加载（Redis → MySQL）
         │
         ├─ image/voice → MultimodalAIService 生成
         │
         └─ text → UnifiedAgentService
              ├─ deep=false → SIMPLE（逐轮 JSON 决策 + 工具调用）
              └─ deep=true  → DEEP（Supervisor计划→Executor分析→Replanner反思→结论）
                   │
         ┌────────┴────────┐
         ▼                 ▼
    Redis List           Redis "ai:result:{id}"
    BLPOP 逐 token       （轮询兜底）
         │
         ▼
    AiClientController.streamResult()
    emitter.send(token) → SSE → 前端 EventSource → fullText累加 → 双缓冲Markdown渲染
```

---

## 四、核心模块

### 4.1 意图识别

一次 LLM 调用完成 text/image/voice 三分类 + 复杂度判断。

```
输入: 用户原始文本
输出: {"intent":"text|image|voice", "content":"原文", "deep":true|false}

deep=true: 需要多步分析才能回答的复杂问题
deep=false: 可直接回答的简单问答
```

**关键文件**: `IntentRecognitionService.java`

### 4.2 统一 Agent 服务

**SIMPLE 模式**：LLM 逐轮 JSON 决策（`{"type":"final"}` 或 `{"type":"tool",...}`），只暴露基础工具（calculate + getCurrentDateTime + webSearch + MCP工具），不暴露 RAG。

**DEEP 模式**：Supervisor 制定分步计划 → 逐步骤调 deep_research → Executor 分析 → Replanner 反思（至少一半步骤后才允许 FINISH）。全量工具可用。

**planOnce 三层容错**：有效JSON → 纯文本包装 → 纠错重试。

**关键文件**: `UnifiedAgentService.java`, `AgentExecutor.java`

### 4.3 工具调用 & MCP

内置工具 + 外部 MCP Server 自动发现：

| 工具 | 来源 | 说明 |
|------|------|------|
| `getCurrentDateTime` | 内置 | 时间感知 |
| `calculate` | 内置 | 递归下降数学解析器 |
| `queryInternalDocs` | 内置 | RAG 知识库检索 |
| `webSearch` | 内置 | 博查联网搜索 |
| `deep_research` | 内置 | DEEP 模式 LLM 子 Agent 编排 |
| 外部 MCP 工具 | MCP Server | 启动时自动发现并注册 |

MCP 配置：`application.yaml` 中 `mcp.servers: name:command`，逗号分隔多个 Server。`McpClientManager` 启动时通过 JSON-RPC over stdio 调用 `tools/list`，自动获取工具列表并注册到 `ToolRegistry`。

**关键文件**: `ToolConfig.java`, `McpClientManager.java`, `McpClient.java`, `McpSearchService.java`

### 4.4 RAG 检索增强生成

双引擎架构：Milvus（生产）→ 内存 VectorStore（降级），`@Autowired(required=false)` 自动切换。余弦相似度 1536 维向量检索，Top-K 堆排，0.75 相似度阈值过滤。分片 500 字 + 50 字 overlap，句号处断句。

**关键文件**: `VectorStore.java`, `DocumentService.java`

### 4.5 SSE Token 级流式推送

完整链路：`LLM.stream()` → Flux → FluxSink.next(char) → Redis List rightPush → BLPOP 消费 → SSE emitter.send → 前端 EventSource → 双缓冲 Markdown 渲染。

关键设计：
- **Redis 解耦**：Agent 线程（写）与 SSE 线程（读）异步通信
- **心跳保活**：空 data 事件防浏览器 45s 超时
- **`\n` 编码**：`<NL>` 占位符解决 SSE 协议吞换行
- **onCancel 不取消任务**：只 dispose 当前 LLM 订阅，前端重连从 Redis 续读
- **双缓冲渲染**：`findSafeEnd` 语法完整性检查 + 稳定部分缓存不变 + 尾部纯文本显示

**关键文件**: `AiClientController.java`, `AiTaskExecutor.java`

### 4.6 前端

暗色主题 + 粒子背景（Canvas 35 节点神经网络风格）+ 双缓冲流式 Markdown 渲染 + 工具链可视化面板 + 搜索来源卡片 + 管理面板（模型 CRUD + 分配）。

**关键文件**: `static/index.html`

---

## 五、关键设计决策

| 决策 | 说明 |
|------|------|
| 线程池异步 vs MQ | 单应用场景，线程池延迟更低 |
| Redis List 队列 | Agent 和 SSE 线程解耦，支持断线重连 |
| SIMPLE 黑名单过滤 | 只排除 RAG 和 deep_research，MCP 工具自动可见 |
| planOnce 三层容错 | JSON → 纯文本包装 → 纠错重试 |
| onCancel 不取消 ctx | SSE 断连只清理订阅，任务继续跑 |
| 历史保存前清洗 | 正则过滤协议标记和 JSON 残片 |
| Replanner 至少执行一半 | `i >= n/2` 才允许 FINISH，防止草率结束 |
| MCP 自动发现 | 启动时 tools/list → 自动注册，零代码加工具 |
| 双缓冲 Markdown 渲染 | 语法完整才渲染，不完整尾部纯文本显示 |

---

## 六、项目文件清单

| 类别 | 文件 | 说明 |
|------|------|------|
| **Agent** | `UnifiedAgentService.java` | SIMPLE/DEEP 分发 + prompt 构建 |
| | `AgentExecutor.java` | 双模式引擎 + 工具调用 + planOnce 容错 + LLM 空安全 |
| | `AgentContext.java` | 消息列表 + 取消标志 + 计数器 |
| | `ReactProtocol.java` | SSE 事件协议 |
| | `ToolCaller.java` | 工具调用器 |
| | `ToolRegistry.java` | 工具注册表 |
| | `ToolConfig.java` | 内置工具 + MCP 自动发现 |
| | `AgentToolPlannerService.java` | deep_research LLM 子 Agent |
| **MCP** | `McpClientManager.java` | 多 MCP Server 管理 + tools/list 自动发现 |
| | `McpClient.java` | JSON-RPC over stdio 单连接 |
| **AI 服务** | `MultimodalAIServiceImpl.java` | 图片(DashScope 异步)/语音/流式聊天 |
| | `IntentRecognitionService.java` | 意图三分类 + 复杂度判断 |
| | `AiTaskExecutor.java` | 线程池异步编排 + Redis/DB 双写 + 历史清洗 |
| | `McpSearchService.java` | 博查联网搜索 |
| **RAG** | `VectorStore.java` | 余弦相似度 Top-K 检索引擎 |
| | `DocumentService.java` | 分片 + Embedding + 检索（含相似度阈值） |
| | `MilvusConfig.java` | Milvus 连接（条件启用） |
| **控制器** | `AiClientController.java` | /chat /stream /result /sessions /admin/* |
| | `UserController.java` | 登录/注册 |
| **限流/鉴权** | `RateLimitAspect.java`, `SaTokenConfigure.java` | Redisson + Sa-Token |
| **前端** | `static/index.html` | Vue3 单页（暗色主题 + 粒子 + 双缓冲渲染 + 管理面板） |
| **配置** | `application.yaml` | 全部中间件 + 模型 + MCP + 博查配置 |
