<template>
  <div>
    <div class="head-line">
      <h2 class="page-title" style="margin:0">上门巡诊</h2>
      <el-radio-group v-model="filter" size="default" @change="load">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button label="SCHEDULED">待上门</el-radio-button>
        <el-radio-button label="IN_PROGRESS">上门中</el-radio-button>
        <el-radio-button label="COMPLETED">已完成</el-radio-button>
        <el-radio-button label="EXCEPTION">异常挂起</el-radio-button>
      </el-radio-group>
    </div>

    <div class="card-soft">
      <el-table :data="visits" v-loading="loading" size="small">
        <el-table-column label="预约时间" width="150">
          <template #default="{row}">{{ row.scheduledAt?.replace('T',' ').slice(0,16) }}</template>
        </el-table-column>
        <el-table-column label="患者" width="100"><template #default="{row}">{{ row.patient.name }} <span class="muted">{{row.patient.age}}岁</span></template></el-table-column>
        <el-table-column prop="patient.address" label="地址" show-overflow-tooltip />
        <el-table-column label="责任护士" width="90"><template #default="{row}">{{ row.nurse?.name || '-' }}</template></el-table-column>
        <el-table-column label="路程" width="70"><template #default="{row}">{{ row.estimatedTravelMinutes || '-' }}分</template></el-table-column>
        <el-table-column label="状态" width="95">
          <template #default="{row}"><el-tag size="small" :type="tagType('VisitStatus',row.status)">{{ label('VisitStatus',row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="arrangementNote" label="安排说明" show-overflow-tooltip />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{row}">
            <el-button link type="info" size="small" @click="viewDetail(row)">记录</el-button>
            <el-button v-if="canExecute(row) && row.status==='SCHEDULED'" link type="primary" size="small" @click="start(row)">签到上门</el-button>
            <el-button v-if="canExecute(row) && (row.status==='IN_PROGRESS')" link type="success" size="small" @click="openComplete(row)">填写记录</el-button>
            <el-button v-if="canExecute(row) && row.status!=='COMPLETED'" link type="warning" size="small" @click="openException(row)">报异常</el-button>
            <el-button v-if="canExecute(row) && row.status!=='CANCELLED'" link type="danger" size="small" @click="openWorse(row)">病情恶化</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 巡诊详情 -->
    <el-drawer v-model="detailVisible" size="560px" :title="'巡诊记录 ' + (detail?.visitNo || '')">
      <template v-if="detailData">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="血压">{{ detailData.visit.systolicPressure || '-' }}/{{ detailData.visit.diastolicPressure || '-' }}</el-descriptions-item>
          <el-descriptions-item label="血糖">{{ detailData.visit.bloodGlucose || '-' }}</el-descriptions-item>
          <el-descriptions-item label="心率">{{ detailData.visit.heartRate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="体温">{{ detailData.visit.temperature || '-' }}</el-descriptions-item>
          <el-descriptions-item label="血氧">{{ detailData.visit.oxygenSaturation || '-' }}%</el-descriptions-item>
          <el-descriptions-item label="呼吸">{{ detailData.visit.breathRate || '-' }}</el-descriptions-item>
          <el-descriptions-item label="压疮" :span="2">{{ detailData.visit.pressureUlcerNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="换药" :span="2">{{ detailData.visit.dressingNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="导尿" :span="2">{{ detailData.visit.catheterNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="用药" :span="2">{{ detailData.visit.medicationNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="整体情况" :span="2">{{ detailData.visit.overallNote || '-' }}</el-descriptions-item>
        </el-descriptions>

        <div class="section-title mt-16">护理项目（{{ detailData.careResults.length }}）</div>
        <el-table :data="detailData.careResults" size="small">
          <el-table-column prop="itemName" label="项目" />
          <el-table-column prop="quantity" label="数量" width="60" align="center" />
          <el-table-column label="单价" width="80" align="right"><template #default="{row}">¥{{ row.unitPrice }}</template></el-table-column>
          <el-table-column label="结果" width="80"><template #default="{row}">
            <el-tag size="small" :type="row.resultStatus==='DONE'?'success':row.resultStatus==='SKIPPED'?'info':'danger'">{{ label('CareResultStatus',row.resultStatus) }}</el-tag>
          </template></el-table-column>
        </el-table>

        <div class="section-title mt-16">耗材/冷链领用</div>
        <el-table :data="detailData.materialTransactions" size="small">
          <el-table-column label="耗材"><template #default="{row}">{{ row.material.name }}
            <el-tag v-if="row.material.coldChain" size="small" type="primary" effect="plain">冷链</el-tag></template></el-table-column>
          <el-table-column prop="quantity" label="数量" width="60" align="center" />
          <el-table-column label="交接温度" width="100">
            <template #default="{row}">
              <span v-if="row.material.coldChain">
                {{ row.coldChainTemp ? row.coldChainTemp+'℃' : '未登记' }}
                <el-icon v-if="row.coldChainTemp && (row.coldChainTemp<2||row.coldChainTemp>8)" color="#f56c6c"><WarningFilled /></el-icon>
              </span>
              <span v-else>-</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="section-title mt-16">现场照片</div>
        <el-image v-for="p in detailData.photos" :key="p.id" :src="p.filePath"
                  :preview-src-list="detailData.photos.map(x=>x.filePath)"
                  class="photo-thumb" fit="cover" />

        <div class="section-title mt-16">家属签字</div>
        <div v-if="detailData.visit.familySignPath" class="sign-box">
          <el-image :src="detailData.visit.familySignPath" class="sign-img" fit="contain" />
          <div>签字人：{{ detailData.visit.familySignName }} ｜ {{ detailData.visit.familySignedAt?.replace('T',' ').slice(0,16) }}</div>
        </div>
        <el-empty v-else description="尚未取得家属签字" :image-size="60" />

        <div v-if="auth.isDoctor && detailData.visit.status==='COMPLETED'" class="mt-16" style="text-align:right">
          <el-button type="warning" @click="openOrder(detailData.visit)">依据本次巡诊下达医嘱（处方/复诊/转诊）</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 医生下达医嘱 -->
    <el-dialog v-model="orderVisible" title="下达医嘱" width="520px">
      <el-form :model="orderForm" label-width="100px">
        <el-form-item label="医嘱类型">
          <el-radio-group v-model="orderForm.orderType">
            <el-radio-button v-for="(v,k) in ENUMS.OrderType" :key="k" :value="k">{{ v }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="诊断依据"><el-input v-model="orderForm.diagnosisBasis" placeholder="依据的巡诊体征/表现" /></el-form-item>
        <el-form-item label="医嘱内容"><el-input v-model="orderForm.content" type="textarea" :rows="4" placeholder="药品用法用量 / 复诊时间 / 转诊机构与原因" /></el-form-item>
        <el-form-item label="需冷链药品"><el-switch v-model="orderForm.coldChainRequired" /></el-form-item>
        <el-form-item v-if="orderForm.coldChainRequired" label="冷链要求"><el-input v-model="orderForm.coldChainNote" placeholder="如 2-8℃ 避光运输" /></el-form-item>
      </el-form>
      <div class="muted">转诊/处方类医嘱需由另一位医生远程复核后执行。</div>
      <template #footer><el-button @click="orderVisible=false">取消</el-button>
        <el-button type="primary" @click="submitOrder">下达</el-button></template>
    </el-dialog>

    <!-- 填写巡诊记录 -->
    <el-drawer v-model="completeVisible" size="640px" title="填写上门巡诊记录">
      <el-form :model="form" label-width="92px">
        <el-divider content-position="left">生命体征</el-divider>
        <el-row :gutter="8">
          <el-col :span="8"><el-form-item label="血压(收)"><el-input-number v-model="form.systolicPressure" :max="260" controls-position="right" style="width:100%"/></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="血压(舒)"><el-input-number v-model="form.diastolicPressure" :max="180" controls-position="right" style="width:100%"/></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="心率"><el-input-number v-model="form.heartRate" :max="220" controls-position="right" style="width:100%"/></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="体温℃"><el-input-number v-model="form.temperature" :step="0.1" :precision="1" controls-position="right" style="width:100%"/></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="血糖"><el-input-number v-model="form.bloodGlucose" :max="600" controls-position="right" style="width:100%"/></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="血氧%"><el-input-number v-model="form.oxygenSaturation" :max="100" controls-position="right" style="width:100%"/></el-form-item></el-col>
        </el-row>
        <el-form-item label="压疮情况"><el-input v-model="form.pressureUlcerNote" type="textarea" :rows="2" placeholder="部位/分期/面积/是否新发加重" /></el-form-item>
        <el-form-item label="换药部位"><el-input v-model="form.dressingNote" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="导尿护理"><el-input v-model="form.catheterNote" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="用药提醒"><el-input v-model="form.medicationNote" type="textarea" :rows="2" placeholder="药名/剂量/家属核对情况" /></el-form-item>
        <el-form-item label="整体情况"><el-input v-model="form.overallNote" type="textarea" :rows="2" /></el-form-item>

        <el-divider content-position="left">护理项目（对应收费，逐项记录防止漏记错记）</el-divider>
        <div v-for="(r,i) in form.careResults" :key="i" class="line-row">
          <el-select v-model="r.catalogId" placeholder="选择收费/护理项目" style="width:240px">
            <el-option v-for="c in nursingCatalogs" :key="c.id"
                       :label="`${c.name} ¥${c.unitPrice}` + (c.reimbursable?`（${c.insuranceCategory}）`:'（自费）')" :value="c.id" />
          </el-select>
          <el-input-number v-model="r.quantity" :min="1" :max="9" style="width:110px" />
          <el-select v-model="r.resultStatus" style="width:100px">
            <el-option label="已完成" value="DONE" /><el-option label="未执行" value="SKIPPED" /><el-option label="异常中止" value="ABNORMAL" />
          </el-select>
          <el-button link type="danger" @click="form.careResults.splice(i,1)">移除</el-button>
        </div>
        <el-button size="small" @click="form.careResults.push({catalogId:null,quantity:1,resultStatus:'DONE',note:''})">+ 添加护理项目</el-button>

        <el-divider content-position="left">耗材/冷链药品领用</el-divider>
        <div v-for="(u,i) in form.materialUsages" :key="i" class="line-row">
          <el-select v-model="u.materialId" placeholder="耗材/药品" style="width:220px">
            <el-option v-for="m in materials" :key="m.id"
                       :label="`${m.name} 库存${m.stockQty}`" :value="m.id" />
          </el-select>
          <el-input-number v-model="u.quantity" :min="1" style="width:100px" />
          <template v-if="coldMap[u.materialId]">
            交接温度
            <el-input-number v-model="u.coldChainTemp" :step="0.1" :precision="1" :min="-5" :max="20" style="width:110px" /> ℃
          </template>
          <el-button link type="danger" @click="form.materialUsages.splice(i,1)">移除</el-button>
        </div>
        <el-button size="small" @click="form.materialUsages.push({materialId:null,quantity:1,coldChainTemp:null,note:''})">+ 领用耗材</el-button>
        <div class="muted">冷链药品交接温度须在 2-8℃，越界将自动生成风险事件；库存不足时请先上报“耗材缺货”异常</div>

        <el-divider content-position="left">现场照片</el-divider>
        <UploadPhotos v-model="form.photoPaths" type="visit" />

        <el-divider content-position="left">家属签字（必填）</el-divider>
        <el-input v-model="form.familySignName" placeholder="签字家属姓名" style="width:220px" class="mb8" />
        <SignPad v-model="form.familySignPath" :sign-name="form.familySignName" />
      </el-form>
      <template #footer>
        <el-button @click="completeVisible=false">暂存关闭</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">提交巡诊记录</el-button>
      </template>
    </el-drawer>

    <!-- 异常上报 -->
    <el-dialog v-model="exceptionVisible" title="上报巡诊异常" width="460px">
      <el-form label-width="92px">
        <el-form-item label="异常类型">
          <el-select v-model="exForm.type" style="width:100%">
            <el-option v-for="(v,k) in ENUMS.ExceptionType" :key="k" :label="v" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="情况说明"><el-input v-model="exForm.description" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="exceptionVisible=false">取消</el-button>
        <el-button type="warning" @click="submitException">上报（任务将挂起/记录）</el-button></template>
    </el-dialog>

    <!-- 病情恶化·标记高危 -->
    <el-dialog v-model="worseVisible" title="病情恶化·标记高危并通知三方" width="520px">
      <el-alert type="error" :closable="false" show-icon class="mb12"
                title="提交后立即标记高危，同时通知家庭医生、家属与转诊医院，由医生远程判断是否救护车转运" />
      <el-form label-width="92px">
        <el-form-item label="患者"><div>{{ currentVisit?.patient?.name }}（{{ currentVisit?.patient?.age }}岁）</div></el-form-item>
        <el-form-item label="主要症状"><el-input v-model="worseForm.symptom" placeholder="如：呼吸困难" /></el-form-item>
        <el-form-item label="情况描述">
          <el-input v-model="worseForm.description" type="textarea" :rows="3"
                    placeholder="体征表现、血氧/呼吸频率、现场处置等" />
        </el-form-item>
        <el-form-item label="转诊医院"><el-input v-model="worseForm.targetHospital" placeholder="如：县人民医院" /></el-form-item>
        <el-form-item label="医院电话"><el-input v-model="worseForm.hospitalPhone" placeholder="急救/接诊电话" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="worseVisible=false">取消</el-button>
        <el-button type="danger" :loading="worseSubmitting" @click="submitWorse">标记高危并通知</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useAuthStore } from '../store/auth'
import { ENUMS, label, tagType } from '../labels'
import SignPad from '../components/SignPad.vue'
import UploadPhotos from '../components/UploadPhotos.vue'

const auth = useAuthStore()
const visits = ref([])
const loading = ref(true)
const filter = ref(auth.isNurse ? 'SCHEDULED' : '')

async function load() {
  loading.value = true
  const url = '/visits' + (filter.value ? '?status=' + filter.value : '')
  visits.value = (await api.get(url)).data
  loading.value = false
}
onMounted(load)

function canExecute(row) {
  return auth.isAdmin || (auth.isNurse && row.nurse?.id === auth.user.id)
}

async function start(row) {
  await api.post(`/visits/${row.id}/start`, { locationSnapshot: row.patient.address + '（GPS签到）' })
  ElMessage.success('签到成功，注意路途安全')
  filter.value = 'IN_PROGRESS'
  load()
}

// 详情
const detailVisible = ref(false)
const detailData = ref(null)
async function viewDetail(row) {
  detailData.value = (await api.get(`/visits/${row.id}`)).data
  detailVisible.value = true
}

// 完成记录
const completeVisible = ref(false)
const submitting = ref(false)
const currentVisit = ref(null)
const form = reactive({})
const materials = ref([])
const nursingCatalogs = ref([])
const coldMap = computed(() => Object.fromEntries(materials.value.filter(m => m.coldChain).map(m => [m.id, true])))

async function openComplete(row) {
  currentVisit.value = row
  materials.value = (await api.get('/materials')).data
  const cats = (await api.get('/catalogs')).data
  nursingCatalogs.value = cats.filter(c => ['VISIT_FEE','NURSING'].includes(c.category))
  Object.assign(form, {
    systolicPressure: null, diastolicPressure: null, heartRate: null, temperature: null,
    bloodGlucose: null, oxygenSaturation: null, breathRate: null,
    pressureUlcerNote: '', dressingNote: '', catheterNote: '', medicationNote: '', overallNote: '',
    careResults: nursingCatalogs.value.slice(0, 2).map(c => ({ catalogId: c.id, quantity: 1, resultStatus: 'DONE', note: '' })),
    materialUsages: [],
    photoPaths: [],
    familySignName: row.patient.primaryCaregiver || '',
    familySignPath: '',
    locationSnapshot: row.patient.address
  })
  completeVisible.value = true
}

async function submit() {
  if (!form.familySignName || !form.familySignPath) {
    return ElMessage.warning('必须取得家属手写签字后方可提交')
  }
  if (!form.careResults.length) return ElMessage.warning('至少记录一项护理服务')
  submitting.value = true
  try {
    await api.post(`/visits/${currentVisit.value.id}/complete`, form)
    ElMessage.success('巡诊记录已提交，体征异常项已自动进入风险预警')
    completeVisible.value = false
    load()
  } finally { submitting.value = false }
}

// 异常
const exceptionVisible = ref(false)
const exForm = reactive({ type: 'FAMILY_ABSENT', description: '' })
function openException(row) {
  currentVisit.value = row
  exForm.type = 'FAMILY_ABSENT'
  exForm.description = ''
  exceptionVisible.value = true
}
async function submitException() {
  if (!exForm.description) return ElMessage.warning('请填写情况说明')
  await api.post(`/visits/${currentVisit.value.id}/exceptions`, exForm)
  ElMessage.success(exForm.type === 'NURSE_OVERTIME' ? '超时已记录' : '异常已上报，任务已挂起等待处理')
  exceptionVisible.value = false
  load()
}

// 病情恶化·标记高危（启动转诊闭环）
const worseVisible = ref(false)
const worseSubmitting = ref(false)
const worseForm = reactive({ symptom: '呼吸困难', description: '', targetHospital: '县人民医院', hospitalPhone: '' })
function openWorse(row) {
  currentVisit.value = row
  Object.assign(worseForm, { symptom: '呼吸困难', description: '', targetHospital: '县人民医院', hospitalPhone: '' })
  worseVisible.value = true
}
async function submitWorse() {
  if (!worseForm.symptom) return ElMessage.warning('请填写主要症状')
  worseSubmitting.value = true
  try {
    await api.post('/referrals/mark', { visitId: currentVisit.value.id, ...worseForm })
    ElMessage.success('已标记高危：家庭医生/家属/转诊医院已同步通知，等待医生远程判断')
    worseVisible.value = false
  } finally { worseSubmitting.value = false }
}

// 医生下达医嘱
const orderVisible = ref(false)
const orderForm = reactive({})
function openOrder(visit) {
  Object.assign(orderForm, { orderType: 'PRESCRIPTION', diagnosisBasis: '', content: '', coldChainRequired: false, coldChainNote: '' })
  currentVisit.value = visit
  orderVisible.value = true
}
async function submitOrder() {
  if (!orderForm.content) return ElMessage.warning('请填写医嘱内容')
  await api.post('/orders', { visitId: currentVisit.value.id, ...orderForm })
  ElMessage.success('医嘱已下达，等待另一位医生远程复核')
  orderVisible.value = false
}
</script>

<style scoped>
.head-line { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; }
.section-title { font-weight: 600; margin-bottom: 8px; }
.mt-16 { margin-top: 16px; } .mb8 { margin-bottom: 8px; }
.line-row { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.photo-thumb { width: 120px; height: 80px; border-radius: 6px; margin: 4px 8px 4px 0; border: 1px solid #ddd; }
.sign-box { text-align: center; }
.sign-img { width: 260px; height: 100px; border: 1px solid #eee; border-radius: 6px; background: #fafafa; }
</style>
