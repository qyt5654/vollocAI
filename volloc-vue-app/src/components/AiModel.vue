<template>
    <div class="chat-container">
      <div class="action-header">
        <div class="model-select">
          <label for="model">选择模型：</label>
          <select v-model="selectedModelId">
            <option v-for="model in models" :key="model.id" :value="model.id">
              {{ model.aiApiModel }}
            </option>
          </select>
        </div>
        <button class="image-button" @click="generateImage" :disabled="loading || !question.trim()">
          生成图片
        </button>
      </div>
      <div class="chat-content" ref="chatContentRef">
        <div v-for="(msg, index) in messages" :key="index" class="message">
          <p class="user-msg" v-if="msg.role === 'user'">🙋‍♂️：{{ msg.content }}</p>
          <p class="ai-msg" v-else-if="msg.role === 'ai'">🤖：{{ msg.content }}</p>
          <div class="image-msg" v-else-if="msg.role === 'image'">
            <p>🖼️ 生成的图片：</p>
            <img :src="msg.content" alt="Generated image" />
          </div>
        </div>
        <div v-if="loading" class="loading">AI 正在思考中...</div>
      </div>
      <div class="input-area">
        <input
          v-model="question"
          class="input-box"
          :placeholder="'可询问任何问题'"
          @keyup.enter="sendMessage"
        />
        <button
          class="send-button"
          :disabled="!question.trim() || loading"
          @click="sendMessage"
        >
          <svg viewBox="0 0 24 24" fill="none" width="20" height="20">
            <path
              d="M2 21L23 12L2 3V10L17 12L2 14V21Z"
              fill="currentColor"
            />
          </svg>
        </button>
      </div>
    </div>
  </template>
     
  <script setup>
  import { ref, onMounted, nextTick } from 'vue'
  import axios from 'axios'
     
  const question = ref('')
  const loading = ref(false)
  const messages = ref([])
  const models = ref([])
  const selectedModelId = ref(null)
  const chatContentRef = ref(null)
     
  nextTick(() => {
    // 增加一些等待时间以确保消息完全渲染
    setTimeout(() => {
        if (chatContentRef.value) {
        chatContentRef.value.scrollTop = chatContentRef.value.scrollHeight
        }
    }, 50); // 等待 50 毫秒
    })
   
  onMounted(async () => {
    try {
        const token = localStorage.getItem('satoken');
        if (token) {
            axios.defaults.headers.common["satoken"] = token;
            axios.defaults.headers.common["Content-Type"] = "application/json";
            console.log("Token set:", token);
            console.log(axios.defaults.headers.common);
        }
      const res = await axios.get('http://127.0.0.1:8001/ai/selectModelByUserId')
      if (res.data.success) {
        models.value = res.data.data
        if (models.value.length > 0) {
          selectedModelId.value = models.value[0].id
        }
      }
    } catch (e) {
      alert('模型获取失败')
    }
  })

  async function sendMessage() {
    if (!question.value.trim() || !selectedModelId.value) return

    const userMsg = question.value.trim()
    messages.value.push({ role: 'user', content: userMsg })
    question.value = ''
    loading.value = true

    try {
      const res = await axios.post('http://127.0.0.1:8001/ai/ask', {
        question: userMsg,
        id: selectedModelId.value
      })

      const result = res.data

      if (result.code === 200) {
        const taskId = result.data
        messages.value.push({ role: 'ai', content: '🤖 AI 正在思考，请稍候...' })

        // 每 2 秒轮询一次结果（或使用 WebSocket 替代）
        const interval = setInterval(async () => {
          console.log(taskId)
          const pollRes = await axios.get(`http://127.0.0.1:8001/ai/${taskId}`)
          const data = pollRes.data
          console.log(data)
          if (data.code === 200 && data.data != null) {
            messages.value.pop() // 删除“正在思考”
            messages.value.push({ role: 'ai', content: data.data })
            clearInterval(interval)
          } else if (data.code !== 200) {
            messages.value.pop()
            messages.value.push({ role: 'ai', content: '❌ AI 回复失败，请稍后重试' })
            clearInterval(interval)
          }
        }, 2000)
      } else {
        messages.value.push({
          role: 'ai',
          content: `❌ 出错：${result.message || 'AI 无法回复'}`
        })
      }
    } catch (err) {
      messages.value.push({
        role: 'ai',
        content: `❌ 请求异常：${err.response?.data?.message || err.message}`
      })
    } finally {
      loading.value = false
    }
  }
  
  async function generateImage() {
    if (!question.value.trim() || !selectedModelId.value) return
       
    const userMsg = question.value.trim()
    messages.value.push({ role: 'user', content: `生成图片: ${userMsg}` })
    question.value = ''
    loading.value = true
       
    try {
      const res = await axios.post('http://127.0.0.1:8001/ai/askImg', {
        question: userMsg,
        id: selectedModelId.value
      })
      if (res.data.code === 200) {
        const taskId = res.data.data
        messages.value.push({ role: 'ai', content: '🖼️ 正在生成图片，请稍候...' })

        const interval = setInterval(async () => {
          const pollRes = await axios.get(`http://127.0.0.1:8001/ai/${taskId}`)
          const data = pollRes.data
          if (data.code === 200 && data.data != null) {
            messages.value.pop()
            messages.value.push({ role: 'image', content: data.data }) // 图片 URL 或 base64
            clearInterval(interval)
          } else if (data.code !== 200) {
            messages.value.pop()
            messages.value.push({ role: 'ai', content: '❌ 图片生成失败，请稍后重试' })
            clearInterval(interval)
          }
        }, 2000)
      }
      // if (res.data.success) {
      //   messages.value.push({ role: 'image', content: res.data.data })
      // } else {
      //   messages.value.push({ role: 'ai', content: '图片生成失败，请稍后再试。' })
      // }
    } catch (err) {
      messages.value.push({ role: 'ai', content: '请求失败：' + err.message })
    } finally {
      loading.value = false
      nextTick(() => {
        chatContentRef.value.scrollTop = chatContentRef.value.scrollHeight
      })
    }
  }
  </script>
     
  <style scoped>
  .chat-container {
    background: #1e1e1e;
    color: #e0e0e0;
    height: 100vh;
    display: flex;
    flex-direction: column;
    font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
  }
  .action-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 1rem;
    background: #2b2b2b;
    border-bottom: 1px solid #444;
  }
  .model-select {
    display: flex;
    align-items: center;
  }
  .model-select select {
    background: #3a3a3a;
    border: none;
    padding: 0.4rem 0.6rem;
    color: #fff;
    border-radius: 8px;
    margin-left: 0.5rem;
  }
  .image-button {
    padding: 0.5rem 1rem;
    background: #4a4a4a;
    color: white;
    border: none;
    border-radius: 8px;
    cursor: pointer;
    transition: background 0.3s;
  }
  .image-button:hover {
    background: #5a5a5a;
  }
  .image-button:disabled {
    background: #777;
    cursor: not-allowed;
  }
  .chat-content {
    flex: 1;
    overflow-y: auto;
    padding: 1rem 20%;
    scroll-behavior: smooth;
  }
  .message {
    margin-bottom: 1rem;
  }
  .user-msg, .ai-msg {
    background: none;
    margin: 0;
    line-height: 1.5;
    word-break: break-word;
  }
  .image-msg {
    margin: 0;
    line-height: 1.5;
  }
  .image-msg img {
    max-width: 100%;
    border-radius: 8px;
    margin-top: 0.5rem;
  }
  .loading {
    text-align: center;
    color: #aaa;
  }
  .input-area {
    display: flex;
    align-items: center;
    padding: 1rem;
    border-top: 1px solid #444;
    background: #2a2a2a;
    position: sticky;
    bottom: 0;
  }
  .input-box {
    flex: 1;
    padding: 0.8rem 1rem;
    background: #3b3b3b;
    border: none;
    border-radius: 20px;
    color: #fff;
    font-size: 1rem;
    margin-right: 0.5rem;
  }
  .input-box::placeholder {
    color: #888;
  }
  .send-button {
    width: 42px;
    height: 42px;
    border-radius: 50%;
    border: none;
    background: #555;
    color: white;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: background 0.3s;
    cursor: pointer;
  }
  .send-button:disabled {
    background: #777;
    cursor: not-allowed;
  }
  .send-button svg {
    transform: translateX(1px);
  }
  </style>