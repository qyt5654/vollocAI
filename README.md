# vollocAI 项目介绍

---

## 一、项目定位

**vollocAI** 是一个基于 Spring Boot 3 + Spring AI 的**智能 Agent 对话平台**。用户通过 Web 聊天界面与 AI 交互，系统自动进行意图识别、动态模型分配，并按照 SIMPLE（日常问答）或 DEEP（多步深度调查）两种模式执行任务，最终以 SSE 流式逐字推送回答。

核心设计理念：**模型凭证完全动态化**，所有 API Key / URL / Model 均从数据库实时读取，yml 配置文件中无任何硬编码密钥。

---

## 二、核心能力

| 能力 | 说明 |
|------|------|
| **双模式 Agent** | SIMPLE 模式（日常问答，LLM 逐轮 JSON 决策+工具调用）和 DEEP 模式（Supervisor 制定计划→Executor 多步分析→Replanner 反思→综合回答） |
| **意图识别** | LLM 自动判断用户输入为 text / image / voice，并决定需要 SIMPLE 还是 DEEP 处理 |
| **工具调用** | 内置计算器、日期查询、知识库搜索、联网搜索；支持通过 MCP 协议自动发现外部工具 |
| **多模态** | 支持 AI 图片生成（DashScope 通义万相）和语音合成（DashScope TTS） |
| **RAG 检索增强** | 向量存储支持内存余弦相似度引擎和 Milvus 双模，文档自动分块入库 |
| **会话记忆** | Redis + MySQL 双写，支持跨会话上下文连贯对话 |
| **SSE 流式推送** | 逐 token 实时推送，前端打字机效果，支持心跳保活 |

---

## 三、技术栈

| 层级 | 技术选型 |
|------|---------|
| **后端框架** | Spring Boot 3.4、Spring AI 1.0.0-M5 |
| **ORM** | MyBatis / MyBatis-Plus |
| **数据库** | MySQL 8（任务/用户/模型配置/会话持久化） |
| **缓存与队列** | Redis 7（会话历史/流式 token 队列/结果缓存）+ Redisson（分布式锁/限流） |
| **向量引擎** | 内存余弦相似度引擎 + Milvus 双模 |
| **AI 模型** | DeepSeek / DashScope / 任意 OpenAI 兼容 API |
| **鉴权** | Sa-Token（Token 认证 + RBAC 角色管理） |
| **外部工具** | MCP 协议（JSON-RPC over stdio，自动发现工具） |
| **前端** | Vue 3 CDN + marked.js + KaTeX（暗色主题 + 粒子背景） |

---

## 四、系统架构

### 4.1 整体请求流程

```
POST /ai/chat  (QuestionDTO)
  │
  ├─ Sa-Token 鉴权
  ├─ 创建 AiTask (status=PENDING)，写入 MySQL
  ├─ AiTaskExecutor.execute(taskId, query, userId, modelId, sessionId)
  │     │
  │     └─ [aiThreadPool 子线程异步执行]
  │          ├─ resolve(): 从 DB 查询模型凭证 (apiKey / URL / Model)
  │          ├─ IntentRecognitionService.recognize(query, model)
  │          │     └─ LLM 判定 → {"intent":"text|image|voice", "deep":true|false}
  │          │
  │          ├─ image → MultimodalAIService.generateImage()
  │          ├─ voice → MultimodalAIService.generateVoice()
  │          │
  │          └─ text → UnifiedAgentService.execute()
  │               ├─ SIMPLE: AgentExecutor 逐轮 JSON 决策 + 工具调用
  │               └─ DEEP:   Supervisor 计划 → Executor 分析 → Replanner 反思
  │                    │
  │                    └─ 每条 token → Redis List "stream:{taskId}:q"
  │
  └─ 同步返回 taskId 给客户端

GET /ai/stream/{taskId}  (SSE)
  └─ 后台线程 BLPOP Redis List → 逐 token 推送 SSE data: 事件
```

