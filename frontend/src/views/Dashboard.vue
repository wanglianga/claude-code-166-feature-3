<template>
  <div v-loading="loading">
    <h2 class="page-title">卫生院运营看板 · 基层巡诊资源与风险总览</h2>
    <el-row :gutter="14">
      <el-col :span="6" v-for="k in kpis" :key="k.label">
        <div class="card-soft kpi-card">
          <div class="muted">{{ k.label }}</div>
          <div class="kpi-value" :style="{ color: k.color || '#1f6f5c' }">{{ k.value }}</div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="14" class="mt-16">
      <el-col :span="8"><div class="card-soft"><div class="chart-title">上门半径分布</div><div ref="radiusChart" class="chart"></div></div></el-col>
      <el-col :span="8"><div class="card-soft"><div class="chart-title">巡诊异常类型分布</div><div ref="excChart" class="chart"></div></div></el-col>
      <el-col :span="8"><div class="card-soft"><div class="chart-title">风险事件严重程度</div><div ref="riskChart" class="chart"></div></div></el-col>
    </el-row>

    <el-row :gutter="14" class="mt-16">
      <el-col :span="12">
        <div class="card-soft">
          <div class="chart-title">护士巡诊负荷（近7天 / 累计 / 上门超时）</div>
          <el-table :data="data.nurseLoad || []" size="small">
            <el-table-column prop="nurseName" label="护士" width="90" />
            <el-table-column prop="title" label="职称" width="90" />
            <el-table-column label="技能"><template #default="{row}"><span class="muted">{{ row.skills || '-' }}</span></template></el-table-column>
            <el-table-column prop="weekVisitCount" label="近7天" width="70" align="center" />
            <el-table-column prop="totalVisitCount" label="累计" width="60" align="center" />
            <el-table-column label="超时" width="60" align="center">
              <template #default="{row}">
                <el-tag size="small" :type="row.overtimeCount > 0 ? 'danger' : 'success'">{{ row.overtimeCount }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="card-soft">
          <div class="chart-title">耗材消耗 Top（近30天）与缺货预警</div>
          <div ref="matChart" style="height:220px"></div>
          <el-table :data="data.materialShortage || []" size="small" class="mt-12">
            <el-table-column prop="name" label="预警耗材" />
            <el-table-column label="库存/安全库存" width="130">
              <template #default="{row}">
                <el-tag size="small" :type="row.shortage ? 'danger' : 'warning'">{{ row.stockQty }} / {{ row.safetyStock }}</el-tag>
                <el-tag v-if="row.coldChain" size="small" type="primary" effect="plain" class="ml6">冷链</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="14" class="mt-16">
      <el-col :span="12">
        <div class="card-soft">
          <div class="chart-title">超半径患者（>5km，需拼单/风险告知）</div>
          <el-table :data="data.farPatients || []" size="small">
            <el-table-column prop="patientName" label="患者" width="100" />
            <el-table-column prop="address" label="住址" />
            <el-table-column label="距离" width="100" align="right">
              <template #default="{row}">{{ (row.distanceMeters/1000).toFixed(1) }} km</template>
            </el-table-column>
          </el-table>
        </div>
      </el-col>
      <el-col :span="12">
        <div class="card-soft">
          <div class="chart-title">待处理风险事件</div>
          <el-table :data="data.openRisks || []" size="small">
            <el-table-column prop="riskType" label="类型" width="110" />
            <el-table-column label="患者" width="90"><template #default="{row}">{{ row.patient.name }}</template></el-table-column>
            <el-table-column prop="description" label="描述" show-overflow-tooltip />
            <el-table-column label="级别" width="70"><template #default="{row}">
              <el-tag size="small" :type="row.severity==='HIGH'?'danger':'warning'">{{ label('RiskSeverity',row.severity) }}</el-tag>
            </template></el-table-column>
          </el-table>
        </div>
      </el-col>
    </el-row>

    <div class="card-soft mt-16">
      <span class="muted">近30天金额：</span>
      总额 <b>¥{{ data.amount30d?.total }}</b>　医保报销 <b style="color:#1f6f5c">¥{{ data.amount30d?.insurance }}</b>
      家属自费 <b style="color:#e6a23c">¥{{ data.amount30d?.self }}</b>
    </div>
  </div>
</template>

<script setup>
import { onMounted, onBeforeUnmount, ref, computed, nextTick } from 'vue'
import * as echarts from 'echarts'
import api from '../api'
import { label } from '../labels'

const data = ref({})
const loading = ref(true)
const charts = []

const kpis = computed(() => [
  { label: '待评估申请', value: data.value.kpi?.pendingApplications || 0, color: '#e6a23c' },
  { label: '在管家庭病床', value: data.value.kpi?.activeBeds || 0 },
  { label: '待上门任务', value: data.value.kpi?.scheduledVisits || 0, color: '#409eff' },
  { label: '已完成巡诊', value: data.value.kpi?.completedVisits || 0, color: '#67c23a' },
  { label: '异常挂起', value: data.value.kpi?.exceptionVisits || 0, color: '#f56c6c' },
  { label: '待处理风险', value: data.value.kpi?.openRiskEvents || 0, color: '#f56c6c' },
  { label: '转诊住院中', value: data.value.kpi?.inHospitalReferrals || 0, color: '#f56c6c' },
  { label: '在岗护士', value: data.value.kpi?.nursesOnDuty || 0 }
].slice(0, 8))

onMounted(async () => {
  const res = await api.get('/dashboard/overview')
  data.value = res.data
  loading.value = false
  await nextTick()
  drawCharts()
})

function pie(el, dist, colors) {
  const c = echarts.init(el)
  c.setOption({
    tooltip: {},
    color: colors,
    series: [{ type: 'pie', radius: ['42%', '70%'], data: Object.entries(dist).map(([name, value]) => ({ name, value })),
      label: { formatter: '{b}: {c}' } }]
  })
  charts.push(c)
}
function drawCharts() {
  const rb = data.value.radiusBuckets
  pie(radiusChart.value, { '<1km': rb.lt1km, '1-3km': rb.km1to3, '3-5km': rb.km3to5, '>5km': rb.gt5km },
    ['#67c23a', '#409eff', '#e6a23c', '#f56c6c'])
  pie(excChart.value, data.value.exceptionDistribution,
    ['#f56c6c', '#e6a23c', '#909399', '#409eff', '#f78fb3', '#9b59b6', '#95a5a6'])
  pie(riskChart.value, data.value.riskDistribution, ['#67c23a', '#e6a23c', '#f56c6c'])
  const mc = echarts.init(matChart.value)
  const top = data.value.materialConsumeTop || []
  mc.setOption({
    tooltip: {},
    grid: { left: 110, right: 20, top: 10, bottom: 20 },
    xAxis: { type: 'value' },
    yAxis: { type: 'category', data: top.map(t => t.name).reverse(), axisLabel: { fontSize: 11 } },
    series: [{ type: 'bar', data: top.map(t => t.usedQty).reverse(),
      itemStyle: { color: p => top.slice().reverse()[p.dataIndex].coldChain ? '#409eff' : '#1f6f5c' } }]
  })
  charts.push(mc)
}

onBeforeUnmount(() => charts.forEach(c => c.dispose()))
</script>

<style scoped>
.kpi-card { text-align: center; padding: 18px; }
.chart-title { font-weight: 600; font-size: 14px; margin-bottom: 8px; color: #303a45; }
.chart { height: 230px; }
.ml6 { margin-left: 6px; }
</style>
