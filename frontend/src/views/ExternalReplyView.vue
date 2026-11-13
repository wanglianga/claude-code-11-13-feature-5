<template>
  <div>
    <h2 class="page-title">对外答复（版本化）</h2>
    <el-alert type="info" :closable="false" style="margin-bottom:12px"
      title="只有异议裁定涉及的全部影响项均取得明确处理结果，才允许生成新的对外说明；旧答复标记“已被取代”但物理保留，可按日期查回。" />

    <el-card class="section-card">
      <el-form inline>
        <el-form-item label="异议 ID">
          <el-input v-model.number="genForm.disputeId" style="width:120px" placeholder="如 1" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="genForm.title" style="width:260px" placeholder="留空使用默认标题" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="generate">生成对外说明</el-button>
        </el-form-item>
      </el-form>
      <el-input v-model="genForm.bodyText" type="textarea" :rows="2" placeholder="正文可留空，使用系统默认说明" />
    </el-card>

    <el-card class="section-card">
      <el-form inline>
        <el-form-item label="按异议过滤">
          <el-input v-model.number="filterDisputeId" style="width:120px" placeholder="异议 ID" @change="load" />
        </el-form-item>
        <el-form-item label="按主体哈希过滤">
          <el-input v-model="filterSubject" style="width:300px" placeholder="64 位主体哈希" @change="load" />
        </el-form-item>
        <el-form-item><el-button @click="load">查询版本</el-button></el-form-item>
      </el-form>
      <el-table :data="replies" border stripe size="small" v-loading="loading">
        <el-table-column prop="reply_no" label="答复号" width="220" />
        <el-table-column prop="dispute_id" label="异议" width="80" />
        <el-table-column prop="reply_version" label="版本" width="70" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.reply_status === 'ISSUED' ? 'success' : 'info'">
              {{ row.reply_status === 'ISSUED' ? '现行' : '已被取代' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="issued_at" label="出具时间" min-width="180" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="viewBody(row)">正文</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card>
      <template #header>按日期查回原答复（含已被取代的历史版本）</template>
      <el-form inline>
        <el-form-item label="主体哈希">
          <el-input v-model="traceSubject" style="width:340px" placeholder="64 位主体哈希" />
        </el-form-item>
        <el-form-item label="日期（留空=今天）">
          <el-date-picker v-model="traceDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" />
        </el-form-item>
        <el-form-item><el-button type="primary" @click="trace">查回</el-button></el-form-item>
      </el-form>
      <el-descriptions v-if="traced" :column="1" border size="small">
        <el-descriptions-item label="答复号">{{ traced.reply_no }}（V{{ traced.reply_version }}）</el-descriptions-item>
        <el-descriptions-item label="状态">{{ traced.reply_status === 'ISSUED' ? '现行' : '已被取代' }}</el-descriptions-item>
        <el-descriptions-item label="出具时间">{{ traced.issued_at }}</el-descriptions-item>
        <el-descriptions-item label="正文"><pre class="pre-text">{{ traced.body_text }}</pre></el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-dialog v-model="bodyOpen" :title="`答复正文 ${current?.reply_no ?? ''}`" width="600px">
      <pre class="pre-text">{{ current?.body_text }}</pre>
    </el-dialog>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import http from '../api/http'
import { ElMessage } from 'element-plus'

const loading = ref(false)
const replies = ref([])
const genForm = reactive({ disputeId: null, title: '', bodyText: '' })
const filterDisputeId = ref(null)
const filterSubject = ref('')
const traceSubject = ref('')
const traceDate = ref('')
const traced = ref(null)
const bodyOpen = ref(false)
const current = ref(null)

async function generate() {
  if (!genForm.disputeId) { ElMessage.warning('请填写异议 ID'); return }
  loading.value = true
  try {
    const { data } = await http.post(`/api/replies/disputes/${genForm.disputeId}`, {
      title: genForm.title || null, bodyText: genForm.bodyText || null
    })
    ElMessage.success(`已生成 V${data.replyVersion}（${data.replyNo}）`)
    await load()
  } finally { loading.value = false }
}
async function load() {
  loading.value = true
  try {
    const params = {}
    if (filterDisputeId.value) params.disputeId = filterDisputeId.value
    if (filterSubject.value) params.subjectHash = filterSubject.value
    replies.value = (await http.get('/api/replies', { params })).data
  } finally { loading.value = false }
}
async function trace() {
  if (!traceSubject.value) { ElMessage.warning('请填写主体哈希'); return }
  const params = { subjectHash: traceSubject.value }
  if (traceDate.value) params.date = traceDate.value
  traced.value = (await http.get('/api/replies/by-date', { params })).data
}
function viewBody(row) { current.value = row; bodyOpen.value = true }
load()
</script>

<style scoped>
.pre-text { white-space: pre-wrap; font-family: inherit; margin: 0; line-height: 1.7; }
</style>
