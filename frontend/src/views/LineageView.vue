<template>
  <div>
    <h2 class="page-title">资产登记与血缘注册</h2>
    <el-card class="section-card">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
        title="接入登记与血缘注册绑定为一次事务：父资产未登记时整体回滚。断链边不自动跨越，枚举时转人工确认点。" />
      <el-form inline>
        <el-form-item label="资产编码"><el-input v-model="form.assetCode" placeholder="AST_XXX" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="form.assetName" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.storageType" style="width:130px">
            <el-option v-for="t in storageTypes" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="物理引用"><el-input v-model="form.targetRef" placeholder="biz.xxx" /></el-form-item>
        <el-form-item label="默认处置">
          <el-select v-model="form.dispositionDefault" style="width:160px">
            <el-option v-for="d in dispositions" :key="d.value" :label="d.label" :value="d.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="父资产（血缘）">
          <el-select v-model="form.parentAssetCodes" multiple filterable allow-create default-first-option
            placeholder="选择父资产编码" style="width:240px">
            <el-option v-for="a in assets" :key="a.asset_code" :label="a.asset_code" :value="a.asset_code" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="register">同事务登记</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="16">
      <el-col :span="14">
        <el-card>
          <template #header>已登记资产（{{ assets.length }}）</template>
          <el-table :data="assets" border size="small" max-height="520">
            <el-table-column prop="asset_code" label="编码" width="170" />
            <el-table-column prop="asset_name" label="名称" width="150" />
            <el-table-column prop="storage_type" label="类型" width="100" />
            <el-table-column prop="target_ref" label="物理引用" />
            <el-table-column label="默认处置" width="150">
              <template #default="{ row }">
                <el-tag :type="dispType(row.disposition_default)">{{ dispLabel(row.disposition_default) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card style="margin-bottom:16px">
          <template #header>血缘边</template>
          <el-table :data="edges" border size="small" max-height="300">
            <el-table-column prop="from_asset_code" label="来源" width="150" />
            <el-table-column label="边" width="70">
              <template #default="{ row }">
                <el-tag v-if="row.broken" type="danger" size="small">断链</el-tag>
                <span v-else>→</span>
              </template>
            </el-table-column>
            <el-table-column prop="to_asset_code" label="目标" />
          </el-table>
        </el-card>
        <el-card>
          <template #header>断链人工确认点</template>
          <el-table :data="gaps" border size="small">
            <el-table-column prop="asset_code" label="资产" width="140" />
            <el-table-column prop="description" label="说明" show-overflow-tooltip />
            <el-table-column label="状态" width="150">
              <template #default="{ row }">
                <el-tag :type="row.status === 'OPEN' ? 'danger' : 'success'">{{ gapLabel(row.status) }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import http from '../api/http'
import { ElMessage } from 'element-plus'

const storageTypes = ['TABLE', 'PARTITION', 'FILE', 'INDEX', 'FEATURE', 'SAMPLE', 'EXPORT', 'BACKUP', 'EXTERNAL']
const dispositions = [
  { value: 'PHYSICAL_DELETE', label: '物理清除' },
  { value: 'RECOMPUTE_AGGREGATE', label: '重算聚合' },
  { value: 'WAIT_RETENTION', label: '等待保留期' },
  { value: 'EXTERNAL_NOTIFY', label: '流出通知' }
]
const form = reactive({ assetCode: '', assetName: '', storageType: 'TABLE', targetRef: '', dispositionDefault: 'PHYSICAL_DELETE', parentAssetCodes: [] })
const assets = ref([])
const edges = ref([])
const gaps = ref([])

async function load() {
  assets.value = (await http.get('/api/assets')).data
  edges.value = (await http.get('/api/lineage/edges')).data
  gaps.value = (await http.get('/api/lineage/gaps')).data
}
async function register() {
  await http.post('/api/assets/register', { ...form })
  ElMessage.success('资产与血缘已在同一事务登记')
  form.assetCode = ''
  form.assetName = ''
  form.targetRef = ''
  form.parentAssetCodes = []
  await load()
}
function dispLabel(v) {
  return Object.fromEntries(dispositions.map((d) => [d.value, d.label]))[v] || v
}
function dispType(v) {
  return { PHYSICAL_DELETE: 'danger', RECOMPUTE_AGGREGATE: 'warning', WAIT_RETENTION: 'info', EXTERNAL_NOTIFY: 'primary' }[v]
}
function gapLabel(s) {
  return { OPEN: '待人工确认', CONFIRMED_IN_SCOPE: '已确认纳入', CONFIRMED_OUT_SCOPE: '已确认排除' }[s] || s
}
onMounted(load)
</script>
