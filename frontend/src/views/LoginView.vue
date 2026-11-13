<template>
  <div class="login-wrap">
    <el-card class="login-card">
      <h2 class="page-title" style="text-align:center">候选人注销合规操作台</h2>
      <el-form label-position="top">
        <el-form-item label="用户名">
          <el-select v-model="form.username" style="width:100%">
            <el-option label="compliance（合规专员）" value="compliance" />
            <el-option label="operator（运维操作员）" value="operator" />
            <el-option label="viewer（只读审计）" value="viewer" />
          </el-select>
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="form.password" type="password" show-password @keyup.enter="login" />
        </el-form-item>
        <el-button type="primary" style="width:100%" @click="login">登录</el-button>
      </el-form>
      <el-alert type="info" :closable="false" style="margin-top:12px"
        title="演示账号：compliance / Compliance@2026、operator / Operator@2026、viewer / Viewer@2026" />
    </el-card>
  </div>
</template>

<script setup>
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import { ElMessage } from 'element-plus'

const router = useRouter()
const form = reactive({ username: 'compliance', password: 'Compliance@2026' })

async function login() {
  localStorage.setItem('rtbf-auth', JSON.stringify({ ...form }))
  try {
    await http.get('/api/subjects')
    ElMessage.success('登录成功')
    router.push('/subjects')
  } catch {
    localStorage.removeItem('rtbf-auth')
  }
}
</script>

<style scoped>
.login-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #1f2d3d; }
.login-card { width: 440px; }
</style>
