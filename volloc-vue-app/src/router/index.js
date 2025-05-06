import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  // 添加你的路由配置
  {
    path: '/AiModel',
    name: 'AiModel',
    component: () => import('@/components/AiModel.vue') // 确保这个组件存在
  },
  {
    path: '/',
    name: 'HelloWorld',
    component: () => import('@/components/HelloWorld.vue') // 你的登录组件
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router // 必须导出