# vollocAI 项目技术报告

---

## 一、项目概述

基于 Spring Boot 3 + Spring AI + Vue 3 的智能对话平台。支持 SIMPLE（日常问答）和 DEEP（多步 RAG 检索+工具协作）双模式 Agent，SSE 流式推送逐字渲染，Redis+MySQL 双写会话记忆。

---

## 二、技术栈

| 层级 | 技术 |
|------|------|
| 框架 | Spring Boot 3.4 + MyBatis |
| AI | Spring AI 1.0.0-M5 + DashScope / DeepSeek / OpenAI 兼容 |
| 缓存 | Redis 7 (会话/流式队列/结果缓存) |
| 数据库 | MySQL 8 (任务/用户/模型配置/会话持久化) |
| 向量 | 余弦相似度内存引擎 + Milvus (1536维 IVF_FLAT) 双模 |
| 鉴权 | Sa-Token (Token + RBAC) |
| 前端 | Vue 3 CDN + marked.js + KaTeX (Markdown+数学公式渲染) |

---

## 三、系统架构

```
POST /ai/chat
    │
    ├─ 落库 PENDING + 线程池提交 + 返回 taskId (毫秒级)
    │
    ├─ EventSource("GET /ai/stream/{taskId}")  ← SSE 长连接
    │
    └─ AiTaskExecutor (aiPool 子线程，异步)
         ├─ 意图识别 (text/image/voice → LLM 分类)
         ├─ 模型凭证解析 (database_ai → apiKey/URL/Model)
         ├─ 会话历史加载 (Redis → MySQL)
         │
         ├─ image/voice → MultimodalAIService 生成
         │
         └─ text → UnifiedAgentService
              ├─ deep=false → SIMPLE (逐轮 JSON 决策 + 工具调用)
              └─ deep=true  → DEEP   (Supervisor计划→Executor分析→Replanner反思→结论)
                   │
         ┌────────┴────────┐
         ▼                 ▼
    Redis List           Redis "ai:result:{id}"
    BLPOP 逐 token       (轮询兜底)
         │
         ▼
    AiClientController.streamResult()
    emitter.send(token) → SSE → 前端 EventSource → fullText累加 → marked.parse()渲染
```

---

## 四、核心模块

### 4.1 意图识别

一次 LLM 调用完成 text/image/voice 三分类 + 复杂度判断(deep)。

```
输入: 用户原始文本
输出: {"intent":"text|image|voice", "content":"原文", "deep":true|false}

deep=true: 需要多步分析才能回答的复杂问题
deep=false: 可直接回答的简单问答（绝大多数日常提问）
```

**关键文件**: `IntentRecognitionService.java`

### 4.2 统一 Agent 服务 (UnifiedAgentService)

入口层，按意图分发 SIMPLE/DEEP，构建初始上下文。

**SIMPLE 模式**（日常问答）:
- 上下文: [SystemMessage(规划指令), 历史, UserMessage(问题), SystemMessage(回答风格)]
- LLM 逐轮输出 JSON: `{"type":"final"}` 或 `{"type":"tool","name":"x","input":"y"}`
- 可用工具仅: getCurrentDateTime + calculate（不暴露RAG，保持通用性）
- 回答风格: 智能助手，全面有深度

**DEEP 模式**（复杂调查）:
- Supervisor LLM 制定分步计划 → `{"rationale":"...", "steps":["步骤1","步骤2"]}`
- 逐步骤: deep_research工具收集证据 → Executor分析 → Replanner反思调整
- 全量工具可用 (含 RAG queryInternalDocs)
- 步骤中间结果仅入工具链面板，正文只显示最终结论

**关键文件**: `UnifiedAgentService.java`, `AgentExecutor.java`

### 4.3 Agent 执行器 (AgentExecutor)

双模式核心引擎，通过 `steps` 参数区分模式 (null=SIMPLE, 非null=DEEP)。

**SIMPLE 流程**:
```
planOnce(LLM) → 收集输出 → extractJson → 
  type=final → output非空? 直接push : 构建干净prompt流式回答
  type=tool  → execTool → 结果注入ctx → 继续循环
```

**DEEP 流程**:
```
for each step:
  execTool("deep_research") → Executor LLM分析 → findings累积
  Replanner: CONTINUE/ADJUST/FINISH
buildDeepFinalPrompt → 流式生成最终结论
```

**planOnce 三层容错**:
1. LLM输出有效JSON → 直接解析
2. LLM输出纯文本（问候等） → 包装为 `{"type":"final","output":"..."}`
3. 输出为空 → 注入纠错 → 重试一次

**关键文件**: `AgentExecutor.java`

### 4.4 RAG 检索增强生成

双引擎架构，上层接口统一:

| 模式 | 引擎 | 说明 |
|------|------|------|
| 生产 | Milvus 1536维 IVF_FLAT + IP度量 | Docker部署 |
| 降级 | VectorStore 余弦相似度 + Top-K堆排 | 零依赖，自动切换 |

流程: 文档分片(≤500字,50字overlap,句号处断句) → Embedding → Milvus/内存 → Top-3检索 → 相似度阈值0.75过滤 → 注入Prompt。

检索为空时直接告知LLM"知识库未找到相关资料"，防止幻觉编造。

**关键文件**: `VectorStore.java`, `DocumentService.java`, `MilvusConfig.java`

### 4.5 SSE Token级流式推送