### 4.2 DEEP 模式详解

```
用户问题
  │
  ▼
① Supervisor（规划者）
  LLM 分析问题 → 输出 JSON: {"rationale":"分析","steps":["步骤1","步骤2",...]}
  │
  ▼
② Executor（执行者）× N 步
  对于每个步骤:
    ├─ AgentToolPlannerService.planAndExecute()
    │     └─ LLM 决定需要哪些工具 → 调用工具（RAG搜索/联网搜索/MCP工具）
    ├─ LLM 基于工具返回的证据完成本步分析
    └─ findings.add(本步结论)
  │
  ▼
③ Replanner（反思者）
  评估已有发现 → 决定 CONTINUE（继续）/ ADJUST（调整）/ FINISH（结束）
  │
  ▼
④ 最终回答
  综合所有 findings → 流式输出完整回答
```

**终止策略（对齐开源项目最佳实践）**:
- **硬上限**：达到最大步数（maxIter=15）强制结束
- **LLM 自判断**：Replanner 返回 FINISH 直接结束，不做二次校验
- **重复检测**：连续两步高度相似（>75%）或包含相同错误关键词时保险中断

### 4.3 SIMPLE 模式详解

```
LLM 逐轮输出 JSON 决策:
  {"type":"final", "output":"直接回答"}
  {"type":"tool", "name":"calculate", "input":"1+2*3"}
  │
  ├─ final → 流式输出回答 → 结束
  └─ tool  → ToolCaller → ToolRegistry 执行 → 结果注入上下文 → 继续下一轮
```

---

## 五、核心模块

### 5.1 代理引擎 (`ai.agent`)

| 类 | 职责 |
|----|------|
| `UnifiedAgentService` | 入口层，按 deep 参数分发 SIMPLE/DEEP，动态创建 ChatModel |
| `AgentExecutor` | 代理执行循环，SIMPLE 10 轮迭代 / DEEP 15 步深度调查 |
| `AgentToolPlannerService` | DEEP 模式工具规划器，LLM 决定当前步骤调用哪些工具 |
| `ToolRegistry` | 显式工具注册表，按模式过滤（ALL/SIMPLE/DEEP） |
| `ToolCaller` | 工具调用器，ForkJoinPool 异步执行 + 超时控制 |
| `ToolConfig` | 启动时注册内置工具 + 自动发现 MCP 外部工具 |
| `ReactProtocol` | `[[ACT]]`/`[[OBS]]`/`[[STATE]]`/`[[PLAN]]` 事件协议 |
| `AgentContext` | 线程安全的消息上下文 + 取消标志 |

### 5.2 模型管理 (`ai.model`)

| 类 | 职责 |
|----|------|
| `DatabaseAi` | AI 模型配置实体（apiKey / apiUrl / apiModel） |
| `ModelAssignmentDao` | 用户-模型分配关系（多对多） |
| `DatabaseAiService` | 模型配置 CRUD |

### 5.3 LLM 服务 (`ai.llm`)

| 类 | 职责 |
|----|------|
| `AiUtils` | ChatModel 工厂（按 key/url/model 动态创建 + LRU 缓存 32 个） |
| `IntentRecognitionService` | 意图识别：LLM 判定 text/image/voice + simple/deep |
| `MultimodalAIServiceImpl` | 图片生成（DashScope Wanx 异步+轮询）+ 语音合成（DashScope TTS） |
| `McpSearchService` | 博查联网搜索 |

### 5.4 RAG 检索 (`ai.rag`)

| 类 | 职责 |
|----|------|
| `VectorStore` | 内存向量存储，余弦相似度检索 |
| `DocumentService` | 文档分块入库 + RAG 搜索（Milvus 优先，内存兜底） |
| `MilvusConfig` | Milvus 向量数据库配置 |

### 5.5 MCP 外部工具 (`ai.mcp`)

