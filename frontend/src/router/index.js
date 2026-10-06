import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: '看板', component: () => import('../views/Dashboard.vue'),
        meta: { roles: ['ADMIN'] } },
      { path: 'patients', name: '患者档案', component: () => import('../views/Patients.vue') },
      { path: 'applications', name: '建床申请', component: () => import('../views/Applications.vue') },
      { path: 'plans', name: '巡诊计划排班', component: () => import('../views/Plans.vue'),
        meta: { roles: ['ADMIN', 'FAMILY_DOCTOR', 'NURSE'] } },
      { path: 'visits', name: '上门巡诊', component: () => import('../views/Visits.vue'),
        meta: { roles: ['NURSE', 'ADMIN', 'FAMILY_DOCTOR'] } },
      { path: 'exceptions', name: '巡诊异常', component: () => import('../views/Exceptions.vue'),
        meta: { roles: ['NURSE', 'ADMIN', 'FAMILY_DOCTOR'] } },
      { path: 'orders', name: '医嘱管理', component: () => import('../views/Orders.vue'),
        meta: { roles: ['FAMILY_DOCTOR', 'ADMIN', 'NURSE'] } },
      { path: 'materials', name: '耗材冷链', component: () => import('../views/Materials.vue'),
        meta: { roles: ['ADMIN', 'FINANCE', 'NURSE'] } },
      { path: 'bills', name: '统一账单', component: () => import('../views/Bills.vue') },
      { path: 'catalogs', name: '收费医保目录', component: () => import('../views/Catalogs.vue'),
        meta: { roles: ['FINANCE', 'ADMIN'] } },
      { path: 'compliance', name: '医保合规审查', component: () => import('../views/Compliance.vue'),
        meta: { roles: ['INSURANCE_AUDITOR', 'ADMIN', 'FINANCE'] } },
      { path: 'risks', name: '风险事件', component: () => import('../views/Risks.vue'),
        meta: { roles: ['ADMIN', 'FAMILY_DOCTOR', 'NURSE'] } },
      { path: 'referrals', name: '病情恶化转诊闭环', component: () => import('../views/Referrals.vue'),
        meta: { roles: ['NURSE', 'FAMILY_DOCTOR', 'ADMIN', 'FAMILY'] } },
      { path: 'trace', name: '服务全链还原', component: () => import('../views/Trace.vue') },
      { path: 'audit-logs', name: '审计日志', component: () => import('../views/AuditLogs.vue'),
        meta: { roles: ['ADMIN'] } }
    ]
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (to.meta.public) return true
  if (!token) return '/login'
  if (to.meta.roles) {
    const role = JSON.parse(localStorage.getItem('user') || '{}').role
    if (!to.meta.roles.includes(role)) return '/dashboard-role'
  }
  return true
})

// 无看板权限角色的落点由 MainLayout 重定向处理
export default router
