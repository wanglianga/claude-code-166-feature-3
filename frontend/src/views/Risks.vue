<template>
  <div>
    <h2 class="page-title">风险事件（自动预警 + 人工上报 + 闭环管理）</h2>
    <div class="card-soft">
      <el-radio-group v-model="filter" size="default" class="mb12" @change="load">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="OPEN">待处理</el-radio-button>
        <el-radio-button label="CONFIRMED">已确认</el-radio-button>
        <el-radio-button label="CLOSED">已闭环</el-radio-button>
      </el-radio-group>
      <el-table :data="risks" v-loading="loading" size="small">
        <el-table-column prop="riskNo" label="编号" width="160" />
        <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patient.name }}</template></el-table-column>
        <el-table-column prop="riskType" label="类型" width="120" />
        <el-table-column label="级别" width="80">
          <template #default="{row}"><el-tag size="small" :type="tagType('RiskSeverity',row.severity)">{{ label('RiskSeverity',row.severity) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column label="报告人" width="90"><template #default="{row}">{{ row.reporter?.name || '系统自动' }}</template></el-table-column>
        <el-table-column label="上报时间" width="150"><template #default="{row}">{{ row.reportedAt?.replace('T',' ').slice(0,16) }}</template></el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{row}"><el-tag size="small" :type="tagType('RiskEventStatus',row.status)">{{ label('RiskEventStatus',row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{row}">
            <el-button v-if="row.status==='OPEN' && canHandle" link type="primary" size="small" @click="open(row,'confirm')">确认处置</el-button>
            <el-button v-if="row.status==='CONFIRMED' && canHandle" link type="success" size="small" @click="open(row,'close')">闭环</el-button>
            <el-button v-if="canReferral" link type="danger" size="small" @click="goReferral(row)">病情恶化转诊</el-button>
            <el-button link type="info" size="small" @click="view(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog :model-value="visible" :title="dialogTitle" width="480px" @update:model-value="visible=$event">
      <el-form label-width="92px">
        <el-form-item label="风险描述"><div>{{ current?.description }}</div></el-form-item>
        <el-form-item v-if="mode==='confirm'" label="处置方案">
          <el-input v-model="handlePlan" type="textarea" :rows="4" placeholder="如：调整用药、增加巡视频次、联系家属、建议转诊等" />
        </el-form-item>
        <el-form-item v-if="mode==='close'" label="闭环说明">
          <el-input v-model="closeNote" type="textarea" :rows="4" placeholder="处置结果、患者当前情况" />
        </el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button>
        <el-button :type="mode==='confirm'?'primary':'success'" @click="submit">提交</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useAuthStore } from '../store/auth'
import { label, tagType } from '../labels'

const auth = useAuthStore()
const router = useRouter()
const canReferral = computed(() => ['NURSE', 'FAMILY_DOCTOR', 'ADMIN'].includes(auth.role))
function goReferral(row) {
  router.push({
    path: '/referrals',
    query: { patientId: row.patient.id, visitId: row.visit?.id || undefined, riskEventId: row.id }
  })
}
const risks = ref([])
const loading = ref(true)
const filter = ref('OPEN')
const visible = ref(false)
const mode = ref('confirm')
const current = ref(null)
const handlePlan = ref('')
const closeNote = ref('')
const canHandle = computed(() => ['ADMIN', 'FAMILY_DOCTOR'].includes(auth.role))
const dialogTitle = computed(() => mode.value === 'confirm' ? '确认风险并制定处置方案' : '风险事件闭环')

async function load() {
  loading.value = true
  risks.value = (await api.get('/risks' + (filter.value ? '?status=' + filter.value : ''))).data
  loading.value = false
}
onMounted(load)

function open(row, m) { current.value = row; mode.value = m; handlePlan.value = row.handlePlan || ''; closeNote.value = ''; visible.value = true }
function view(row) { current.value = row; mode.value = 'close'; visible.value = true }
async function submit() {
  if (mode.value === 'confirm') {
    if (!handlePlan.value) return ElMessage.warning('请填写处置方案')
    await api.post(`/risks/${current.value.id}/confirm`, { handlePlan: handlePlan.value })
  } else {
    if (!closeNote.value && current.value.status === 'CONFIRMED') return ElMessage.warning('请填写闭环说明')
    await api.post(`/risks/${current.value.id}/close`, { closeNote: closeNote.value || '已闭环' })
  }
  ElMessage.success('已提交')
  visible.value = false
  load()
}
</script>

<style scoped>
.mb12 { margin-bottom: 12px; }
</style>
