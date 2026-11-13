<template>
  <!-- 候选人查询门户：独立全屏布局，不展示内部操作台侧边栏 -->
  <router-view v-if="$route.name === 'portal'" />
  <el-container v-else style="min-height: 100vh">
    <el-aside width="230px" class="aside">
      <div class="logo">被遗忘权<br />合规操作台</div>
      <el-menu :default-active="$route.path" router background-color="#1f2d3d" text-color="#c0ccda" active-text-color="#409eff">
        <el-menu-item index="/subjects"><el-icon><User /></el-icon><span>主体归并确认</span></el-menu-item>
        <el-menu-item index="/tickets"><el-icon><Tickets /></el-icon><span>注销工单</span></el-menu-item>
        <el-menu-item index="/identity"><el-icon><Connection /></el-icon><span>身份错配复核</span></el-menu-item>
        <el-menu-item index="/replies"><el-icon><ChatDotRound /></el-icon><span>对外答复</span></el-menu-item>
        <el-menu-item index="/lineage"><el-icon><Share /></el-icon><span>资产与血缘</span></el-menu-item>
      </el-menu>
      <div class="aside-footer">
        <el-tag size="small" type="info">{{ auth?.username }}（{{ roleLabel }}）</el-tag>
        <el-button link type="warning" size="small" @click="logout">退出登录</el-button>
      </div>
    </el-aside>
    <el-main>
      <router-view />
    </el-main>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const auth = computed(() => {
  const raw = localStorage.getItem('rtbf-auth')
  return raw ? JSON.parse(raw) : null
})
const roleLabel = computed(() => {
  const u = auth.value?.username
  return u === 'compliance' ? '合规专员' : u === 'operator' ? '运维操作员' : u === 'viewer' ? '只读审计' : '未登录'
})
function logout() {
  localStorage.removeItem('rtbf-auth')
  router.push('/login')
}
</script>

<style>
body { margin: 0; font-family: 'Helvetica Neue', Arial, 'PingFang SC', 'Microsoft YaHei', sans-serif; background: #f0f2f5; }
.aside { background: #1f2d3d; color: #fff; display: flex; flex-direction: column; }
.logo { padding: 20px 16px; font-size: 17px; font-weight: 700; line-height: 1.5; color: #fff; border-bottom: 1px solid #2e3f52; }
.aside .el-menu { border-right: none; flex: 1; }
.aside-footer { padding: 14px; display: flex; flex-direction: column; gap: 8px; border-top: 1px solid #2e3f52; }
.page-title { font-size: 20px; font-weight: 700; margin: 0 0 16px; }
.section-card { margin-bottom: 16px; }
.mono { font-family: ui-monospace, 'SFMono-Regular', Menlo, monospace; font-size: 12px; word-break: break-all; }
</style>
