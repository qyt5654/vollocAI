# vollocAI 接口文档

Base URL: `http://localhost:8001`

---

## 1. AI 对话（异步）

**POST** `/ai/chat`

提交任务 → 落库 PENDING → 发送 RocketMQ → 返回 taskId。前端轮询 `/ai/result/{taskId}` 获取结果。

**Request Body:**
```json
{
  "question": "画一只猫",
  "id": 1
}
```
| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| question | String | 是 | 用户输入（文本/图片/语音需求均可） |
| id | Long | 是 | 模型配置 ID |

**Response:**
```json
{
  "success": true,
  "code": 200,
  "data": "uuid-task-id"
}
```

限流：单用户每分钟 5 次。

---

## 2. 查询任务结果

**GET** `/ai/result/{taskId}`

优先 Redis，未命中降级查 DB。

**Headers:** `satoken: xxx`

**Response:**
```json
// 已完成
{ "success": true, "code": 200, "data": "AI 生成的内容或 URL" }

// 处理中
{ "success": true, "code": 200, "data": null }

// 失败
{ "success": false, "code": 500, "message": "任务执行失败", "data": null }
```

---

## 3. 查询用户模型列表

**GET** `/ai/selectModelByUserId`

**Headers:** `satoken: xxx`

**Response:**
```json
{
  "success": true,
  "code": 200,
  "data": [
    { "id": 1, "aiApiModel": "qwen3-max" },
    { "id": 2, "aiApiModel": "wanx-v1" }
  ]
}
```

---

## 4. 添加模型配置

**POST** `/ai/addModel`

管理员权限。

**Request Body:**
```json
{
  "aiApiKey": "sk-xxx",
  "aiApiUrl": "https://api.example.com/v1",
  "aiApiModel": "gpt-4",
  "userId": 1
}
```

---

## 5. 用户登录

**POST** `/user/doLogin`

**Request Body (form-urlencoded):** `username=admin&password=123456`

**Response:**
```json
{ "success": true, "code": 200, "data": { "tokenName": "satoken", "tokenValue": "xxx" } }
```

---

## 6. 用户注册

**POST** `/user/doRegister`

**Request Body:** 同登录

---

## 通用响应格式

```json
{ "success": true, "code": 200, "message": "success", "data": "..." }
```

## 错误码

| code | 说明 |
|------|------|
| 200 | 成功 |
| 429 | 限流（每分钟 5 次） |
| 500 | 服务端错误 |

## 全链路异步流程

```
POST /ai/chat
      │
      ├─ 落库 ai_task (PENDING)
      ├─ 发送 RocketMQ (ai-task-topic)
      └─ 返回 taskId
            │
            ▼
      MQ Consumer 消费
            │
            ├─ 更新 PROCESSING
            ├─ 意图识别 (text/image/voice)
            ├─ 路由执行
            ├─ Redis 写入 (TTL 10min)
            └─ DB 更新 (COMPLETED / FAILED)
                  │
                  ▼
      GET /ai/result/{taskId}
            │
            ├─ Redis 命中 → 直接返回
            └─ Redis 未命中 → DB 查询兜底

兜底机制：
  TaskCompensationScheduler (每30s)
    ├─ PENDING 超时 60s → 重投 MQ
    └─ PROCESSING 超时 120s → 重投 MQ
```
