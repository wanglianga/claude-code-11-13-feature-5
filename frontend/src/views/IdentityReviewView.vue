<template>
  <div>
    <h2 class="page-title">身份错配复核工作台</h2>
    <el-alert type="warning" :closable="false" style="margin-bottom:12px"
      title="规则：手机号相同只生成待核验关系（不自动归并）；证件指纹冲突阻断归并。归属判定版本化只追加，纠错另建修订，旧结论不改写；账号关系、异议办理、对外答复三套状态独立。" />

    <el-tabs v-model="tab" @tab-change="reload">
      <!-- 账号关系与归属版本 -->
      <el-tab-pane label="账号关系 / 归属版本" name="relations">
        <el-card class="section-card">
          <el-button type="primary" :loading="loading" @click="scanRelations">扫描账号关系</el-button>
          <el-radio-group v-model="relationFilter" size="small" style="margin-left:16px" @change="loadRelations">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button value="PENDING_VERIFICATION">待核验</el-radio-button>
            <el-radio-button value="BLOCKED">阻断</el-radio-button>
            <el-radio-button value="REVOKED">已撤销</el-radio-button>
          </el-radio-group>
        </el-card>
        <el-card class="section-card">
          <el-table :data="relations" border stripe size="small" v-loading="loading">
            <el-table-column prop="account_id_a" label="账号 A" width="120" />
            <el-table-column prop="account_id_b" label="账号 B" width="120" />
            <el-table-column label="匹配类型" width="150">
              <template #default="{ row }">{{ matchTypeLabel(row.match_type) }}</template>
            </el-table-column>
            <el-table-column label="关系状态" width="150">
              <template #default="{ row }"><el-tag :type="relationType(row.relation_status)">{{ relationLabel(row.relation_status) }}</el-tag></template>
            </el-table-column>
            <el-table-column prop="evidence_version" label="证据版本" width="90" />
            <el-table-column prop="note" label="备注" min-width="180" />
          </el-table>
        </el-card>
        <el-card>
          <template #header>账号归属判定版本链（旧结论只追加不改写）</template>
          <el-input v-model="attrAccount" placeholder="按账号过滤，如 ACC-4001" style="width:260px" @change="loadAttributions">
            <template #append><el-button @click="loadAttributions">查询</el-button></template>
          </el-input>
          <el-table :data="attributions" border stripe size="small" style="margin-top:10px">
            <el-table-column prop="account_id" label="账号" width="110" />
            <el-table-column label="归属结论" width="190">
              <template #default="{ row }">
                <el-tag :type="decisionType(row.decision_status)">{{ decisionLabel(row.decision_status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="evidence_version" label="证据版本" width="90" />
            <el-table-column prop="revision_no" label="修订号" width="80" />
            <el-table-column label="取代指针" width="110">
              <template #default="{ row }">{{ row.superseded_by_id ?? '—' }}</template>
            </el-table-column>
            <el-table-column prop="decided_by" label="判定人" width="110" />
            <el-table-column prop="decided_at" label="判定时间" min-width="170" />
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 异议办理 -->
      <el-tab-pane label="异议办理" name="disputes">
        <el-card class="section-card">
          <el-radio-group v-model="disputeFilter" size="small" @change="loadDisputes">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button v-for="s in disputeStatuses" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
          </el-radio-group>
        </el-card>
        <el-card>
          <el-table :data="disputes" border stripe size="small" v-loading="loading">
            <el-table-column prop="dispute_no" label="异议编号" width="190" />
            <el-table-column prop="applicant_account" label="申请账号" width="120" />
            <el-table-column prop="disputed_account" label="被异议账号" width="120" />
            <el-table-column label="类型" width="160">
              <template #default="{ row }">{{ kindLabel(row.dispute_kind) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="160">
              <template #default="{ row }"><el-tag :type="disputeStatusType(row.status)">{{ disputeStatusLabel(row.status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="联系方式" width="140">
              <template #default="{ row }">
                <el-tag size="small" :type="row.contact_status === 'REACHABLE' ? 'success' : 'danger'">
                  {{ row.contact_status === 'REACHABLE' ? '可联系' : '失效·人工证明' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="证据版本" width="90">
              <template #default="{ row }">v{{ row.current_evidence_version }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button size="small" type="primary" @click="openDispute(row)">办理</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 修订影响项 -->
      <el-tab-pane label="修订影响项" name="impacts">
        <el-card>
          <el-table :data="impacts" border stripe size="small" v-loading="loading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="revision_id" label="修订" width="80" />
            <el-table-column prop="account_id" label="账号" width="110" />
            <el-table-column prop="asset_code" label="资产" width="130" />
            <el-table-column prop="location_ref" label="位置" min-width="200" show-overflow-tooltip />
            <el-table-column label="影响类型" width="190">
              <template #default="{ row }">{{ impactKindLabel(row.impact_kind) }}</template>
            </el-table-column>
            <el-table-column label="处置状态" width="180">
              <template #default="{ row }"><el-tag :type="impactStatusType(row.item_status)">{{ impactStatusLabel(row.item_status) }}</el-tag></template>
            </el-table-column>
            <el-table-column label="操作" width="300" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.impact_kind === 'SHARED_REFERENCE_SPLIT' && row.item_status === 'SPLIT_PENDING_DISPOSITION'"
                  size="small" type="warning" @click="split(row)">拆引用</el-button>
                <el-button v-if="row.impact_kind === 'SUPPLEMENTAL_DELETE' && row.item_status === 'SUPPLEMENT_TICKETED'"
                  size="small" type="danger" @click="supplemental(row)">补充删除</el-button>
                <el-button v-if="row.impact_kind === 'IRRECOVERABLE_DELETED' && row.item_status !== 'IRRECOVERABLE_RECORDED'"
                  size="small" @click="irrecoverable(row)">登记不可恢复</el-button>
                <el-button v-if="['PENDING','SPLIT_PENDING_DISPOSITION','SUPPLEMENT_TICKETED'].includes(row.item_status)"
                  size="small" type="success" @click="resolve(row)">办结</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>

      <!-- 作业事故 / 暂停 -->
      <el-tab-pane label="作业事故 / 暂停" name="incidents">
        <el-card class="section-card">
          <el-form inline>
            <el-form-item label="工单 ID"><el-input v-model.number="incidentForm.ticketId" style="width:120px" /></el-form-item>
            <el-form-item label="修订 ID"><el-input v-model.number="incidentForm.revisionId" style="width:120px" /></el-form-item>
            <el-form-item><el-button type="primary" @click="raiseIncident">登记作业遇裁定（暂停/事故）</el-button></el-form-item>
          </el-form>
        </el-card>
        <el-card>
          <el-table :data="incidents" border stripe size="small" v-loading="loading">
            <el-table-column prop="id" label="ID" width="70" />
            <el-table-column prop="ticket_id" label="工单" width="80" />
            <el-table-column prop="account_id" label="账号" width="110" />
            <el-table-column label="事故类型" width="180">
              <template #default="{ row }">
                <el-tag :type="row.incident_kind === 'PAUSED_VERSION_STALE' ? 'warning' : 'danger'">
                  {{ row.incident_kind === 'PAUSED_VERSION_STALE' ? '版本过期·暂停' : '已处理·事故' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="版本" width="110">
              <template #default="{ row }">期望 v{{ row.expected_version }} → 实际 v{{ row.actual_version }}</template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.incident_status === 'RESOLVED' ? 'success' : 'danger'">
                  {{ row.incident_status === 'RESOLVED' ? '已解决' : '待处理' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="detail" label="说明" min-width="240" show-overflow-tooltip />
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <el-button v-if="row.incident_kind === 'PAUSED_VERSION_STALE' && row.incident_status === 'OPEN' && row.disposition_id"
                  size="small" type="primary" @click="resumePaused(row)">核对后恢复</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <!-- 异议办理抽屉 -->
    <el-drawer v-model="disputeOpen" size="52%" :title="`异议办理 ${detail?.dispute_no ?? ''}`">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">{{ disputeStatusLabel(detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="联系方式">{{ detail.contact_status === 'REACHABLE' ? '可联系' : '失效·人工证明' }}</el-descriptions-item>
          <el-descriptions-item label="当前证据版本">v{{ detail.current_evidence_version }}</el-descriptions-item>
          <el-descriptions-item label="申请/被异议账号">{{ detail.applicant_account }} / {{ detail.disputed_account || '—' }}</el-descriptions-item>
        </el-descriptions>

        <div style="margin:14px 0; display:flex; flex-wrap:wrap; gap:8px">
          <el-button v-if="detail.status !== 'AWAITING_MANUAL_PROOF' && !terminal(detail.status)" size="small"
            @click="markUnreachable">联系方式失效·转人工证明</el-button>
          <el-button v-if="detail.status === 'AWAITING_MANUAL_PROOF'" size="small" type="danger"
            @click="manualProof(false)">证明不足（保持待核验）</el-button>
          <el-button v-if="detail.status === 'AWAITING_MANUAL_PROOF'" size="small" type="success"
            @click="manualProof(true)">人工证明通过</el-button>
          <el-button v-if="['SUBMITTED','PENDING_VERIFICATION','MERGED'].includes(detail.status)" size="small"
            @click="agentVerify(false)">客服存疑退回</el-button>
          <el-button v-if="['SUBMITTED','PENDING_VERIFICATION','MERGED'].includes(detail.status)" size="small" type="primary"
            @click="agentVerify(true)">客服核验通过·升级合规</el-button>
          <el-button v-if="detail.status === 'ESCALATED_COMPLIANCE'" size="small" type="warning"
            @click="openAdjudicate('UPHOLD')">合规维持(UPHOLD)</el-button>
          <el-button v-if="detail.status === 'ESCALATED_COMPLIANCE'" size="small" type="danger"
            @click="openAdjudicate('CORRECT')">合规更正(CORRECT)</el-button>
        </div>

        <h4>证据/线索版本链</h4>
        <el-timeline>
          <el-timeline-item v-for="e in detail.evidenceList" :key="e.id"
            :timestamp="`v${e.evidence_version} · ${e.clue_type} · ${e.status} · ${e.submitted_by}`">
            {{ e.content }}
          </el-timeline-item>
        </el-timeline>

        <h4>归属修订（纠错另建，旧结论与更正关系同存）</h4>
        <el-table :data="detail.revisions" border size="small">
          <el-table-column prop="revision_no" label="修订号" width="70" />
          <el-table-column prop="account_id" label="账号" width="100" />
          <el-table-column label="结论" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.ruling_decision === 'CORRECT' ? 'danger' : 'success'">{{ row.ruling_decision }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="归属变更" min-width="220">
            <template #default="{ row }">
              <span class="mono">{{ short(row.from_subject_hash) }} → {{ row.to_subject_hash ? short(row.to_subject_hash) : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="rationale" label="理由" min-width="180" show-overflow-tooltip />
        </el-table>

        <h4>修订影响项</h4>
        <el-table :data="detail.impactItems" border size="small">
          <el-table-column prop="asset_code" label="资产" width="120" />
          <el-table-column prop="location_ref" label="位置" min-width="160" show-overflow-tooltip />
          <el-table-column label="类型" width="170">
            <template #default="{ row }">{{ impactKindLabel(row.impact_kind) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }"><el-tag size="small" :type="impactStatusType(row.item_status)">{{ impactStatusLabel(row.item_status) }}</el-tag></template>
          </el-table-column>
        </el-table>

        <h4>对外答复</h4>
        <el-table :data="detail.replies" border size="small">
          <el-table-column prop="reply_no" label="答复号" min-width="170" />
          <el-table-column prop="reply_version" label="版本" width="70" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">{{ row.reply_status === 'ISSUED' ? '现行' : '已被取代' }}</template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="150" />
        </el-table>
      </template>
    </el-drawer>

    <el-dialog v-model="adjudicateOpen" :title="`合规裁定 ${adjudicateForm.decision}`" width="520px">
      <el-alert type="info" :closable="false" style="margin-bottom:10px"
        :title="`仅接受基于当前证据版本 v${adjudicateForm.baseEvidenceVersion} 的裁定；两位复核员并发相反结论时，旧版本提交会被拒绝。`" />
      <el-form label-position="top">
        <el-form-item label="基准证据版本">
          <el-input-number v-model="adjudicateForm.baseEvidenceVersion" :min="1" />
        </el-form-item>
        <el-form-item label="裁定理由">
          <el-input v-model="adjudicateForm.rationale" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adjudicateOpen = false">取消</el-button>
        <el-button type="primary" @click="submitAdjudicate">提交裁定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import http from '../api/http'
import { ElMessage, ElMessageBox } from 'element-plus'

const tab = ref('relations')
const loading = ref(false)
const relations = ref([])
const attributions = ref([])
const disputes = ref([])
const impacts = ref([])
const incidents = ref([])
const relationFilter = ref('')
const disputeFilter = ref('')
const attrAccount = ref('ACC-4001')
const disputeOpen = ref(false)
const detail = ref(null)
const adjudicateOpen = ref(false)
const adjudicateForm = reactive({ disputeId: null, decision: 'CORRECT', baseEvidenceVersion: 1, rationale: '' })
const incidentForm = reactive({ ticketId: null, revisionId: null })

const disputeStatuses = [
  { value: 'SUBMITTED', label: '已提交' },
  { value: 'AWAITING_MANUAL_PROOF', label: '待人工证明' },
  { value: 'PENDING_VERIFICATION', label: '待核验' },
  { value: 'ESCALATED_COMPLIANCE', label: '合规裁定中' },
  { value: 'ADJUDICATED', label: '已裁定' },
  { value: 'CLOSED', label: '已结案' }
]

async function reload(name) {
  if (name === 'relations') { await loadRelations(); await loadAttributions() }
  if (name === 'disputes') await loadDisputes()
  if (name === 'impacts') await loadImpacts()
  if (name === 'incidents') await loadIncidents()
}
async function scanRelations() {
  loading.value = true
  try {
    const { data } = await http.post('/api/identity/relations/scan')
    ElMessage.success(`扫描完成：待核验 ${data.pendingPhoneRelations}，阻断 ${data.blockedIdcardConflicts}`)
    await loadRelations()
  } finally { loading.value = false }
}
async function loadRelations() {
  const url = relationFilter.value ? `/api/identity/relations?status=${relationFilter.value}` : '/api/identity/relations'
  relations.value = (await http.get(url)).data
}
async function loadAttributions() {
  const url = attrAccount.value ? `/api/identity/attributions?accountId=${attrAccount.value}` : '/api/identity/attributions'
  attributions.value = (await http.get(url)).data
}
async function loadDisputes() {
  loading.value = true
  try {
    const url = disputeFilter.value ? `/api/identity/disputes?status=${disputeFilter.value}` : '/api/identity/disputes'
    disputes.value = (await http.get(url)).data
  } finally { loading.value = false }
}
async function loadImpacts() {
  impacts.value = (await http.get('/api/identity/impact-items')).data
}
async function loadIncidents() {
  incidents.value = (await http.get('/api/identity/incidents')).data
}
async function openDispute(row) {
  detail.value = (await http.get(`/api/identity/disputes/${row.id}`)).data
  disputeOpen.value = true
}
async function markUnreachable() {
  await http.post(`/api/identity/disputes/${detail.value.id}/contact-unreachable`, {})
  ElMessage.success('已转人工证明通道'); await refreshDetail()
}
async function manualProof(sufficient) {
  const { value } = await ElMessageBox.prompt('人工证明审核备注', '人工证明', { inputType: 'textarea' })
  await http.post(`/api/identity/disputes/${detail.value.id}/manual-proof`, { sufficient, note: value || '人工证明' })
  ElMessage.success(sufficient ? '证明通过，进入待核验' : '证明不足，保持待核验'); await refreshDetail()
}
async function agentVerify(accepted) {
  const { value } = await ElMessageBox.prompt('客服核验备注', '客服核验', { inputType: 'textarea' })
  await http.post(`/api/identity/disputes/${detail.value.id}/agent-verify`, { accepted, note: value || '客服核验' })
  ElMessage.success(accepted ? '已升级合规裁定' : '已退回待核验'); await refreshDetail()
}
function openAdjudicate(decision) {
  adjudicateForm.disputeId = detail.value.id
  adjudicateForm.decision = decision
  adjudicateForm.baseEvidenceVersion = detail.value.current_evidence_version
  adjudicateForm.rationale = ''
  adjudicateOpen.value = true
}
async function submitAdjudicate() {
  await http.post(`/api/identity/disputes/${adjudicateForm.disputeId}/adjudicate`, { ...adjudicateForm })
  ElMessage.success('裁定已提交，修订与影响项已生成')
  adjudicateOpen.value = false
  await refreshDetail(); await loadDisputes(); await loadImpacts()
}
async function split(row) {
  const { data } = await http.post(`/api/identity/impact-items/${row.id}/split`, {})
  ElMessage.success(`已拆引用 ${data.detached} 条，资料仍被 ${data.stillReferencedByOthers} 个合法候选人引用，本体保留`)
  await loadImpacts()
}
async function supplemental(row) {
  const { data } = await http.post(`/api/identity/impact-items/${row.id}/supplemental-delete`, {})
  ElMessage.success(`补充删除完成，删除 ${data.deleted} 行`); await loadImpacts()
}
async function irrecoverable(row) {
  await http.post(`/api/identity/impact-items/${row.id}/irrecoverable`, { note: '确认已物理删除，事实不可恢复' })
  ElMessage.success('已登记不可恢复事实'); await loadImpacts()
}
async function resolve(row) {
  await http.post(`/api/identity/impact-items/${row.id}/resolve`, { note: '人工确认办结' })
  ElMessage.success('影响项已办结'); await loadImpacts()
}
async function raiseIncident() {
  if (!incidentForm.ticketId || !incidentForm.revisionId) { ElMessage.warning('请填写工单与修订 ID'); return }
  const { data } = await http.post(`/api/identity/tickets/${incidentForm.ticketId}/incidents`, {
    revisionId: incidentForm.revisionId
  })
  ElMessage.success(`暂停 ${data.paused} 处，事故 ${data.processedAccidents} 处（暂停≠撤回成功）`)
  await loadIncidents()
}
async function resumePaused(row) {
  await http.post(`/api/identity/dispositions/${row.disposition_id}/resume-paused`, {})
  ElMessage.success('已按最新证据版本核对并恢复'); await loadIncidents()
}
async function refreshDetail() {
  detail.value = (await http.get(`/api/identity/disputes/${detail.value.id}`)).data
}
function terminal(s) { return ['ADJUDICATED', 'CLOSED'].includes(s) }
function short(h) { return h ? h.substring(0, 12) + '…' : h }
function matchTypeLabel(t) { return { PHONE: '手机号相同', IDCARD_CONFLICT: '证件指纹冲突', EMAIL: '邮箱相同' }[t] || t }
function relationLabel(s) {
  return { PENDING_VERIFICATION: '待核验', BLOCKED: '阻断', VERIFIED_SAME: '核验同一人', VERIFIED_DIFFERENT: '核验不同人', REVOKED: '已撤销', SUPERSEDED: '已被取代' }[s] || s
}
function relationType(s) { return { BLOCKED: 'danger', PENDING_VERIFICATION: 'warning', REVOKED: 'info', VERIFIED_SAME: 'success' }[s] || '' }
function decisionLabel(s) {
  return { AUTO_MERGED: '历史自动归并', PENDING_VERIFICATION: '待核验', BLOCKED_IDCARD_CONFLICT: '证件冲突阻断', CONFIRMED: '已确认', REVISED_OUT: '已修订移出', REASSIGNED: '已更正重指' }[s] || s
}
function decisionType(s) { return { REASSIGNED: 'success', BLOCKED_IDCARD_CONFLICT: 'danger', PENDING_VERIFICATION: 'warning', AUTO_MERGED: 'info' }[s] || '' }
function kindLabel(k) { return { LEAKED_RESIDUE: '简历漏删', WRONG_ATTRIBUTION: '归属错误', RELATION_REVOKE: '撤销关联' }[k] || k }
function disputeStatusLabel(s) {
  return { SUBMITTED: '已提交', MERGED: '已合并补证', PENDING_VERIFICATION: '待核验', AWAITING_MANUAL_PROOF: '待人工证明', VERIFIED: '已核验', ESCALATED_COMPLIANCE: '合规裁定中', ADJUDICATED: '已裁定', CLOSED: '已结案' }[s] || s
}
function disputeStatusType(s) {
  return { ADJUDICATED: 'success', CLOSED: 'success', ESCALATED_COMPLIANCE: 'warning', AWAITING_MANUAL_PROOF: 'danger', PENDING_VERIFICATION: 'info', MERGED: 'info' }[s] || ''
}
function impactKindLabel(k) {
  return { SUPPLEMENTAL_DELETE: '补充删除', SHARED_REFERENCE_SPLIT: '共享引用拆分', IRRECOVERABLE_DELETED: '不可恢复', HISTORY_SCOPE_REFERENCE: '历史删除范围引用', REPLY_REFERENCE: '答复引用' }[k] || k
}
function impactStatusLabel(s) {
  return { PENDING: '待处理', SUPPLEMENT_TICKETED: '已进补充工单', SPLIT_PENDING_DISPOSITION: '待拆引用处置', DISPOSED: '已处置', IRRECOVERABLE_RECORDED: '已记不可恢复', NO_ACTION: '留痕无需动作', RESOLVED: '已办结' }[s] || s
}
function impactStatusType(s) {
  return { DISPOSED: 'success', IRRECOVERABLE_RECORDED: 'info', RESOLVED: 'success', NO_ACTION: 'info', SUPPLEMENT_TICKETED: 'warning', SPLIT_PENDING_DISPOSITION: 'warning' }[s] || 'danger'
}
onMounted(() => { loadRelations(); loadAttributions() })
</script>
