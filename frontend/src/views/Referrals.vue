<template>
  <div>
    <div class="head-line">
      <h2 class="page-title" style="margin:0">病情恶化转诊闭环（高危标记 → 三方通知 → 救护车 → 费用截止 → 出院恢复）</h2>
      <div>
        <el-radio-group v-model="filter" size="default" @change="load">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="active">进行中</el-radio-button>
          <el-radio-button label="CRITICAL_FLAGGED">高危已标记</el-radio-button>
          <el-radio-button label="AMBULANCE_DISPATCHED">转运中</el-radio-button>
          <el-radio-button label="REFERRED">待诊断</el-radio-button>
          <el-radio-button label="DISCHARGED">待出院决定</el-radio-button>
          <el-radio-button label="closed">已闭环</el-radio-button>
        </el-radio-group>
        <el-button v-if="canFlag" type="danger" class="ml8" @click="openFlag">标记高危转诊</el-button>
      </div>
    </div>

    <div class="card-soft">
      <el-table :data="referrals" v-loading="loading" size="small">
        <el-table-column prop="referralNo" label="转诊单号" width="180" />
        <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patient.name }}</template></el-table-column>
        <el-table-column prop="symptom" label="恶化表现" width="150" show-overflow-tooltip />
        <el-table-column label="转诊医院" width="140">
          <template #default="{row}">{{ row.admittedHospital || row.targetHospital || '-' }}</template>
        </el-table-column>
        <el-table-column label="费用截至" width="105">
          <template #default="{row}">{{ row.feeCutoffAt ? row.feeCutoffAt.replace('T',' ').slice(0,10) : '待核定' }}</template>
        </el-table-column>
        <el-table-column label="待审核账单" width="90" align="center">
          <template #default="{row}">
            <el-tag v-if="row.pendingBillCount>0" size="small" type="danger">{{ row.pendingBillCount }}</el-tag>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{row}"><el-tag size="small" :type="tagType('ReferralStatus',row.status)">{{ label('ReferralStatus',row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="标记时间" width="150"><template #default="{row}">{{ row.flaggedAt?.replace('T',' ').slice(0,16) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{row}"><el-button link type="primary" size="small" @click="openDetail(row)">闭环详情</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 标记高危 -->
    <el-dialog v-model="flagVisible" title="护士巡诊发现高危·立即标记并通知三方" width="560px">
      <el-form :model="flagForm" label-width="104px">
        <el-form-item label="患者" required>
          <el-select v-model="flagForm.patientId" filterable placeholder="选择在管患者" style="width:100%">
            <el-option v-for="p in patients" :key="p.id"
                       :label="`${p.name} ${p.gender}/${p.age}岁 · ${p.address}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联巡诊">
          <el-select v-model="flagForm.visitId" clearable placeholder="现场巡诊任务（可空=电话/其他发现）" style="width:100%">
            <el-option v-for="v in patientVisits" :key="v.id"
                       :label="`${v.visitNo} ${v.scheduledAt?.replace('T',' ').slice(0,16)} ${label('VisitStatus',v.status)}`"
                       :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="高危表现" required>
          <el-input v-model="flagForm.symptom" placeholder="如：突发呼吸困难、口唇发绀、血氧88%" />
        </el-form-item>
        <el-form-item label="现场情况">
          <el-input v-model="flagForm.description" type="textarea" :rows="3"
                    placeholder="体征/意识/处置经过，将同步推送给家庭医生与转诊医院" />
        </el-form-item>
        <el-form-item label="转诊医院">
          <el-input v-model="flagForm.targetHospital" placeholder="默认 县人民医院急诊科" />
        </el-form-item>
      </el-form>
      <el-alert type="error" :closable="false" show-icon
                title="提交后立即通知【家庭医生】【家属】【转诊医院】，并自动生成高危风险事件、挂起当前巡诊任务" />
      <template #footer><el-button @click="flagVisible=false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="submitFlag">立即标记高危</el-button></template>
    </el-dialog>

    <!-- 闭环详情 -->
    <el-drawer v-model="detailVisible" size="660px"
               :title="'转诊闭环 ' + (detail?.referral?.referralNo || '')">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="患者">{{ detail.referral.patient.name }} {{ detail.referral.patient.gender }}/{{ detail.referral.patient.age }}岁</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="tagType('ReferralStatus',detail.referral.status)">{{ label('ReferralStatus',detail.referral.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="高危表现" :span="2">{{ detail.referral.symptom }}</el-descriptions-item>
          <el-descriptions-item label="标记人">{{ detail.referral.flaggedBy?.name || '-' }} @ {{ fmt(detail.referral.flaggedAt) }}</el-descriptions-item>
          <el-descriptions-item label="家庭病床">{{ detail.referral.application?.bedNo || '-' }}
            <el-tag v-if="detail.referral.application" size="small" :type="tagType('BedApplyStatus',detail.referral.application.status)">{{ label('BedApplyStatus',detail.referral.application.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="远程判断医生">{{ detail.referral.judgeDoctor?.name || '-' }}</el-descriptions-item>
          <el-descriptions-item label="判断时间">{{ fmt(detail.referral.judgedAt) }}</el-descriptions-item>
          <el-descriptions-item label="救护车出发" :span="2">{{ fmt(detail.referral.ambulanceDispatchedAt) }} {{ detail.referral.ambulanceNote }}</el-descriptions-item>
          <el-descriptions-item label="接诊医院" :span="2">{{ detail.referral.admittedHospital || '-' }}</el-descriptions-item>
          <el-descriptions-item label="入院时间">{{ fmt(detail.referral.admittedAt) }}</el-descriptions-item>
          <el-descriptions-item label="诊断来源">{{ detail.referral.diagnosisSource ? label('DiagnosisSource',detail.referral.diagnosisSource) : '-' }}</el-descriptions-item>
          <el-descriptions-item label="入院诊断" :span="2">{{ detail.referral.admissionDiagnosis || '等待医院回传/家属补充' }}</el-descriptions-item>
          <el-descriptions-item label="费用截至时间" :span="2">
            <b v-if="detail.referral.feeCutoffAt" style="color:#f56c6c">{{ fmt(detail.referral.feeCutoffAt) }}</b>
            <span v-else class="muted">未核定（救护车出发/实际入院较早者）</span>
            <div class="muted" v-if="detail.referral.feeCutoffNote">{{ detail.referral.feeCutoffNote }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="出院日期">{{ detail.referral.dischargeDate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="医生决定">
            {{ detail.referral.status==='RESUMED' ? '恢复家庭病床' : detail.referral.status==='WITHDRAWN' ? '撤床' : '-' }}
          </el-descriptions-item>
        </el-descriptions>

        <!-- 阶段时间线 -->
        <div class="section-title mt-16">闭环进度</div>
        <el-steps direction="vertical" :active="activeStep" :process-status="stepStatus">
          <el-step title="高危标记 + 三方通知" :description="step1" />
          <el-step title="医生远程判断 · 救护车转运 · 暂停家庭病床" :description="step2" />
          <el-step title="转诊送达 · 等待入院诊断" :description="step3" />
          <el-step title="诊断回传 · 费用截止核定" :description="step4" />
          <el-step title="出院 · 医生决定恢复/撤床" :description="step5" />
        </el-steps>

        <!-- 三方通知留痕 -->
        <div class="section-title mt-16">三方通知留痕（{{ detail.notifications.length }}）</div>
        <el-table :data="detail.notifications" size="small" border>
          <el-table-column prop="receiverName" label="接收方" width="150" />
          <el-table-column prop="channel" label="渠道" width="110" />
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
          <el-table-column label="时间" width="140"><template #default="{row}">{{ fmt(row.sentAt) }}</template></el-table-column>
        </el-table>

        <!-- 冻结/待审核账单 -->
        <div class="section-title mt-16">转诊待审核账单（{{ detail.frozenBills.length }}）</div>
        <el-empty v-if="!detail.frozenBills.length" description="无关联冻结账单" :image-size="50" />
        <el-table v-else :data="detail.frozenBills" size="small" border>
          <el-table-column prop="billNo" label="账单号" width="170" />
          <el-table-column label="金额" width="90"><template #default="{row}">¥{{ row.totalAmount }}</template></el-table-column>
          <el-table-column label="截至剔除" width="80"><template #default="{row}">{{ row.cutoffExcludedCount || 0 }} 行</template></el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{row}"><el-tag size="small" :type="tagType('BillStatus',row.status)">{{ label('BillStatus',row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{row}">
              <el-button v-if="canReviewBill && row.status==='PENDING_REVIEW'" link type="success" size="small"
                         @click="reviewBill(row,true)">审核放行</el-button>
              <el-button v-if="canReviewBill && row.status==='PENDING_REVIEW'" link type="danger" size="small"
                         @click="reviewBill(row,false)">驳回</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="muted mt-12">费用截至时间后的护理/耗材明细在账单中标记剔除并清零，住院期间不会按巡诊计划继续计费。</div>

        <!-- 操作区 -->
        <div class="footer-actions">
          <template v-if="isDoctor && r.status==='CRITICAL_FLAGGED'">
            <el-button @click="openHold">远程判断·暂不转运</el-button>
            <el-button type="danger" @click="openAmbulance">远程判断·发起救护车</el-button>
          </template>
          <el-button v-if="r.status==='AMBULANCE_DISPATCHED'" type="warning" @click="openReferred">确认已送达医院</el-button>
          <el-button v-if="r.status==='REFERRED' || r.status==='AMBULANCE_DISPATCHED'" type="primary" @click="openDiagnosis">
            {{ isFamily ? '家属补充入院诊断' : '回传入院诊断·核定费用截止' }}
          </el-button>
          <el-button v-if="r.status==='DIAGNOSIS_RECEIVED'" @click="openDischarge">登记出院</el-button>
          <template v-if="isDoctor && (r.status==='DISCHARGED' || r.status==='DIAGNOSIS_RECEIVED')">
            <el-button type="success" @click="openResume">出院后恢复家庭病床</el-button>
            <el-button @click="openWithdraw">评估后撤床</el-button>
          </template>
        </div>
      </template>
    </el-drawer>

    <!-- 救护车 -->
    <el-dialog v-model="ambVisible" title="医生远程判断 · 发起救护车转运" width="520px">
      <el-form label-width="100px">
        <el-form-item label="转诊医院"><el-input v-model="ambForm.targetHospital" placeholder="县人民医院急诊科" /></el-form-item>
        <el-form-item label="远程判断"><el-input v-model="ambForm.judgeNote" type="textarea" :rows="3"
          placeholder="据巡诊体征/视频判断：呼吸窘迫、血氧…，符合上转指征" /></el-form-item>
        <el-form-item label="随车备注"><el-input v-model="ambForm.ambulanceNote" type="textarea" :rows="2"
          placeholder="吸氧/体位/用药/交接重点" /></el-form-item>
      </el-form>
      <el-alert type="warning" :closable="false" show-icon
                title="发起后：家庭病床与巡诊计划立即暂停、后续待上门巡诊取消、未结算账单进入待审核并通知家属与医院" />
      <template #footer><el-button @click="ambVisible=false">取消</el-button>
        <el-button type="danger" @click="submitAmbulance">确认发起救护车</el-button></template>
    </el-dialog>

    <!-- 就地处置 -->
    <el-dialog v-model="holdVisible" title="远程判断 · 暂不救护车转运" width="460px">
      <el-input v-model="holdNote" type="textarea" :rows="4" placeholder="就地/门诊处置意见与继续观察要点" />
      <template #footer><el-button @click="holdVisible=false">取消</el-button>
        <el-button type="primary" @click="submitHold">记录判断</el-button></template>
    </el-dialog>

    <!-- 送达 -->
    <el-dialog v-model="refVisible" title="确认患者送达医院" width="460px">
      <el-form label-width="100px">
        <el-form-item label="接诊医院"><el-input v-model="refForm.admittedHospital" /></el-form-item>
        <el-form-item label="实际入院时间"><el-date-picker v-model="refForm.admittedAt" type="datetime"
          value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="refForm.note" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="refVisible=false">取消</el-button>
        <el-button type="primary" @click="submitReferred">确认送达</el-button></template>
    </el-dialog>

    <!-- 诊断回传 -->
    <el-dialog v-model="diagVisible" :title="isFamily ? '家属补充入院诊断' : '医院回传入院诊断 · 核定费用截止'" width="520px">
      <el-form label-width="104px">
        <el-form-item v-if="!isFamily" label="诊断来源">
          <el-radio-group v-model="diagForm.source">
            <el-radio label="HOSPITAL_CALLBACK">医院回传</el-radio>
            <el-radio label="FAMILY_SUPPLEMENT">家属补充</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="实际入院时间">
          <el-date-picker v-model="diagForm.admittedAt" type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
        </el-form-item>
        <el-form-item label="入院诊断" required>
          <el-input v-model="diagForm.admissionDiagnosis" type="textarea" :rows="3"
            placeholder="如：慢性阻塞性肺病急性加重、Ⅱ型呼吸衰竭" />
        </el-form-item>
      </el-form>
      <el-alert type="info" :closable="false" show-icon
                title="提交后按【救护车出发时间与实际入院时间较早者】确定家庭病床费用截至时间，此后服务不计入账单" />
      <template #footer><el-button @click="diagVisible=false">取消</el-button>
        <el-button type="primary" @click="submitDiagnosis">提交并核定费用截止</el-button></template>
    </el-dialog>

    <!-- 出院 -->
    <el-dialog v-model="dischVisible" title="登记患者出院" width="440px">
      <el-form label-width="92px">
        <el-form-item label="出院日期"><el-date-picker v-model="dischForm.dischargeDate" type="date"
          value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="出院情况"><el-input v-model="dischForm.dischargeNote" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dischVisible=false">取消</el-button>
        <el-button type="primary" @click="submitDischarge">登记出院（待医生决定）</el-button></template>
    </el-dialog>

    <!-- 恢复 -->
    <el-dialog v-model="resumeVisible" title="出院后恢复家庭病床" width="460px">
      <el-form label-width="104px">
        <el-form-item label="首次上门日"><el-date-picker v-model="resumeForm.resumeVisitDate" type="date"
          value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="恢复意见"><el-input v-model="resumeForm.decisionNote" type="textarea" :rows="3"
          placeholder="出院后评估可继续家庭病床，计划恢复并重新排班；待审核账单解冻" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="resumeVisible=false">取消</el-button>
        <el-button type="success" @click="submitResume">确认恢复</el-button></template>
    </el-dialog>

    <!-- 撤床 -->
    <el-dialog v-model="withdrawVisible" title="评估后撤床（不再恢复家庭病床）" width="440px">
      <el-input v-model="withdrawNote" type="textarea" :rows="4" placeholder="撤床依据与后续随访建议" />
      <template #footer><el-button @click="withdrawVisible=false">取消</el-button>
        <el-button type="danger" @click="submitWithdraw">确认撤床闭环</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useAuthStore } from '../store/auth'
import { label, tagType } from '../labels'

const route = useRoute()

const auth = useAuthStore()
const isDoctor = computed(() => auth.isDoctor || auth.isAdmin)
const isFamily = computed(() => auth.isFamily)
const canFlag = computed(() => auth.isNurse || auth.isDoctor || auth.isAdmin)
const canReviewBill = computed(() => auth.isFinance || auth.isAdmin || auth.isInsurance)

const referrals = ref([])
const patients = ref([])
const patientVisits = ref([])
const loading = ref(true)
const filter = ref('active')
const detailVisible = ref(false)
const detail = ref(null)

const r = computed(() => detail.value?.referral || {})

const CLOSED = ['RESUMED', 'WITHDRAWN']
const activeStep = computed(() => {
  const s = r.value.status
  if (s === 'CRITICAL_FLAGGED') return 1
  if (s === 'AMBULANCE_DISPATCHED') return 2
  if (s === 'REFERRED') return 3
  if (s === 'DIAGNOSIS_RECEIVED') return 4
  if (s === 'DISCHARGED') return 5
  return 5
})
const stepStatus = computed(() => CLOSED.includes(r.value.status) ? 'success' : 'process')
const fmt = t => t ? t.replace('T', ' ').slice(0, 16) : '-'
const step1 = computed(() => `${fmt(r.value.flaggedAt)} 标记人 ${r.value.flaggedBy?.name || '-'}；通知：${r.value.notifySnapshot || '-'}`)
const step2 = computed(() => r.value.ambulanceDispatchedAt
  ? `${fmt(r.value.ambulanceDispatchedAt)} 救护车出发 → ${r.value.targetHospital}；${r.value.judgeNote || ''}`
  : (r.value.judgeNote ? '医生判断：' + r.value.judgeNote : '等待医生远程判断'))
const step3 = computed(() => r.value.referredAt ? `${fmt(r.value.referredAt)} 送达 ${r.value.admittedHospital || ''}` : '等待送达')
const step4 = computed(() => r.value.feeCutoffAt
  ? `${label('DiagnosisSource', r.value.diagnosisSource)}：${r.value.admissionDiagnosis}；费用截至 ${fmt(r.value.feeCutoffAt)}`
  : '等待医院回传/家属补充入院诊断')
const step5 = computed(() => {
  if (r.value.status === 'RESUMED') return `家庭病床已恢复，首次上门 ${r.value.resumeVisitDate || '待排班'}`
  if (r.value.status === 'WITHDRAWN') return '已撤床闭环'
  return r.value.dischargeDate ? `${r.value.dischargeDate} 出院，待医生决定恢复/撤床` : '等待出院登记'
})

async function load() {
  loading.value = true
  let url = '/referrals'
  if (filter.value === 'active') url += '?activeOnly=true'
  else if (filter.value === 'closed') url += '?status=' + encodeURIComponent('RESUMED')
  else if (filter.value) url += '?status=' + filter.value
  referrals.value = (await api.get(url)).data
  if (filter.value === 'closed') {
    const wd = (await api.get('/referrals?status=WITHDRAWN')).data
    referrals.value = referrals.value.concat(wd)
  }
  loading.value = false
}
onMounted(async () => {
  await load()
  if (canFlag.value) patients.value = (await api.get('/patients')).data
  if (route.query.referralId) {
    openDetail({ id: Number(route.query.referralId) })
  } else if (route.query.patientId && canFlag.value) {
    flagForm.patientId = Number(route.query.patientId)
    if (route.query.visitId) flagForm.visitId = Number(route.query.visitId)
    if (route.query.riskEventId) flagForm.riskEventId = Number(route.query.riskEventId)
    flagForm.symptom = '突发呼吸困难、气促明显'
    flagForm.description = ''
    flagForm.targetHospital = '县人民医院急诊科'
    await onPatient()
    flagVisible.value = true
  }
})

// 高危标记
const flagVisible = ref(false)
const submitting = ref(false)
const flagForm = reactive({})
async function openFlag() {
  Object.assign(flagForm, { patientId: null, visitId: null, symptom: '突发呼吸困难、气促明显', description: '', targetHospital: '县人民医院急诊科' })
  patientVisits.value = []
  flagVisible.value = true
}
async function onPatient() {
  if (!flagForm.patientId) { patientVisits.value = []; return }
  const all = (await api.get('/visits?patientId=' + flagForm.patientId)).data
  patientVisits.value = all.filter(v => ['SCHEDULED', 'IN_PROGRESS', 'EXCEPTION'].includes(v.status))
}
watch(() => flagForm.patientId, onPatient)

async function submitFlag() {
  if (!flagForm.patientId || !flagForm.symptom) return ElMessage.warning('请选择患者并填写高危表现')
  submitting.value = true
  try {
    const created = (await api.post('/referrals/flag', flagForm)).data
    ElMessage.success('已标记高危，家庭医生/家属/转诊医院已收到通知')
    flagVisible.value = false
    await load()
    openDetail(created)
  } finally { submitting.value = false }
}

async function openDetail(row) {
  detail.value = (await api.get('/referrals/' + row.id)).data
  detailVisible.value = true
}
function refreshDetail() { return openDetail({ id: detail.value.referral.id }) }

// 救护车
const ambVisible = ref(false)
const ambForm = reactive({})
function openAmbulance() {
  Object.assign(ambForm, { targetHospital: r.value.targetHospital || '县人民医院急诊科', judgeNote: '', ambulanceNote: '' })
  ambVisible.value = true
}
async function submitAmbulance() {
  if (!ambForm.judgeNote) return ElMessage.warning('请填写远程判断意见')
  await api.post(`/referrals/${r.value.id}/ambulance`, ambForm)
  ElMessage.success('救护车已发起，家庭病床与巡诊计划已暂停，未结算账单已冻结')
  ambVisible.value = false
  await refreshDetail(); load()
}

// 就地
const holdVisible = ref(false)
const holdNote = ref('')
function openHold() { holdNote.value = ''; holdVisible.value = true }
async function submitHold() {
  if (!holdNote.value) return ElMessage.warning('请填写判断意见')
  await api.post(`/referrals/${r.value.id}/hold`, { judgeNote: holdNote.value })
  ElMessage.success('已记录就地处置意见')
  holdVisible.value = false
  await refreshDetail()
}

// 送达
const refVisible = ref(false)
const refForm = reactive({})
function openReferred() {
  Object.assign(refForm, { admittedHospital: r.value.targetHospital, admittedAt: nowStr(), note: '' })
  refVisible.value = true
}
async function submitReferred() {
  await api.post(`/referrals/${r.value.id}/referred`, refForm)
  ElMessage.success('已确认送达，等待入院诊断回传')
  refVisible.value = false
  await refreshDetail(); load()
}

// 诊断
const diagVisible = ref(false)
const diagForm = reactive({})
function openDiagnosis() {
  Object.assign(diagForm, { source: isFamily.value ? 'FAMILY_SUPPLEMENT' : 'HOSPITAL_CALLBACK', admittedAt: r.value.admittedAt || nowStr(), admissionDiagnosis: '' })
  diagVisible.value = true
}
async function submitDiagnosis() {
  if (!diagForm.admissionDiagnosis) return ElMessage.warning('请填写入院诊断')
  await api.post(`/referrals/${r.value.id}/diagnosis`, diagForm)
  ElMessage.success('入院诊断已登记，家庭病床费用截至时间已核定')
  diagVisible.value = false
  await refreshDetail(); load()
}

// 出院
const dischVisible = ref(false)
const dischForm = reactive({})
function openDischarge() {
  Object.assign(dischForm, { dischargeDate: todayStr(), dischargeNote: '' })
  dischVisible.value = true
}
async function submitDischarge() {
  await api.post(`/referrals/${r.value.id}/discharge`, dischForm)
  ElMessage.success('已登记出院，等待家庭医生决定是否恢复家庭病床')
  dischVisible.value = false
  await refreshDetail(); load()
}

// 恢复
const resumeVisible = ref(false)
const resumeForm = reactive({})
function openResume() {
  Object.assign(resumeForm, { resumeVisitDate: todayPlus(3), decisionNote: '' })
  resumeVisible.value = true
}
async function submitResume() {
  await api.post(`/referrals/${r.value.id}/resume`, resumeForm)
  ElMessage.success('家庭病床已恢复，巡诊计划重启，冻结账单已解冻')
  resumeVisible.value = false
  await refreshDetail(); load()
}

// 撤床
const withdrawVisible = ref(false)
const withdrawNote = ref('')
function openWithdraw() { withdrawNote.value = ''; withdrawVisible.value = true }
async function submitWithdraw() {
  if (!withdrawNote.value) return ElMessage.warning('请填写撤床依据')
  await api.post(`/referrals/${r.value.id}/withdraw`, { decisionNote: withdrawNote.value })
  ElMessage.success('已撤床，转诊闭环完成')
  withdrawVisible.value = false
  await refreshDetail(); load()
}

async function reviewBill(bill, approved) {
  let note = approved ? '按费用截止核定无误' : ''
  try {
    const res = await ElMessageBox.prompt(
      approved ? '审核意见（放行后退回家属确认）' : '驳回原因（账单维持冻结）',
      '转诊待审核账单', { inputType: 'textarea', inputValue: note })
    note = res.value || note
  } catch (e) {
    return
  }
  await api.post(`/referrals/${r.value.id}/bills/${bill.id}/review`, { approved, auditNote: note })
  ElMessage.success(approved ? '账单已放行，退回家属确认' : '账单维持冻结')
  await refreshDetail(); load()
}

function pad(n) { return String(n).padStart(2, '0') }
function nowStr() {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:00`
}
function todayStr() { return nowStr().slice(0, 10) }
function todayPlus(days) {
  const d = new Date(Date.now() + days * 864e5)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
</script>

<style scoped>
.head-line { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; flex-wrap: wrap; gap: 8px; }
.section-title { font-weight: 600; margin-bottom: 8px; }
.mt-16 { margin-top: 16px } .mt-12 { margin-top: 12px } .ml8 { margin-left: 8px }
.footer-actions { margin-top: 20px; text-align: right; border-top: 1px dashed #ddd; padding-top: 14px; }
</style>
