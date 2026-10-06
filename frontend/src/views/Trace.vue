<template>
  <div>
    <h2 class="page-title">服务全链还原 · 申请/执行/照片/医嘱/签字/费用一键回溯</h2>
    <div class="card-soft mb12">
      <el-select v-model="applicationId" filterable placeholder="选择建床申请单（按患者）" style="width:480px" @change="load">
        <el-option v-for="a in applications" :key="a.id"
                   :label="`${a.patient.name} · ${a.applyNo} · ${label('BedApplyStatus',a.status)}`" :value="a.id" />
      </el-select>
      <el-tag v-if="data" class="ml8" type="success">
        {{ data.application.bedNo || '无床位号' }} ｜ 护理需求 {{ data.careNeeds.length }} 项
        ｜ 巡诊 {{ data.visitChain.length }} 次 ｜ 账单 {{ data.bills.length }} 张 ｜ 风险 {{ data.riskEvents.length }} 起
      </el-tag>
    </div>

    <template v-if="data">
      <el-row :gutter="14">
        <el-col :span="14">
          <div class="card-soft">
            <div class="section-title">全过程时间线（用于家属质疑/医保核查/纠纷举证）</div>
            <el-timeline>
              <el-timeline-item v-for="(t,i) in data.timeline" :key="i"
                                :timestamp="t.time.replace('T',' ').slice(0,16)" placement="top"
                                :type="stageType(t.stage)">
                <el-card shadow="never" class="tl-card">
                  <div class="tl-head">
                    <el-tag size="small" :type="stageType(t.stage)">{{ t.stage }}</el-tag>
                    <b>{{ t.title }}</b>
                    <span class="muted">{{ t.actor }}</span>
                  </div>
                  <div class="tl-detail">{{ t.detail }}</div>
                  <div v-if="t.evidences?.length" class="timeline-evidence mt-12">
                    <el-image v-for="(e,j) in t.evidences" :key="j" :src="e"
                              :preview-src-list="t.evidences" fit="cover" />
                  </div>
                </el-card>
              </el-timeline-item>
            </el-timeline>
          </div>
        </el-col>
        <el-col :span="10">
          <div class="card-soft mb12">
            <div class="section-title">建床评估</div>
            <el-descriptions :column="1" size="small" border>
              <el-descriptions-item label="风险等级">
                <el-tag size="small" :type="tagType('RiskLevel',data.application.riskLevel)">{{ label('RiskLevel',data.application.riskLevel) }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="距离/路程">{{ data.application.distanceMeters }} 米 / {{ data.application.travelMinutes }} 分钟</el-descriptions-item>
              <el-descriptions-item label="医保资格">{{ data.application.insuranceEligible ? '符合' : '不符合' }}</el-descriptions-item>
              <el-descriptions-item label="照护人">{{ data.application.caregiverCondition }}</el-descriptions-item>
              <el-descriptions-item label="评估结论">{{ data.application.assessmentConclusion }}</el-descriptions-item>
            </el-descriptions>
          </div>
          <div class="card-soft mb12">
            <div class="section-title">逐次巡诊证据</div>
            <el-collapse>
              <el-collapse-item v-for="node in data.visitChain" :key="node.visit.id"
                                :title="`${node.visit.visitNo} ${node.visit.completedAt?'(已完成)':'('+label('VisitStatus',node.visit.status)+')'}`"
                                :name="node.visit.id">
                <div class="muted">护士 {{ node.visit.nurse?.name }} ｜ {{ node.visit.scheduledAt?.replace('T',' ').slice(0,16) }}</div>
                <div>血压 {{ node.visit.systolicPressure||'-' }}/{{ node.visit.diastolicPressure||'-' }}
                  ｜ 血糖 {{ node.visit.bloodGlucose||'-' }} ｜ 血氧 {{ node.visit.oxygenSaturation||'-' }}%</div>
                <div>护理 {{ node.careResults.length }} 项 ｜ 耗材 {{ node.materialTransactions.length }} 项
                  ｜ 照片 {{ node.photos.length }} 张 ｜ 异常 {{ node.exceptions.length }} ｜ 医嘱 {{ node.orders.length }}</div>
                <div v-if="node.visit.familySignPath" class="sign-line">
                  <el-image :src="node.visit.familySignPath" class="sign-thumb" fit="contain" />
                  家属签字：{{ node.visit.familySignName }}
                </div>
                <div v-if="node.exceptions.length" class="mt-12">
                  <el-tag v-for="e in node.exceptions" :key="e.id" size="small" type="danger">
                    {{ label('ExceptionType',e.type) }}
                  </el-tag>
                </div>
              </el-collapse-item>
            </el-collapse>
          </div>
          <div class="card-soft">
            <div class="section-title">账单与合规</div>
            <el-timeline>
              <el-timeline-item v-for="bm in data.bills" :key="bm.bill.id"
                                :timestamp="bm.bill.createdAt?.replace('T',' ').slice(0,16)" placement="top" type="success">
                <div><b>{{ bm.bill.billNo }}</b>
                  <el-tag size="small" :type="tagType('BillStatus',bm.bill.status)">{{ label('BillStatus',bm.bill.status) }}</el-tag>
                </div>
                <div>总额 ¥{{ bm.bill.totalAmount }}（医保 ¥{{ bm.bill.insuranceAmount }} / 自费 ¥{{ bm.bill.selfPayAmount }}），{{ bm.items.length }} 行</div>
                <div v-if="bm.compliance" class="muted">
                  合规初审 {{ bm.compliance.autoScore }} 分 · {{ label('ComplianceStatus',bm.compliance.status) }}
                </div>
              </el-timeline-item>
            </el-timeline>
          </div>
        </el-col>
      </el-row>
    </template>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api'
import { label, tagType } from '../labels'

const route = useRoute()
const applications = ref([])
const applicationId = ref(null)
const data = ref(null)

function stageType(stage) {
  return {
    '申请': 'primary', '评估': 'warning', '排班': 'primary', '上门': 'primary',
    '巡诊记录': '', '家属签字': 'success', '医嘱': 'warning', '远程复核': 'warning',
    '异常': 'danger', '账单': 'info', '家属确认': 'success', '收费质疑': 'danger',
    '结算': 'success', '风险': 'danger', '转诊': 'danger'
  }[stage] || ''
}

async function load() {
  if (!applicationId.value) return
  data.value = (await api.get('/trace/application/' + applicationId.value)).data
}

onMounted(async () => {
  applications.value = (await api.get('/applications')).data
  if (route.query.applicationId) {
    applicationId.value = Number(route.query.applicationId)
  } else if (route.query.patientId) {
    const a = applications.value.find(a => String(a.patient.id) === String(route.query.patientId))
    applicationId.value = a?.id
  } else {
    applicationId.value = applications.value[0]?.id
  }
  await load()
})
</script>

<style scoped>
.mb12 { margin-bottom: 12px; } .ml8 { margin-left: 8px; }
.section-title { font-weight: 600; margin-bottom: 10px; }
.tl-card { padding: 0; }
.tl-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.tl-detail { color: #555; font-size: 13px; line-height: 1.7; white-space: pre-wrap; }
.timeline-evidence img { width: 96px; height: 60px; object-fit: cover; border-radius: 4px; border: 1px solid #ddd; }
.sign-line { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 13px; }
.sign-thumb { width: 120px; height: 48px; border: 1px solid #eee; border-radius: 4px; background: #fafafa; }
.mt-12 { margin-top: 8px; }
</style>
