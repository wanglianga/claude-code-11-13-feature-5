import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/', timeout: 15000 })

// HTTP Basic 凭据保存在 localStorage，刷新页面不丢登录态
http.interceptors.request.use((config) => {
  const raw = localStorage.getItem('rtbf-auth')
  if (raw) {
    const { username, password } = JSON.parse(raw)
    config.auth = { username, password }
  }
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    const status = error.response?.status
    const message = error.response?.data?.message || error.message
    if (status === 401) {
      localStorage.removeItem('rtbf-auth')
      if (location.hash !== '#/login') {
        location.hash = '#/login'
      }
    }
    ElMessage.error(`[${status || 'ERR'}] ${message}`)
    return Promise.reject(error)
  }
)

export default http
