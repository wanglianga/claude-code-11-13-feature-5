<template>
  <div v-loading="loading">
    <div style="display:flex; justify-content:space-between; align-items:center">
      <h2 class="page-title" style="margin:0">工单处置台 · {{ ticket?.ticket_no }}</h2>
      <el-button @click="$router.back()">返回列表</el-button>
    </div>

    <el-card v-if="ticket" class="section-card">
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item label="状态">
          <el-tag :type="statusType(ticket.status)">{{ statusLabel(ticket.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="当前节点">{{ nodeLabel(ticket.currentNode) }}</el-descriptions-item>
        <el-descriptions-item label="渠道">{{ ticket.channel }}</el-descriptions-item>
        <el-descriptions-item label="法定到期日">{{ ticket.legalDeadline }}</el-descriptions-item>
        <el-descriptions-item label="授权撤回时点">{{ ticket.consentWithdrawnAt }}</el-descriptions-item>
        <el-descriptions-item label="可撤回截止">{{ ticket.cancelBefore || '—' }}</el-descriptions-item>
        <el-descriptions-item label="退回原因" :span="2">{{ ticket.returnReason || '—' }}</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top:14px; display:flex; flex-wrap:wrap; gap:8px">
        <el-button type="primary" :disabled="!canFreeze" @click="act('freeze-enumeration', '冻结枚举快照')">冻结枚举快照</el-button>
        <el-button type="warning" :disabled="!canPreview" @click="act('preview', '影响面预览')">影响面预览</el-button>
        <el-button type="danger" :disabled="!canExecute" @click="act('execute', '执行删除作业')">执行删除作业</el-button>
        <el-button :disabled="!canExecute" @click="retryRetention">保留期到期重放</el-button>
        <el-button type="info" plain :disabled="!canCancel" @click="cancel">撤回注销</el-button>
        <el-button type="warning" plain :disabled="!canReturn" @click="doReturn">退回当前节点</el-button>
        <el-button type="success" plain :disabled="ticket.status !== 'RETURNED'" @click="act('resume', '续办')">续办</el-button>
        <el-button type="primary" plain :disabled="!canReconcile" @click="reconcile">对账并关单</el-button>
        <el-button type="primary" :disabled="ticket.status==='CREATED'" @click="buildEvidence">生成证据包</el-button>
      </div>
    </el-card>

    <el-card v-if="previewResult" class="section-card">
      <template #header>最近一次影响面预览（可撤回窗口 {{ previewResult.cancelBefore }}）</template>
      <el-table :data="previewResult.impact" border size="small">
        <el-table-column prop="assetCode" label="资产" width="190" />
        <el-table-column label="处置类别" width="140">
          <template #default="{ row }"><el-tag :type="dispType(row.category)">{{ dispLabel(row.category) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="rowCount" label="涉及行数" width="100" />
        <el-table-column prop="retentionUntil" label="保留期到期" width="140" />
        <el-table-column label="重算前值" show-overflow-tooltip>
          <template #default="{ row }">{{ row.beforeValue || '—' }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-tabs v-if="ticket" type="border-card">
      <el-tab-pane label="枚举快照（不可变）">
        <el-alert v-if="ticket.snapshot?.length" type="success" :closable="false" style="margin-bottom:10px"
          :title="`快照 #${ticket.snapshot[0].id} 已冻结 · ${ticket.snapshot[0].item_count} 项 · 清单哈希 ${ticket.snapshot[0].manifest_hash}`" />
        <el-table :data="ticket.items" border size="small">
          <el-table-column prop="asset_code" label="资产编码" width="170" />
          <el-table-column prop="asset_name" label="名称" width="140" />
          <el-table-column label="处置类别" width="130">
            <template #default="{ row }"><el-tag :type="dispType(row.disposition_category)">{{ dispLabel(row.disposition_category) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="快照状态" width="130">
            <template #default="{ row }">
              <el-tag :type="row.item_status==='IN_SCOPE'?'success':row.item_status==='PENDING_HUMAN'?'danger':'info'">
                {{ itemStatusLabel(row.item_status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="row_count" label="行数" width="80" />
          <el-table-column prop="retention_until" label="保留期" width="120" />
          <el-table-column prop="lineage_path" label="血缘路径" show-overflow-tooltip />
          <el-table-column label="人工确认" width="170" fixed="right">
            <template #default="{ row }">
              <template v-if="row.item_status === 'PENDING_HUMAN'">
                <el-button size="small" type="success" @click="confirmGap(row.gap_id || 1, true)">纳入</el-button>
                <el-button size="small" type="info" @click="confirmGap(row.gap_id || 1, false)">排除</el-button>
              </template>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="处置记账（四类）">
        <el-table :data="ticket.dispositions" border size="small">
          <el-table-column prop="asset_code" label="资产" width="170" />
          <el-table-column label="类别" width="130">
            <template #default="{ row }"><el-tag :type="dispType(row.category)">{{ dispLabel(row.category) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="状态" width="140">
            <template #default="{ row }"><el-tag :type="dispStatusType(row.status)">{{ dispStatusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="before_value" label="重算/清除前" width="150" show-overflow-tooltip />
          <el-table-column prop="after_value" label="后" width="90" />
          <el-table-column prop="diff_value" label="差额" width="120" show-overflow-tooltip />
          <el-table-column prop="attempt_count" label="尝试次数" width="90" />
          <el-table-column label="抽样反查哈希" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.recheck_hash || '—' }}</span></template>
          </el-table-column>
          <el-table-column prop="receipt_ref" label="回执号" width="170" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="第三方回执">
        <el-table :data="ticket.notifications" border size="small" style="margin-bottom:12px">
          <el-table-column prop="party" label="接收方" width="160" />
          <el-table-column prop="asset_code" label="资产" width="160" />
          <el-table-column prop="notice_ref" label="通知号" width="240" />
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag :type="row.notice_status==='RECEIPT_RECEIVED'?'success':'warning'">{{ row.notice_status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="receipt_ref" label="回执号" />
        </el-table>
        <el-form inline v-if="ticket.notifications.some((n) => n.notice_status === 'SENT')">
          <el-form-item label="通知号">
            <el-select v-model="receiptForm.noticeRef" style="width:280px">
              <el-option v-for="n in ticket.notifications.filter((x) => x.notice_status === 'SENT')"
                :key="n.id" :label="n.notice_ref" :value="n.notice_ref" />
            </el-select>
          </el-form-item>
          <el-form-item label="第三方回执号"><el-input v-model="receiptForm.receiptRef" style="width:220px" /></el-form-item>
          <el-form-item><el-button type="primary" @click="registerReceipt">登记回执</el-button></el-form-item>
        </el-form>
        <el-alert type="info" :closable="false" title="第三方销毁不在本地事务边界内：回执未到工单只能挂起，禁止无回执推进。" />
      </el-tab-pane>

      <el-tab-pane label="对账复核">
        <el-table :data="ticket.issues" border size="small" style="margin-bottom:12px">
          <el-table-column prop="issue_type" label="问题类型" width="200" />
          <el-table-column prop="expected_value" label="期望值" width="180" show-overflow-tooltip />
          <el-table-column prop="actual_value" label="实际值" width="180" show-overflow-tooltip />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status==='OPEN'?'danger':'success'">{{ row.status==='OPEN'?'待复核':'已解决' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resolution_note" label="复核说明" show-overflow-tooltip />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status==='OPEN'" size="small" type="primary" @click="resolveIssue(row.id)">复核解决</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-alert type="warning" :closable="false" title="物理清除、重算差额、抽样反查对不上的位置单列在此；存在未决问题时工单不得关单。" />
      </el-tab-pane>

      <el-tab-pane label="模型卡（参数不可回退）">
        <el-table :data="ticket.modelCards" border size="small">
          <el-table-column prop="model_name" label="模型" width="180" />
          <el-table-column prop="affected_sample_count" label="受影响样本" width="110" />
          <el-table-column prop="trained_params_note" label="参数说明" show-overflow-tooltip />
          <el-table-column label="重训决策" width="160">
            <template #default="{ row }">
              <el-tag :type="row.retrain_decision==='RETRAINED'?'success':row.retrain_decision==='PENDING'?'warning':'info'">
                {{ row.retrain_decision }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="performance_before" label="效果前" width="130" />
          <el-table-column prop="performance_after" label="效果后" width="130" />
          <el-table-column label="操作" width="140">
            <template #default="{ row }">
              <el-button v-if="row.retrain_decision==='PENDING'" size="small" type="primary" @click="openRetrain(row.id)">重训决策</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="增补工单（快照不可改写）">
        <el-table :data="ticket.supplements" border size="small" style="margin-bottom:12px">
          <el-table-column prop="asset_code" label="事后补登记资产" width="200" />
          <el-table-column prop="reason" label="原因" show-overflow-tooltip />
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><el-tag :type="row.status==='EXECUTED'?'success':'warning'">{{ row.status }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <el-button v-if="row.status==='PENDING'" size="small" type="primary" @click="executeSupplement(row.id)">执行增补</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-form inline>
          <el-form-item label="新接入资产编码"><el-input v-model="supplementForm.assetCode" style="width:220px" placeholder="如 AST_INTERVIEW_VIDEO" /></el-form-item>
          <el-form-item label="原因"><el-input v-model="supplementForm.reason" style="width:300px" /></el-form-item>
          <el-form-item><el-button @click="createSupplement">生成增补工单</el-button></el-form-item>
        </el-form>
      </el-tab-pane>

      <el-tab-pane label="证据包">
        <el-table :data="evidenceList" border size="small">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column label="包哈希" show-overflow-tooltip>
            <template #default="{ row }"><span class="mono">{{ row.package_hash }}</span></template>
          </el-table-column>
          <el-table-column prop="item_count" label="索引条目" width="100" />
          <el-table-column prop="file_path" label="证据文件" show-overflow-tooltip />
          <el-table-column prop="created_at" label="生成时间" width="200" />
        </el-table>
      </el-tab-pane>

      <el-tab-pane label="审计时间线">
        <el-timeline>
          <el-timeline-item v-for="log in ticket.auditLogs" :key="log.id" :timestamp="log.created_at" placement="top">
            <el-card>
              <h4 style="margin:0 0 4px">{{ log.action }} <el-tag size="small">{{ log.actor }}</el-tag></h4>
              <pre class="mono" style="margin:0; white-space:pre-wrap">{{ JSON.stringify(log.detail_json, null, 2) }}</pre>
            </el-card>
          </el-timeline-item>
        </el-timeline>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import http from '../api/http'
import { ElMessage, ElMessageBox } from 'element-plus'

const route = useRoute()
const ticketId = route.params.id
const ticket = ref(null)
const loading = ref(false)
const previewResult = ref(null)
const evidenceList = ref([])
const receiptForm = reactive({ noticeRef: '', receiptRef: '' })
const supplementForm = reactive({ assetCode: '', reason: '' })

const canFreeze = computed(() => ticket.value && ['CREATED', 'RETURNED'].includes(ticket.value.status) && ticket.value.currentNode === 'ENUMERATION')
const canPreview = computed(() => ticket.value && ['ENUM_FROZEN', 'RETURNED'].includes(ticket.value.status))
const canExecute = computed(() => ticket.value && ['PENDING_EXECUTION', 'RETURNED', 'EXECUTING', 'WAITING_RECEIPT'].includes(ticket.value.status))
const canCancel = computed(() => ticket.value && ['PENDING_EXECUTION', 'RETURNED'].includes(ticket.value.status))
const canReturn = computed(() => ticket.value && !['COMPLETED', 'CANCELLED'].includes(ticket.value.status))
const canReconcile = computed(() => ticket.value && ['RECONCILIATION', 'RETURNED'].includes(ticket.value.status))

async function load() {
  loading.value = true
  try {
    ticket.value = (await http.get(`/api/tickets/${ticketId}`)).data
    evidenceList.value = (await http.get(`/api/tickets/${ticketId}/evidence-package`)).data
  } finally {
    loading.value = false
  }
}
async function act(path, label) {
  const r = (await http.post(`/api/tickets/${ticketId}/${path}`)).data
  ElMessage.success(`${label}完成`)
  if (path === 'preview') previewResult.value = r
  await load()
}
async function confirmGap(gapId, inScope) {
  await http.post(`/api/tickets/${ticketId}/gaps/${gapId}/confirm`, { inScope })
  ElMessage.success(inScope ? '已确认纳入范围' : '已确认排除范围')
  await load()
}
async function cancel() {
  const { value } = await ElMessageBox.prompt('请输入撤回原因', '撤回注销（数据将原样保留）', { inputType: 'textarea' })
  await http.post(`/api/tickets/${ticketId}/cancel`, { reason: value || '候选人撤回' })
  ElMessage.success('工单已撤回，数据未改动')
  await load()
}
async function doReturn() {
  const { value } = await ElMessageBox.prompt('退回原因（如：派生表漏登记、回执未到）', '退回当前处置节点', { inputType: 'textarea' })
  await http.post(`/api/tickets/${ticketId}/return`, { reason: value })
  ElMessage.success('已退回，接手人可见已确认主体与冻结结论')
  await load()
}
async function retryRetention() {
  const r = (await http.post(`/api/tickets/${ticketId}/retry-retention`)).data
  ElMessage.success(`保留期重放完成，本次清除 ${r.executed} 个位置`)
  await load()
}
async function reconcile() {
  const r = (await http.post(`/api/tickets/${ticketId}/reconcile`)).data
  if (r.passed) ElMessage.success('对账通过，工单已关单')
  else ElMessage.warning(`对账未通过，${r.openIssues} 个问题待合规复核`)
  await load()
}
async function registerReceipt() {
  await http.post(`/api/tickets/${ticketId}/receipts`, { ...receiptForm })
  ElMessage.success('第三方回执已登记')
  receiptForm.receiptRef = ''
  await load()
}
async function resolveIssue(issueId) {
  const { value } = await ElMessageBox.prompt('复核结论说明', '解决对账问题', { inputType: 'textarea' })
  await http.post(`/api/issues/${issueId}/resolve`, { note: value })
  ElMessage.success('问题已复核解决')
  await load()
}
async function openRetrain(cardId) {
  const { value } = await ElMessageBox.prompt('请填写重训决策（格式：决策|效果前|效果后|理由），决策取 RETRAINED 或 NO_RETRAIN_JUSTIFIED',
    '模型卡重训决策', { inputType: 'textarea', inputValue: 'RETRAINED|AUC=0.812|AUC=0.810|受影响样本占比低，完成重训' })
  const [decision, performanceBefore, performanceAfter, justification] = value.split('|')
  await http.post(`/api/model-cards/${cardId}/retrain-decision`,
    { decision, performanceBefore, performanceAfter, justification })
  ElMessage.success('重训决策已写入模型卡，与物理删除记录分开陈列')
  await load()
}
async function createSupplement() {
  await http.post(`/api/tickets/${ticketId}/supplements`, { ...supplementForm })
  ElMessage.success('增补工单已生成，原快照保持不可变')
  supplementForm.assetCode = ''
  supplementForm.reason = ''
  await load()
}
async function executeSupplement(id) {
  await http.post(`/api/supplements/${id}/execute`)
  ElMessage.success('增补处置已执行')
  await load()
}
async function buildEvidence() {
  const r = (await http.post(`/api/tickets/${ticketId}/evidence-package`)).data
  ElMessage.success(`证据包已封存：${r.packageHash.substring(0, 16)}…`)
  await load()
}
function statusLabel(s) {
  return { CREATED: '待枚举', ENUM_FROZEN: '枚举已冻结', PENDING_EXECUTION: '待执行(可撤回)', EXECUTING: '执行中', WAITING_RECEIPT: '等待回执', RECONCILIATION: '对账中', COMPLETED: '已完成', CANCELLED: '已撤回', RETURNED: '已退回' }[s] || s
}
function statusType(s) {
  return { COMPLETED: 'success', CANCELLED: 'info', RETURNED: 'warning', WAITING_RECEIPT: 'primary', RECONCILIATION: 'warning', PENDING_EXECUTION: 'danger' }[s] || ''
}
function nodeLabel(n) {
  return { ENUMERATION: '枚举', PREVIEW: '预览', EXECUTION: '执行', RECEIPT: '回执', RECONCILE: '对账', DONE: '完成' }[n] || n
}
function dispLabel(v) {
  return { PHYSICAL_DELETE: '物理清除', RECOMPUTE_AGGREGATE: '重算聚合', WAIT_RETENTION: '等待保留期', EXTERNAL_NOTIFY: '流出通知' }[v] || v
}
function dispType(v) {
  return { PHYSICAL_DELETE: 'danger', RECOMPUTE_AGGREGATE: 'warning', WAIT_RETENTION: 'info', EXTERNAL_NOTIFY: 'primary' }[v] || ''
}
function dispStatusLabel(s) {
  return { PENDING: '待处理', PREVIEWED: '已预览', SCHEDULED: '已排期(等保留期)', EXECUTED: '已执行', FAILED: '失败', WAITING_RECEIPT: '等待回执', RECEIPT_RECEIVED: '回执已到', SKIPPED: '无需处置' }[s] || s
}
function dispStatusType(s) {
  return { EXECUTED: 'success', RECEIPT_RECEIVED: 'success', SCHEDULED: 'warning', WAITING_RECEIPT: 'primary', FAILED: 'danger', SKIPPED: 'info' }[s] || ''
}
function itemStatusLabel(s) {
  return { IN_SCOPE: '纳入范围', PENDING_HUMAN: '待人工确认', OUT_OF_SCOPE: '排除' }[s] || s
}
onMounted(load)
</script>