| 类 | 职责 |
|----|------|
| `McpClient` | 单个 MCP Server 连接（JSON-RPC 2.0 over stdio） |
| `McpClientManager` | 管理多个 MCP Server 的生命周期 |
| `McpTool` | MCP 工具描述 + 执行器 |

### 5.6 基础设施 (`ai.infra` + `ai.task` + `ai.session`)

| 类 | 职责 |
|----|------|
| `AiTaskExecutor` | 异步任务编排：意图识别→模型凭证→Agent 执行→Redis+DB 双写 |
| `ChatSessionDao` | 会话持久化（MyBatis） |
| `RateLimitAspect` | AOP 限流（Redisson 分布式计数器） |
| `AiThreadPoolConfig` | 专用线程池（核心 20，最大 100，CallerRunsPolicy） |
| `SseController` | SSE 流式推送（BLPOP Redis List + 心跳） |
| `GlobalExceptionHandler` | 全局异常拦截 |

---

## 六、数据库设计

| 表 | 用途 | 关键字段 |
|----|------|---------|
| `ai_task` | AI 任务生命周期 | task_id, user_id, query, intent, result, status(PENDING/PROCESSING/COMPLETED/FAILED) |
| `user` | 用户认证 | username, password, role(admin/user) |
| `database_ai` | AI 模型凭证 | ai_api_key, ai_api_url, ai_api_model, user_id |
| `model_assignment` | 用户-模型分配 | model_id, user_id |
| `chat_session` | 会话持久化 | session_id, user_id, title, messages(JSON) |

---

## 七、设计决策

1. **模型凭证完全动态化**：所有 `ChatModel` 在运行时从 `database_ai` 表动态创建，yml 配置文件无任何硬编码 API Key。Spring 的 `DashScopeAutoConfiguration` 和 `OpenAiAutoConfiguration` 均被排除。

2. **无状态 ChatModel 传递**：`ChatModel` 通过方法参数显式传递（`AgentExecutor.run(m, ...)` → `planAndExecute(query, step, m)` → `recognize(query, m)`），无 ThreadLocal、无全局状态、无 Spring 单例 Bean。

3. **Redis 作为 SSE 缓冲区**：生产者（线程池）写 token 到 Redis List，消费者（SSE 连接）通过 BLPOP 拉取。解耦生产与消费，支持心跳保活和优雅降级。

4. **deep_research 不走 ToolRegistry**：DEEP 模式的核心调查工具由 `AgentExecutor` 直接调用 `AgentToolPlannerService.planAndExecute(query, step, m)`，避免 ThreadLocal 跨线程问题，保持架构纯净。

5. **终止策略对齐开源标准**：采用 LangChain / AutoGen / OpenHands 通用的「LLM 自判断 + 硬预算」模式，不使用自研置信度公式。

6. **会话双写**：Redis 提供低延迟（30 分钟 TTL），MySQL 提供持久化。首次查询时 Redis 未命中则从 MySQL 回填（Redisson 分布式锁保护）。

---

## 八、快速启动

### 环境要求
- JDK 17+
- MySQL 8.0
- Redis 7
- Milvus（可选，用于向量 RAG）
- Python 3（可选，用于 MCP 外部工具）

### 数据库初始化
```sql
-- 执行 src/main/resources/sql/ 下的建表脚本
-- 在 database_ai 表中插入你的模型凭证
INSERT INTO database_ai (ai_api_key, ai_api_url, ai_api_model, user_id)
VALUES ('sk-xxx', 'https://api.deepseek.com/v1', 'deepseek-chat', 1);

-- 在 model_assignment 表中分配模型给用户
INSERT INTO model_assignment (model_id, user_id) VALUES (1, 1);
```

### 启动
```bash
mvn spring-boot:run
# 服务运行在 http://localhost:8001
# 打开浏览器访问 http://localhost:8001
```

### 配置 MCP 外部工具（可选）
```yaml
mcp:
  servers: text:python3 /path/to/your-mcp-server.py
```
