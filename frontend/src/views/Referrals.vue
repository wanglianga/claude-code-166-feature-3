<template>
  <div>
    <div class="head-line">
      <h2 class="page-title" style="margin:0">病情恶化转诊闭环（标记高危 → 远程判断 → 救护车转运 → 入院回传 → 出院恢复）</h2>
      <el-button v-if="canMark" type="danger" @click="openMark">标记病情恶化（呼吸困难等）</el-button>
    </div>

    <!-- 我的转诊通知 -->
    <div v-if="notifications.length" class="card-soft notify-box">
      <div class="section-title">我的转诊通知（{{ unreadCount }} 条未读）</div>
      <div v-for="n in notifications.slice(0, 5)" :key="n.id" class="notify-item" :class="{ unread: !n.readAt }">
        <el-tag size="small" :type="n.recipientType === 'HOSPITAL' ? 'danger' : 'primary'" effect="plain">
          {{ label('NotifyRecipientType', n.recipientType) }}
        </el-tag>
        <span class="notify-content">{{ n.content }}</span>
        <span class="muted">{{ n.sentAt?.replace('T', ' ').slice(0, 16) }}</span>
        <el-button v-if="!n.readAt" link type="primary" size="small" @click="readNotify(n)">已读</el-button>
      </div>
    </div>

    <div class="card-soft">
      <el-radio-group v-model="filter" size="default" class="mb12" @change="load">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button v-for="(v, k) in ENUMS.ReferralStatus" :key="k" :label="k">{{ v }}</el-radio-button>
      </el-radio-group>
      <el-table :data="referrals" v-loading="loading" size="small">
        <el-table-column prop="referralNo" label="转诊单号" width="150" />
        <el-table-column label="患者" width="90"><template #default="{ row }">{{ row.patient.name }}</template></el-table-column>
        <el-table-column prop="symptom" label="症状" width="100">
          <template #default="{ row }"><el-tag size="small" type="danger" effect="plain">{{ row.symptom }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="targetHospital" label="转诊医院" width="110"><template #default="{ row }">{{ row.targetHospital || '-' }}</template></el-table-column>
        <el-table-column label="状态" width="180">
          <template #default="{ row }"><el-tag size="small" :type="tagType('ReferralStatus', row.status)">{{ label('ReferralStatus', row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="费用截至" width="150">
          <template #default="{ row }">
            <span v-if="row.feeCutoffAt" style="color:#c45656">{{ row.feeCutoffAt.replace('T', ' ').slice(0, 16) }}</span>
            <span v-else class="muted">待入院回传</span>
          </template>
        </el-table-column>
        <el-table-column label="标记时间" width="150"><template #default="{ row }">{{ row.createdAt?.replace('T', ' ').slice(0, 16) }}</template></el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }"><el-button link type="primary" size="small" @click="open(row)">详情/处理</el-button></template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 标记病情恶化 -->
    <el-dialog v-model="markVisible" title="标记病情恶化·立即通知三方" width="520px">
      <el-alert type="error" :closable="false" show-icon class="mb12"
                title="提交后将立即标记高危，并同时通知家庭医生、家属与转诊医院" />
      <el-form label-width="92px">
        <el-form-item label="患者">
          <el-select v-model="markForm.patientId" style="width:100%" placeholder="选择患者">
            <el-option v-for="p in patients" :key="p.id" :label="`${p.name}（${p.age}岁·${p.address}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="主要症状">
          <el-input v-model="markForm.symptom" placeholder="如：呼吸困难" />
        </el-form-item>
        <el-form-item label="情况描述">
          <el-input v-model="markForm.description" type="textarea" :rows="3"
                    placeholder="体征表现、血氧/呼吸频率、现场处置等" />
        </el-form-item>
        <el-form-item label="转诊医院"><el-input v-model="markForm.targetHospital" placeholder="如：县人民医院" /></el-form-item>
        <el-form-item label="医院电话"><el-input v-model="markForm.hospitalPhone" placeholder="急救/接诊电话" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="markVisible = false">取消</el-button>
        <el-button type="danger" :loading="acting" @click="submitMark">标记高危并通知</el-button>
      </template>
    </el-dialog>

    <!-- 转诊详情与处理 -->
    <el-drawer v-model="visible" size="640px" :title="'转诊闭环 ' + (current?.referralNo || '')">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="患者">{{ detail.referral.patient.name }}（{{ detail.referral.patient.age }}岁）</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag size="small" :type="tagType('ReferralStatus', detail.referral.status)">{{ label('ReferralStatus', detail.referral.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="症状">{{ detail.referral.symptom }}</el-descriptions-item>
          <el-descriptions-item label="转诊医院">{{ detail.referral.targetHospital || '-' }} {{ detail.referral.hospitalPhone || '' }}</el-descriptions-item>
          <el-descriptions-item label="情况描述" :span="2">{{ detail.referral.description || '-' }}</el-descriptions-item>
          <el-descriptions-item label="入院诊断" :span="2">{{ detail.referral.admissionDiagnosis || '待回传' }}</el-descriptions-item>
          <el-descriptions-item label="入院时间">{{ fmt(detail.referral.admittedAt) }}</el-descriptions-item>
          <el-descriptions-item label="费用截至">
            <span v-if="detail.referral.feeCutoffAt" style="color:#c45656">{{ fmt(detail.referral.feeCutoffAt) }}</span>
            <span v-else>待确定</span>
          </el-descriptions-item>
          <el-descriptions-item label="费用截至说明" :span="2">{{ detail.referral.feeCutoffNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="出院时间">{{ fmt(detail.referral.dischargedAt) }}</el-descriptions-item>
          <el-descriptions-item label="待审核/待确认账单">{{ detail.pendingBillCount }} 张</el-descriptions-item>
        </el-descriptions>

        <div class="section-title mt-16">闭环进程</div>
        <el-timeline class="mb12">
          <el-timeline-item type="danger" :timestamp="fmt(detail.referral.createdAt)">
            标记高危【{{ detail.referral.symptom }}】，通知家庭医生/家属/转诊医院
          </el-timeline-item>
          <el-timeline-item v-if="detail.referral.decidedAt" :type="detail.referral.decision === 'AMBULANCE' ? 'danger' : 'info'" :timestamp="fmt(detail.referral.decidedAt)">
            医生远程判断：{{ label('ReferralDecision', detail.referral.decision) }}（{{ detail.referral.decisionDoctor?.name }}）
            <div class="muted">{{ detail.referral.decisionNote }}</div>
            <div v-if="detail.referral.decision === 'AMBULANCE'" class="muted">
              家庭病床已暂停、未结算项目转待审核；救护车：{{ detail.referral.ambulanceNote || '已派出' }}
            </div>
          </el-timeline-item>
          <el-timeline-item v-if="detail.referral.feedbackAt" type="warning" :timestamp="fmt(detail.referral.feedbackAt)">
            {{ label('FeedbackSource', detail.referral.feedbackSource) }}确认入院，费用截至 {{ fmt(detail.referral.feeCutoffAt) }}
          </el-timeline-item>
          <el-timeline-item v-if="detail.referral.dischargedAt" type="warning" :timestamp="fmt(detail.referral.dischargedAt)">
            患者出院{{ detail.referral.dischargeNote ? '：' + detail.referral.dischargeNote : '' }}
          </el-timeline-item>
          <el-timeline-item v-if="detail.referral.resumeDecidedAt" :type="detail.referral.resumeDecision === 'RESUME' ? 'success' : 'info'" :timestamp="fmt(detail.referral.resumeDecidedAt)">
            医生决定：{{ label('ResumeDecision', detail.referral.resumeDecision) }}（{{ detail.referral.resumeDoctor?.name }}）
            <div class="muted">{{ detail.referral.resumeNote }}</div>
          </el-timeline-item>
        </el-timeline>

        <!-- 角色操作区 -->
        <div v-if="actionMode" class="action-box">
          <el-divider content-position="left">{{ actionTitle }}</el-divider>
          <!-- 医生远程判断 -->
          <template v-if="actionMode === 'decide'">
            <el-radio-group v-model="decideForm.decision" class="mb12">
              <el-radio-button value="AMBULANCE">发起救护车转运</el-radio-button>
              <el-radio-button value="OBSERVE">继续居家观察</el-radio-button>
            </el-radio-group>
            <el-input v-if="decideForm.decision === 'AMBULANCE'" v-model="decideForm.ambulanceNote"
                      class="mb8" placeholder="转运说明：救护车/随车医护/预计到达时间" />
            <el-input v-model="decideForm.note" type="textarea" :rows="3"
                      :placeholder="decideForm.decision === 'AMBULANCE' ? '远程判断依据（转运后家庭病床将暂停，未结算项目转待审核）' : '继续观察的医嘱意见'" />
          </template>
          <!-- 医院回传 -->
          <template v-else-if="actionMode === 'hospital'">
            <el-form label-width="92px">
              <el-form-item label="入院时间">
                <el-date-picker v-model="feedbackForm.admittedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
              </el-form-item>
              <el-form-item label="入院诊断">
                <el-input v-model="feedbackForm.admissionDiagnosis" type="textarea" :rows="2" placeholder="医院回传的入院诊断" />
              </el-form-item>
            </el-form>
            <el-alert type="warning" :closable="false" show-icon title="提交后家庭病床费用截至入院时间，待审核账单退回待确认" />
          </template>
          <!-- 家属补充 -->
          <template v-else-if="actionMode === 'family'">
            <el-form label-width="92px">
              <el-form-item label="入院时间">
                <el-date-picker v-model="feedbackForm.admittedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
              </el-form-item>
              <el-form-item label="住院结果">
                <el-input v-model="feedbackForm.admissionDiagnosis" type="textarea" :rows="2" placeholder="医院诊断/住院情况" />
              </el-form-item>
              <el-form-item label="补充说明"><el-input v-model="feedbackForm.note" /></el-form-item>
            </el-form>
            <el-alert type="warning" :closable="false" show-icon title="提交后家庭病床费用截至入院时间，避免住院期间继续计费" />
          </template>
          <!-- 出院登记 -->
          <template v-else-if="actionMode === 'discharge'">
            <el-form label-width="92px">
              <el-form-item label="出院时间">
                <el-date-picker v-model="dischargeForm.dischargedAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" style="width:100%" />
              </el-form-item>
              <el-form-item label="出院情况"><el-input v-model="dischargeForm.note" type="textarea" :rows="2" /></el-form-item>
            </el-form>
          </template>
          <!-- 恢复决定 -->
          <template v-else-if="actionMode === 'resume'">
            <el-radio-group v-model="resumeForm.decision" class="mb12">
              <el-radio-button value="RESUME">恢复家庭病床</el-radio-button>
              <el-radio-button value="TERMINATE">终止家庭病床</el-radio-button>
            </el-radio-group>
            <el-input v-model="resumeForm.note" type="textarea" :rows="3"
                      :placeholder="resumeForm.decision === 'RESUME' ? '恢复依据（计划重启并自动补排剩余巡诊）' : '终止原因（费用截至入院时间，建床终止）'" />
          </template>
          <div style="text-align:right;margin-top:10px">
            <el-button type="primary" :loading="acting" @click="submitAction">提交</el-button>
          </div>
        </div>

        <div class="section-title mt-16">通知记录（{{ detail.notifications.length }}）</div>
        <el-table :data="detail.notifications" size="small">
          <el-table-column label="接收方" width="90">
            <template #default="{ row }"><el-tag size="small" effect="plain">{{ label('NotifyRecipientType', row.recipientType) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="recipientName" label="接收人" width="110" show-overflow-tooltip />
          <el-table-column label="渠道" width="90"><template #default="{ row }">{{ row.channel === 'SMS' ? '短信(模拟)' : '站内信' }}</template></el-table-column>
          <el-table-column prop="content" label="内容" show-overflow-tooltip />
          <el-table-column label="时间" width="140"><template #default="{ row }">{{ fmt(row.sentAt) }}</template></el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useAuthStore } from '../store/auth'
import { ENUMS, label, tagType } from '../labels'

const auth = useAuthStore()
const referrals = ref([])
const notifications = ref([])
const patients = ref([])
const loading = ref(true)
const filter = ref('')
const acting = ref(false)

const canMark = computed(() => ['NURSE', 'FAMILY_DOCTOR', 'ADMIN'].includes(auth.role))
const isStaff = computed(() => ['FAMILY_DOCTOR', 'ADMIN'].includes(auth.role))
const unreadCount = computed(() => notifications.value.filter(n => !n.readAt).length)

function fmt(t) { return t ? t.replace('T', ' ').slice(0, 16) : '-' }

async function load() {
  loading.value = true
  referrals.value = (await api.get('/referrals' + (filter.value ? '?status=' + filter.value : ''))).data
  loading.value = false
}
async function loadNotifications() {
  if (auth.isFamily || auth.isDoctor) {
    notifications.value = (await api.get('/referrals/notifications/mine')).data
  }
}
onMounted(() => { load(); loadNotifications() })

async function readNotify(n) {
  await api.post(`/referrals/notifications/${n.id}/read`)
  loadNotifications()
}

// ===== 标记病情恶化 =====
const markVisible = ref(false)
const markForm = reactive({ patientId: null, symptom: '呼吸困难', description: '', targetHospital: '县人民医院', hospitalPhone: '' })
async function openMark() {
  patients.value = (await api.get('/patients')).data
  Object.assign(markForm, { patientId: null, symptom: '呼吸困难', description: '', targetHospital: '县人民医院', hospitalPhone: '' })
  markVisible.value = true
}
async function submitMark() {
  if (!markForm.patientId) return ElMessage.warning('请选择患者')
  if (!markForm.symptom) return ElMessage.warning('请填写主要症状')
  acting.value = true
  try {
    await api.post('/referrals/mark', markForm)
    ElMessage.success('已标记高危，家庭医生/家属/转诊医院已同步通知')
    markVisible.value = false
    load()
  } finally { acting.value = false }
}

// ===== 详情与处理 =====
const visible = ref(false)
const current = ref(null)
const detail = ref(null)
const decideForm = reactive({ decision: 'AMBULANCE', note: '', ambulanceNote: '' })
const feedbackForm = reactive({ admittedAt: null, admissionDiagnosis: '', note: '' })
const dischargeForm = reactive({ dischargedAt: null, note: '' })
const resumeForm = reactive({ decision: 'RESUME', note: '' })

const actionMode = computed(() => {
  if (!detail.value) return null
  const s = detail.value.referral.status
  if (s === 'MARKED' && isStaff.value) return 'decide'
  if (s === 'AMBULANCE_DISPATCHED' && isStaff.value) return 'hospital'
  if (s === 'AMBULANCE_DISPATCHED' && auth.isFamily) return 'family'
  if (s === 'ADMITTED' && (isStaff.value || auth.isFamily)) return 'discharge'
  if (s === 'DISCHARGED' && isStaff.value) return 'resume'
  return null
})
const actionTitle = computed(() => ({
  decide: '医生远程判断（决定后家庭病床暂停/未结算转待审核）',
  hospital: '登记医院回传（确定费用截至时间）',
  family: '家属补充住院结果（确定费用截至时间）',
  discharge: '出院登记',
  resume: '出院后医生决定'
}[actionMode.value]))

async function open(row) {
  current.value = row
  detail.value = (await api.get(`/referrals/${row.id}`)).data
  Object.assign(decideForm, { decision: 'AMBULANCE', note: '', ambulanceNote: '' })
  Object.assign(feedbackForm, { admittedAt: null, admissionDiagnosis: '', note: '' })
  Object.assign(dischargeForm, { dischargedAt: null, note: '' })
  Object.assign(resumeForm, { decision: 'RESUME', note: '' })
  visible.value = true
}

async function submitAction() {
  const id = current.value.id
  acting.value = true
  try {
    if (actionMode.value === 'decide') {
      if (decideForm.decision === 'AMBULANCE' && !decideForm.note) return ElMessage.warning('请填写远程判断依据')
      await api.post(`/referrals/${id}/decide`, decideForm)
      ElMessage.success(decideForm.decision === 'AMBULANCE'
        ? '救护车转运已发起：家庭病床暂停，未结算项目转待审核' : '已记录：继续居家观察')
    } else if (actionMode.value === 'hospital') {
      if (!feedbackForm.admittedAt || !feedbackForm.admissionDiagnosis) return ElMessage.warning('请填写入院时间与入院诊断')
      await api.post(`/referrals/${id}/hospital-feedback`, feedbackForm)
      ElMessage.success('医院回传已登记，费用截至时间已确定')
    } else if (actionMode.value === 'family') {
      if (!feedbackForm.admittedAt || !feedbackForm.admissionDiagnosis) return ElMessage.warning('请填写入院时间与住院结果')
      await api.post(`/referrals/${id}/family-feedback`, feedbackForm)
      ElMessage.success('住院结果已补充，费用截至时间已确定')
    } else if (actionMode.value === 'discharge') {
      await api.post(`/referrals/${id}/discharge`, dischargeForm)
      ElMessage.success('出院已登记，等待医生恢复决定')
    } else if (actionMode.value === 'resume') {
      if (!resumeForm.note) return ElMessage.warning('请填写医生意见')
      await api.post(`/referrals/${id}/resume-decision`, resumeForm)
      ElMessage.success(resumeForm.decision === 'RESUME' ? '家庭病床已恢复并补排巡诊' : '家庭病床已终止，转诊闭环')
    }
    visible.value = false
    load()
    loadNotifications()
  } finally { acting.value = false }
}
</script>

<style scoped>
.head-line { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.mb12 { margin-bottom: 12px; } .mb8 { margin-bottom: 8px; }
.mt-16 { margin-top: 16px; }
.section-title { font-weight: 600; margin-bottom: 8px; }
.notify-box { margin-bottom: 14px; }
.notify-item { display: flex; align-items: center; gap: 8px; padding: 6px 0; border-bottom: 1px dashed #eee; }
.notify-item.unread .notify-content { font-weight: 600; color: #303133; }
.notify-content { flex: 1; color: #606266; font-size: 13px; }
.action-box { background: #fdf6f0; border: 1px solid #f5e0cc; border-radius: 8px; padding: 4px 14px 12px; }
</style>
