<template>
  <el-container class="layout">
    <el-aside width="232px" class="aside">
      <div class="logo">
        <el-icon :size="22"><HomeFilled /></el-icon>
        <span>家庭病床服务平台</span>
      </div>
      <div class="sub">青山镇卫生院</div>
      <el-menu :default-active="route.path" router :collapse="false"
               background-color="#15403a" text-color="#cfe2dd" active-text-color="#ffffff">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="title-crumb">{{ route.name }}</div>
        <div class="user-box">
          <el-tag type="success" effect="plain" round>{{ roleLabel }}</el-tag>
          <span class="uname">{{ auth.user?.name }}</span>
          <el-button text @click="logout"><el-icon><SwitchButton /></el-icon> 退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'
import { ENUMS } from '../labels'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const roleLabel = computed(() => ENUMS.Role[auth.role] || '')

const ALL_MENUS = [
  { path: '/dashboard', title: '运营看板', icon: 'DataAnalysis', roles: ['ADMIN'] },
  { path: '/applications', title: '建床申请', icon: 'Document', roles: ['FAMILY', 'FAMILY_DOCTOR', 'ADMIN', 'INSURANCE_AUDITOR', 'FINANCE', 'NURSE'] },
  { path: '/plans', title: '巡诊计划排班', icon: 'Calendar', roles: ['ADMIN', 'FAMILY_DOCTOR', 'NURSE'] },
  { path: '/visits', title: '上门巡诊', icon: 'Van', roles: ['NURSE', 'ADMIN', 'FAMILY_DOCTOR'] },
  { path: '/exceptions', title: '巡诊异常', icon: 'WarningFilled', roles: ['NURSE', 'ADMIN', 'FAMILY_DOCTOR'] },
  { path: '/orders', title: '医嘱与远程复核', icon: 'FirstAidKit', roles: ['FAMILY_DOCTOR', 'ADMIN', 'NURSE'] },
  { path: '/risks', title: '风险事件', icon: 'Alert', roles: ['ADMIN', 'FAMILY_DOCTOR', 'NURSE'] },
  { path: '/referrals', title: '转诊闭环', icon: 'Promotion', roles: ['NURSE', 'FAMILY_DOCTOR', 'ADMIN', 'FAMILY'] },
  { path: '/materials', title: '耗材与冷链', icon: 'Box', roles: ['ADMIN', 'FINANCE', 'NURSE'] },
  { path: '/bills', title: '统一账单', icon: 'Money', roles: ['FAMILY', 'FINANCE', 'ADMIN', 'INSURANCE_AUDITOR'] },
  { path: '/catalogs', title: '收费医保目录', icon: 'Tickets', roles: ['FINANCE', 'ADMIN'] },
  { path: '/compliance', title: '医保合规审查', icon: 'CircleCheck', roles: ['INSURANCE_AUDITOR', 'ADMIN', 'FINANCE'] },
  { path: '/patients', title: '患者档案', icon: 'User', roles: ['FAMILY', 'FAMILY_DOCTOR', 'NURSE', 'ADMIN', 'FINANCE', 'INSURANCE_AUDITOR'] },
  { path: '/trace', title: '服务全链还原', icon: 'Connection', roles: ['FAMILY', 'FAMILY_DOCTOR', 'NURSE', 'ADMIN', 'FINANCE', 'INSURANCE_AUDITOR'] },
  { path: '/audit-logs', title: '审计日志', icon: 'Notebook', roles: ['ADMIN'] }
]

const menus = computed(() => ALL_MENUS.filter(m => m.roles.includes(auth.role)))

// 角色落点
const HOME = { ADMIN: '/dashboard', FAMILY: '/applications', FAMILY_DOCTOR: '/applications',
  NURSE: '/visits', FINANCE: '/bills', INSURANCE_AUDITOR: '/compliance' }
if (route.path === '/' || route.path === '/dashboard-role' ||
  (route.meta.roles && !route.meta.roles.includes(auth.role))) {
  router.replace(HOME[auth.role] || '/applications')
}

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #15403a; overflow-y: auto; }
.logo { color: #fff; font-weight: 700; font-size: 16px; display: flex; align-items: center; gap: 8px; padding: 18px 16px 4px; }
.sub { color: #8fb3ab; font-size: 12px; padding: 0 16px 14px; }
.aside :deep(.el-menu) { background: #15403a; }
.header {
  background: #fff; display: flex; align-items: center; justify-content: space-between;
  border-bottom: 1px solid #edf0f2;
}
.title-crumb { font-weight: 600; color: #303a45; }
.user-box { display: flex; align-items: center; gap: 10px; }
.uname { color: #555; font-size: 13px; }
.main { background: #f4f6f8; padding: 18px; }
</style>
