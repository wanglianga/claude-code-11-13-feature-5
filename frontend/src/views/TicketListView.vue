<template>
  <div>
    <h2 class="page-title">注销工单</h2>
    <el-card class="section-card">
      <el-form inline>
        <el-form-item label="已确认主体">
          <el-select v-model="createForm.subjectHash" filterable placeholder="选择主体哈希" style="width:380px">
            <el-option v-for="s in confirmedSubjects" :key="s.subject_hash"
              :label="`${s.display_name} | ${s.subject_hash.substring(0, 16)}…`" :value="s.subject_hash" />
          </el-select>
        </el-form-item>
        <el-form-item label="注销渠道">
          <el-select v-model="createForm.channel" style="width:170px">
            <el-option v-for="c in channels" :key="c.value" :label="c.label" :value="c.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源单号"><el-input v-model="createForm.sourceRef" placeholder="问询函编号等" /></el-form-item>
        <el-form-item>
          <el-button type="primary" @click="create">创建工单（幂等并单）</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card>
      <div style="margin-bottom:10px">
        <el-radio-group v-model="statusFilter" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button v-for="s in statuses" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
        </el-radio-group>
      </div>
      <el-table :data="tickets" border stripe v-loading="loading">
        <el-table-column prop="ticket_no" label="工单号" width="200" />
        <el-table-column label="主体哈希" min-width="180">
          <template #default="{ row }"><span class="mono">{{ row.subject_hash.substring(0, 20) }}…</span></template>
        </el-table-column>
        <el-table-column prop="channel" label="渠道" width="150" />
        <el-table-column label="状态" width="140">
          <template #default="{ row }"><el-tag :type="ticketStatusType(row.status)">{{ ticketStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="当前节点" width="110">
          <template #default="{ row }">{{ nodeLabel(row.current_node) }}</template>
        </el-table-column>
        <el-table-column prop="legal_deadline" label="法定到期日" width="130" />
        <el-table-column prop="created_at" label="创建时间" min-width="170" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="$router.push(`/tickets/${row.id}`)">处置台</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import http from '../api/http'
import { ElMessage } from 'element-plus'

const channels = [
  { value: 'APP', label: 'APP' },
  { value: 'WEB', label: 'WEB' },
  { value: 'HOTLINE', label: '客服热线' },
  { value: 'REGULATOR_LETTER', label: '监管问询函' },
  { value: 'EMAIL', label: '邮件' }
]
const statuses = [
  { value: 'CREATED', label: '待枚举' },
  { value: 'ENUM_FROZEN', label: '枚举已冻结' },
  { value: 'PENDING_EXECUTION', label: '待执行(可撤回)' },
  { value: 'EXECUTING', label: '执行中' },
  { value: 'WAITING_RECEIPT', label: '等待回执' },
  { value: 'RECONCILIATION', label: '对账中' },
  { value: 'COMPLETED', label: '已完成' },
  { value: 'CANCELLED', label: '已撤回' },
  { value: 'RETURNED', label: '已退回' }
]
const createForm = reactive({ subjectHash: '', channel: 'REGULATOR_LETTER', sourceRef: '' })
const confirmedSubjects = ref([])
const tickets = ref([])
const statusFilter = ref('')
const loading = ref(false)

async function loadSubjects() {
  const all = (await http.get('/api/subjects')).data
  confirmedSubjects.value = all.filter((s) => s.status === 'CONFIRMED')
}
async function load() {
  loading.value = true
  try {
    const url = statusFilter.value ? `/api/tickets?status=${statusFilter.value}` : '/api/tickets'
    tickets.value = (await http.get(url)).data
  } finally {
    loading.value = false
  }
}
async function create() {
  if (!createForm.subjectHash) {
    ElMessage.warning('请选择已确认主体')
    return
  }
  const r = (await http.post('/api/tickets', { ...createForm })).data
  ElMessage.success(r.idempotentHit ? `命中幂等，并回原工单 #${r.ticketId}` : `工单已创建 #${r.ticketId}`)
  await load()
}
function ticketStatusLabel(s) {
  return Object.fromEntries(statuses.map((x) => [x.value, x.label]))[s] || s
}
function ticketStatusType(s) {
  return { COMPLETED: 'success', CANCELLED: 'info', RETURNED: 'warning', WAITING_RECEIPT: 'primary', RECONCILIATION: 'warning', PENDING_EXECUTION: 'danger' }[s] || ''
}
function nodeLabel(n) {
  return { ENUMERATION: '枚举', PREVIEW: '预览', EXECUTION: '执行', RECEIPT: '回执', RECONCILE: '对账', DONE: '完成' }[n] || n
}
onMounted(() => {
  loadSubjects()
  load()
})
</script>
