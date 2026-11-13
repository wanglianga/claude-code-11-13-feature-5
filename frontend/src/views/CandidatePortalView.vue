<template>
  <div class="portal-wrap">
    <el-card class="portal-card">
      <div class="portal-head">
        <h2>候选人查询与身份错配复核入口</h2>
        <p>凭“账号 + 标识符知识”核验后获取<strong>限时凭证</strong>，查看隐藏敏感信息办理说明、补交账号线索、追踪处理进度。
          证件号等敏感信息不回显明文，也不会向您透露其他主体的任何信息。</p>
      </div>

      <el-steps :active="activeStep" finish-status="success" align-center style="margin: 8px 0 20px">
        <el-step title="1 身份核验" />
        <el-step title="2 办理说明" />
        <el-step title="3 提交异议/线索" />
        <el-step title="4 办理追踪" />
      </el-steps>

      <!-- 步骤一：签发限时凭证 -->
      <el-form label-position="top" :model="issueForm">
        <el-form-item label="账号 ID">
          <el-input v-model="issueForm.accountId" placeholder="如 ACC-4001" />
        </el-form-item>
        <el-form-item label="标识符类型">
          <el-radio-group v-model="issueForm.idType">
            <el-radio-button value="PHONE">手机号</el-radio-button>
            <el-radio-button value="EMAIL">邮箱</el-radio-button>
            <el-radio-button value="IDCARD">证件号</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标识符值（仅用于哈希校验，不回显）">
          <el-input v-model="issueForm.idValue" placeholder="输入注册时的手机号/邮箱/证件号" show-password />
        </el-form-item>
        <el-button type="primary" :loading="loading" @click="issue">核验并签发限时凭证</el-button>
      </el-form>

      <el-alert v-if="token" type="success" :closable="false" style="margin-top:14px"
        :title="`凭证已签发，有效期至 ${token.expiresAt}`" :description="`凭证号：${token.tokenRef}（归属修订后旧凭证立即失效，请重新核验）`" />

      <!-- 步骤二：办理说明 -->
      <template v-if="token">
        <el-divider />
        <el-button type="primary" plain :loading="loading" @click="viewInstruction">限时查看隐藏敏感信息办理说明</el-button>
        <el-descriptions v-if="instruction" :column="1" border style="margin-top:12px">
          <el-descriptions-item label="主体">{{ instruction.subject.displayName }}（{{ subjectStatus(instruction.subject.status) }}）</el-descriptions-item>
          <el-descriptions-item label="本人账号">
            <el-tag v-for="a in instruction.ownAccounts" :key="a.account_id" style="margin-right:6px">{{ a.account_id }} / {{ a.name }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="办理说明">
            <pre class="pre-text">{{ instruction.instruction }}</pre>
          </el-descriptions-item>
        </el-descriptions>

        <!-- 步骤三：提交异议 -->
        <el-divider />
        <h3>提交身份异议 / 补交账号线索</h3>
        <el-form label-position="top" :model="disputeForm">
          <el-form-item label="异议类型">
            <el-select v-model="disputeForm.kind" style="width:100%">
              <el-option label="另一份简历漏删（LEAKED_RESIDUE）" value="LEAKED_RESIDUE" />
              <el-option label="他人资料算到我名下（WRONG_ATTRIBUTION）" value="WRONG_ATTRIBUTION" />
              <el-option label="撤销错误账号关联（RELATION_REVOKE）" value="RELATION_REVOKE" />
            </el-select>
          </el-form-item>
          <el-form-item label="被异议账号">
            <el-input v-model="disputeForm.disputedAccount" placeholder="如 ACC-4001" />
          </el-form-item>
          <el-form-item label="资料被错挂的主体哈希（可选，留空默认本主体）">
            <el-input v-model="disputeForm.wrongSubjectHash" placeholder="f08840a3…（64 位）" />
          </el-form-item>
          <el-form-item label="账号线索 / 情况说明">
            <el-input v-model="disputeForm.clue" type="textarea" :rows="3" placeholder="注册手机号、常用邮箱、账号 ID 等可核验线索" />
          </el-form-item>
          <el-button type="primary" :loading="loading" @click="submitDispute">提交（重复异议自动合并补证）</el-button>
        </el-form>

        <!-- 步骤四：办理追踪 -->
        <el-divider />
        <div style="display:flex; align-items:center; gap:10px">
          <h3 style="margin:0">办理追踪</h3>
          <el-button size="small" :loading="loading" @click="track">刷新</el-button>
        </div>
        <el-table v-if="tracking" :data="tracking.disputes" border stripe size="small" style="margin-top:10px">
          <el-table-column prop="dispute_no" label="异议编号" width="200" />
          <el-table-column prop="dispute_kind" label="类型" width="170" />
          <el-table-column label="办理状态" width="180">
            <template #default="{ row }">
              <el-tag :type="disputeStatusType(row.status)">{{ disputeStatusLabel(row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="联系方式" width="160">
            <template #default="{ row }">{{ row.contact_status === 'REACHABLE' ? '可联系' : '失效·人工证明' }}</template>
          </el-table-column>
          <el-table-column label="证据版本" width="90">
            <template #default="{ row }">v{{ row.current_evidence_version }}</template>
          </el-table-column>
          <el-table-column label="操作" min-width="120">
            <template #default="{ row }">
              <el-button size="small" @click="openClue(row)">补线索</el-button>
              <el-button size="small" type="primary" @click="openDetail(row)">明细</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="暂无异议，提交后可在此追踪" :image-size="70" />
      </template>
    </el-card>

    <el-drawer v-model="detailOpen" size="46%" title="异议明细与答复版本">
      <template v-if="current">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="编号">{{ current.dispute_no }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ disputeStatusLabel(current.status) }}</el-descriptions-item>
          <el-descriptions-item label="证据版本">v{{ current.current_evidence_version }}</el-descriptions-item>
        </el-descriptions>
        <h4>证据/线索版本</h4>
        <el-timeline>
          <el-timeline-item v-for="e in current.evidenceList" :key="e.evidence_version"
            :timestamp="`v${e.evidence_version} · ${e.clue_type} · ${e.status}`">
            {{ e.content }}
          </el-timeline-item>
        </el-timeline>
        <h4>对外答复（按版本保留，旧版可查回）</h4>
        <el-table :data="current.replies" border size="small">
          <el-table-column prop="reply_no" label="答复号" min-width="180" />
          <el-table-column prop="reply_version" label="版本" width="70" />
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.reply_status === 'ISSUED' ? 'success' : 'info'">
                {{ row.reply_status === 'ISSUED' ? '现行' : '已被取代' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="160" />
        </el-table>
      </template>
    </el-drawer>

    <el-dialog v-model="clueOpen" title="补交线索/证明材料" width="480px">
      <el-form label-position="top">
        <el-form-item label="材料类型">
          <el-select v-model="clueForm.clueType" style="width:100%">
            <el-option label="账号线索 ACCOUNT_CLUE" value="ACCOUNT_CLUE" />
            <el-option label="身份证明 ID_PROOF" value="ID_PROOF" />
            <el-option label="人工证明 MANUAL_PROOF" value="MANUAL_PROOF" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="clueForm.content" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="clueOpen = false">取消</el-button>
        <el-button type="primary" @click="submitClue">提交补证</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import http from '../api/http'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const activeStep = ref(0)
const token = ref(null)
const instruction = ref(null)
const tracking = ref(null)
const detailOpen = ref(false)
const current = ref(null)
const clueOpen = ref(false)
const issueForm = reactive({ accountId: 'ACC-4001', idType: 'PHONE', idValue: '13600000004' })
const disputeForm = reactive({ kind: 'WRONG_ATTRIBUTION', disputedAccount: 'ACC-4001', wrongSubjectHash: '', clue: '' })
const clueForm = reactive({ disputeId: null, clueType: 'ACCOUNT_CLUE', content: '' })

async function issue() {
  loading.value = true
  try {
    const { data } = await http.post('/api/public/portal/tokens', issueForm)
    token.value = data
    activeStep.value = 1
    ElMessage.success('限时凭证已签发')
  } finally {
    loading.value = false
  }
}
async function viewInstruction() {
  loading.value = true
  try {
    const { data } = await http.get('/api/public/portal/instruction', { params: { tokenRef: token.value.tokenRef } })
    instruction.value = data
    activeStep.value = Math.max(activeStep.value, 2)
  } finally {
    loading.value = false
  }
}
async function submitDispute() {
  loading.value = true
  try {
    const { data } = await http.post('/api/public/portal/disputes', {
      tokenRef: token.value.tokenRef, ...disputeForm,
      wrongSubjectHash: disputeForm.wrongSubjectHash || null
    })
    ElMessage.success(data.merged ? '重复异议已合并补证，证据版本递增' : '异议已提交')
    activeStep.value = 3
    await track()
  } finally {
    loading.value = false
  }
}
async function track() {
  loading.value = true
  try {
    const { data } = await http.get('/api/public/portal/track', { params: { tokenRef: token.value.tokenRef } })
    tracking.value = data
    activeStep.value = 3
  } finally {
    loading.value = false
  }
}
function openDetail(row) {
  current.value = row
  detailOpen.value = true
}
function openClue(row) {
  clueForm.disputeId = row.id
  clueForm.content = ''
  clueOpen.value = true
}
async function submitClue() {
  await http.post(`/api/public/portal/disputes/${clueForm.disputeId}/clues`, {
    tokenRef: token.value.tokenRef, clueType: clueForm.clueType, content: clueForm.content
  })
  ElMessage.success('补证已提交，证据版本递增')
  clueOpen.value = false
  await track()
}
function subjectStatus(s) {
  return { CANDIDATE: '待确认', CONFIRMED: '已确认', REJECTED: '已驳回', MERGED: '已合并' }[s] || s
}
function disputeStatusLabel(s) {
  return {
    SUBMITTED: '已提交', MERGED: '已合并补证', PENDING_VERIFICATION: '待核验',
    AWAITING_MANUAL_PROOF: '待人工证明', VERIFIED: '已核验',
    ESCALATED_COMPLIANCE: '合规裁定中', ADJUDICATED: '已裁定', CLOSED: '已结案'
  }[s] || s
}
function disputeStatusType(s) {
  return {
    ADJUDICATED: 'success', CLOSED: 'success', ESCALATED_COMPLIANCE: 'warning',
    AWAITING_MANUAL_PROOF: 'danger', PENDING_VERIFICATION: 'info', MERGED: 'info'
  }[s] || ''
}
</script>

<style scoped>
.portal-wrap { min-height: 100vh; background: #1f2d3d; padding: 30px 12px; display: flex; justify-content: center; }
.portal-card { width: 920px; max-width: 100%; }
.portal-head h2 { margin: 0 0 8px; }
.portal-head p { color: #606266; line-height: 1.7; }
.pre-text { white-space: pre-wrap; font-family: inherit; margin: 0; line-height: 1.7; }
</style>
