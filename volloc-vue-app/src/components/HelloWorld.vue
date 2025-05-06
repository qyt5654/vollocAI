<template>
  <div class="container">
    <div class="form-box">
      <h2>{{ isLogin ? '登录' : '注册' }}</h2>
      <form @submit.prevent="handleSubmit">
        <input v-model="form.username" type="text" placeholder="请输入账号" required />
        <input v-model="form.password" type="password" placeholder="请输入密码" required />
        <input
          v-if="!isLogin"
          v-model="form.confirmPassword"
          type="password"
          placeholder="请确认密码"
          required
        />
        <button type="submit">{{ isLogin ? '登录' : '注册' }}</button>
      </form>
      <div class="toggle" @click="toggleForm">
        {{ isLogin ? '切换为注册' : '切换为登录' }}
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import axios from 'axios'
import { useRouter } from 'vue-router'

const router = useRouter()
const isLogin = ref(true)
const form = reactive({
  username: '',
  password: '',
  confirmPassword: ''
})

function toggleForm() {
  isLogin.value = !isLogin.value
  form.username = ''
  form.password = ''
  form.confirmPassword = ''
}

async function handleSubmit() {
    try {
      if (isLogin.value) {
        const res = await axios.post('http://127.0.0.1:8001/user/doLogin', {
          username: form.username,
          password: form.password
        })
        localStorage.setItem('satoken', res.data.data.tokenValue)
        axios.defaults.withCredentials = true;
        if (res.data.success && res.data.data.tokenValue) {
          axios.defaults.headers.common["satoken"] = res.data.data.tokenValue
          router.push('/AiModel')
        } else {
          alert(res.data.message || '登录失败')
        }
      } else {
        if (form.password !== form.confirmPassword) {
          alert('两次输入的密码不一致')
          return
        }
        const res = await axios.post('http://127.0.0.1:8001/user/doRegister', {
          username: form.username,
          password: form.password
        })
        if (res.data.success) {
          alert('注册成功')
          isLogin.value = true
          router.push('/')
        } else {
          alert(res.data.message || '注册失败')
        }
      }
    } catch (error) {
      alert('请求失败: ' + error.message)
    }
  }
</script>

<style scoped>
.container {
  height: 100vh;
  background: linear-gradient(145deg, #cfcfcf, #e6e6e6);
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
}

.form-box {
  background: #f5f5f5;
  padding: 2.5rem 2rem;
  border-radius: 12px;
  width: 350px;
  box-shadow: 0 8px 16px rgba(0, 0, 0, 0.2);
  transition: 0.3s ease;
}

.form-box h2 {
  text-align: center;
  margin-bottom: 1.5rem;
  color: #444;
}

.form-box form {
  display: flex;
  flex-direction: column;
}

.form-box input {
  margin-bottom: 1rem;
  padding: 0.7rem 1rem;
  border: 1px solid #ccc;
  border-radius: 6px;
  transition: 0.2s;
  font-size: 1rem;
}

.form-box input:focus {
  outline: none;
  border-color: #888;
  background-color: #f9f9f9;
}

.form-box button {
  background-color: #666;
  color: white;
  padding: 0.7rem;
  font-size: 1rem;
  border: none;
  border-radius: 6px;
  cursor: pointer;
  transition: 0.2s;
}

.form-box button:hover {
  background-color: #444;
}

.toggle {
  text-align: right;
  margin-top: 1rem;
  color: #555;
  cursor: pointer;
  font-size: 0.9rem;
  user-select: none;
  transition: 0.2s;
}

.toggle:hover {
  color: #222;
  text-decoration: underline;
}
</style>