完整链路:
```
LLM.stream() → Flux<String> → FluxSink.next(char) → AiTaskExecutor.doOnNext
  → redis.rightPush("stream:{taskId}:q", char)
    → AiClientController BLPOP → emitter.send(data:char) → SSE
      → 前端 EventSource.onmessage → fullText += ch → renderMd(fullText) → v-html渲染
```

关键设计:
- **Redis解耦**: Agent线程(写)与SSE线程(读)通过Redis List异步通信
- **BLPOP阻塞消费**: token一到立即推送，无空轮询CPU开销
- **心跳保活**: 每3秒空data事件，防浏览器45秒超时
- **\n编码**: 独立换行token用`<NL>`编码→前端解码，解决SSE协议吞换行导致Markdown标题不被marked识别的问题
- **onCancel不取消任务**: SSE断连只dispose当前LLM订阅，任务继续跑，前端重连后从Redis续读

**关键文件**: `AiClientController.java`, `AiTaskExecutor.java`

### 4.6 工具调用

3个真实工具 + 1个编排工具，显式注册:

| 工具 | 功能 | 说明 |
|------|------|------|
| `getCurrentDateTime` | 时间感知 | 解决LLM时间失忆 |
| `calculate` | 数学计算 | 递归下降解析器 |
| `queryInternalDocs` | RAG检索 | 搜索项目知识库 |
| `deep_research` | 多步调查 | DEEP模式专用，LLM子Agent编排工具调用 |

SIMPLE模式仅暴露前2个基础工具，DEEP模式全量可用。

**关键文件**: `ToolConfig.java`, `ToolRegistry.java`, `ToolCaller.java`

### 4.7 会话记忆

| 层级 | 存储 | TTL | 用途 |
|------|------|-----|------|
| 短期 | Redis `session:{id}:history` | 30min | 热缓存 |
| 长期 | MySQL `chat_session` | 永久 | 持久化，退出后可找回 |

保存前清洗协议标记和JSON决策残片，防止污染历史。

**关键文件**: `AiTaskExecutor.loadHistory()/saveHistory()`

### 4.8 SSE事件协议 (ReactProtocol)

前端工具链可视化面板通过结构化事件展示Agent执行过程:

| 事件 | 含义 | 前端展示 |
|------|------|----------|
| `[[STATE]]` | 状态变更(planning/answering/error) | 状态标签 |
| `[[ACT]]` | 工具调用开始 | "工具调用"徽标 |
| `[[OBS]]` | 工具调用结果 | "调用结果"徽标 |
| `[[PLAN]]` | 规划token增量 | 入planBuf，不显示 |

普通文本token逐字符推送，前端累加为fullText后Markdown渲染。

**关键文件**: `ReactProtocol.java`

---

## 五、关键设计决策

| 决策 | 说明 |
|------|------|
| 线程池异步 vs MQ | 单应用场景，线程池延迟更低，无额外运维 |
| Redis List队列 | Agent线程和SSE线程解耦，支持前端断线重连 |
| SIMPLE工具白名单 | 只暴露计算器+时间，保持回答通用性，不绑定项目上下文 |
| planOnce三层容错 | JSON→纯文本包装→纠错重试，保证LLM输出不稳定时系统不崩溃 |
| onCancel不取消ctx | SSE断连只清理订阅，任务继续跑，前端重连无数据丢失 |
| 历史保存前清洗 | 正则过滤[[协议标记]]和JSON决策残片，防止污染多轮对话 |

---

## 六、项目文件清单

| 类别 | 文件 | 说明 |
|------|------|------|
| **Agent** | `UnifiedAgentService.java` | 入口: SIMPLE/DEEP分发 + prompt构建 |
| | `AgentExecutor.java` | 核心引擎: 双模式循环 + 工具调用 + planOnce容错 |
| | `AgentContext.java` | 上下文容器: 消息列表+取消标志+计数器 |
| | `ReactProtocol.java` | SSE事件协议: [[STATE]]/[[ACT]]/[[OBS]]/[[PLAN]] |
| | `ToolCaller.java` | 工具调用器: [result, error, elapsedMs] |
| | `ToolRegistry.java` | 工具注册表 |
| | `ToolConfig.java` | 工具定义与注册(3真实+1编排) |
| | `AgentToolPlannerService.java` | deep_research LLM子Agent编排 |
| **AI服务** | `MultimodalAIServiceImpl.java` | 图片/语音/流式聊天 |
| | `IntentRecognitionService.java` | 意图三分类(text/image/voice)+复杂度(deep) |
| | `AiTaskExecutor.java` | 线程池异步编排+Redis/DB双写+历史管理 |
| **RAG** | `VectorStore.java` | 内存余弦相似度Top-K检索引擎 |
| | `DocumentService.java` | 分片+Embedding+检索统一接口(含相似度阈值) |
| | `MilvusConfig.java` | Milvus连接(条件启用) |
| **控制器** | `AiClientController.java` | /chat /stream /result /sessions /admin/* |
| | `UserController.java` | 登录/注册 |
| **限流** | `RateLimitAspect.java` + `@RateLimit` | Redisson分布式限流 |
| **鉴权** | `SaTokenConfigure.java` + `LoginInterceptor.java` | Sa-Token拦截 |
| **实体/DAO** | `AiTask.java`, `ChatSession.java`, `DatabaseAi.java` 等 | 核心实体+MyBatis映射 |
| **前端** | `static/index.html` | Vue3单页(SSE流式+Markdown+KaTeX+工具链面板+管理) |
| **配置** | `application.yaml` | 全部中间件+模型配置 |
