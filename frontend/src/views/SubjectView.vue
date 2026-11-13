<template>
  <div>
    <h2 class="page-title">主体归并与逐条确认</h2>
    <el-card class="section-card">
      <el-alert type="warning" :closable="false"
        title="归并规则：仅按手机号/邮箱/证件号的哈希连通归并；姓名只用于展示，姓名相同不构成合并依据。合规专员须逐条确认标识符匹配后才能确认主体。"
        style="margin-bottom:12px" />
      <el-button type="primary" :loading="scanning" @click="scan">
        <el-icon><Refresh /></el-icon> 扫描账号并归并主体
      </el-button>
    </el-card>

    <el-card>
      <el-table :data="subjects" border stripe v-loading="loading" row-key="subject_hash">
        <el-table-column prop="display_name" label="展示姓名" width="110" />
        <el-table-column label="主体哈希" min-width="240">
          <template #default="{ row }"><span class="mono">{{ row.subject_hash }}</span></template>
        </el-table-column>
        <el-table-column prop="accounts" label="关联账号" width="180" />
        <el-table-column label="匹配项" width="120">
          <template #default="{ row }">
            共 {{ row.match_count }} 条 / 待决 {{ row.pending_count }} 条
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="openDetail(row.subject_hash)">查看/逐条确认</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="drawer" size="52%" :title="`主体详情 ${detail?.display_name ?? ''}`">
      <template v-if="detail">
        <el-descriptions :column="1" border style="margin-bottom:14px">
          <el-descriptions-item label="主体哈希"><span class="mono">{{ detail.subjectHash }}</span></el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.status)">{{ statusLabel(detail.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="确认人/时间">{{ detail.confirmedBy || '—' }} {{ detail.confirmedAt || '' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.identifierMatches" border size="small">
          <el-table-column label="类型" width="90">
            <template #default="{ row }">{{ idTypeLabel(row.id_type) }}</template>
          </el-table-column>
          <el-table-column prop="id_value_masked" label="脱敏标识值" width="170" />
          <el-table-column prop="source_account_ref" label="来源账号" width="120" />
          <el-table-column label="哈希" min-width="200">
            <template #default="{ row }"><span class="mono">{{ row.id_hash.substring(0, 24) }}…</span></template>
          </el-table-column>
          <el-table-column label="决定" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.match_status !== 'PENDING'" :type="row.match_status === 'ACCEPTED' ? 'success' : 'danger'">
                {{ row.match_status === 'ACCEPTED' ? '已接受' : '已驳回' }}
              </el-tag>
              <span v-else>待决定</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <template v-if="row.match_status === 'PENDING'">
                <el-button size="small" type="success" @click="decide(row.id, true)">接受</el-button>
                <el-button size="small" type="danger" @click="decide(row.id, false)">驳回</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
        <div style="margin-top:16px; display:flex; gap:10px">
          <el-button type="primary" :disabled="detail.status !== 'CANDIDATE'" @click="confirm">确认主体</el-button>
          <el-button type="danger" plain :disabled="detail.status === 'REJECTED'" @click="reject">驳回主体</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import http from '../api/http'
import { ElMessage, ElMessageBox } from 'element-plus'

const subjects = ref([])
const loading = ref(false)
const scanning = ref(false)
const drawer = ref(false)
const detail = ref(null)
let currentHash = ''

async function load() {
  loading.value = true
  try {
    subjects.value = (await http.get('/api/subjects')).data
  } finally {
    loading.value = false
  }
}
async function scan() {
  scanning.value = true
  try {
    await http.post('/api/subjects/scan')
    ElMessage.success('扫描归并完成')
    await load()
  } finally {
    scanning.value = false
  }
}
async function openDetail(hash) {
  currentHash = hash
  detail.value = (await http.get('/api/subjects/' + hash)).data
  drawer.value = true
}
async function decide(matchId, accept) {
  await http.post(`/api/identifier-matches/${matchId}/decide`, { accept })
  ElMessage.success(accept ? '已接受该匹配' : '已驳回该匹配')
  await openDetail(currentHash)
  await load()
}
async function confirm() {
  await http.post(`/api/subjects/${currentHash}/confirm`)
  ElMessage.success('主体已确认，可创建注销工单')
  await openDetail(currentHash)
  await load()
}
async function reject() {
  const { value } = await ElMessageBox.prompt('请输入驳回原因', '驳回主体', { inputType: 'textarea' })
  await http.post(`/api/subjects/${currentHash}/reject`, { reason: value || '合规驳回' })
  ElMessage.success('主体已驳回')
  await openDetail(currentHash)
  await load()
}
function statusType(s) {
  return { CONFIRMED: 'success', REJECTED: 'danger', MERGED: 'warning' }[s] || 'info'
}
function statusLabel(s) {
  return { CANDIDATE: '待确认', CONFIRMED: '已确认', REJECTED: '已驳回', MERGED: '已合并' }[s] || s
}
function idTypeLabel(t) {
  return { PHONE: '手机号', EMAIL: '邮箱', IDCARD: '证件号' }[t] || t
}
onMounted(load)
</script>
