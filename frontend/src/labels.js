// 与后端 DomainEnums 保持一致的中文标签
const L = {
  Role: { FAMILY: '家属', FAMILY_DOCTOR: '家庭医生', NURSE: '护士', ADMIN: '管理者', FINANCE: '财务', INSURANCE_AUDITOR: '医保经办' },
  BedApplyStatus: { DRAFT: '草稿', SUBMITTED: '待评估', APPROVED: '批准建床', REJECTED: '驳回', SUSPENDED: '暂停建床', WITHDRAWN: '已撤床', CANCELLED: '家属撤销' },
  RiskLevel: { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险', CRITICAL: '极高风险' },
  DiseaseCategory: { POST_SURGICAL: '术后康复', DIABETES: '糖尿病', HYPERTENSION: '高血压', STROKE_REHAB: '脑卒中康复', COPD: '慢阻肺', PRESSURE_ULCER: '压疮', OTHER: '其他' },
  InsuranceType: { URBAN_EMPLOYEE: '城镇职工医保', URBAN_RESIDENT: '城乡居民医保', NONE: '无医保' },
  CareItemType: { VITALS: '生命体征监测', BLOOD_PRESSURE: '血压测量', BLOOD_GLUCOSE: '血糖测量', PRESSURE_ULCER: '压疮护理', DRESSING: '伤口换药', CATHETER: '导尿护理', INJECTION: '注射/输液', MEDICATION_REMINDER: '用药提醒与核对', HEALTH_EDUCATION: '健康指导' },
  PlanStatus: { ACTIVE: '执行中', PAUSED: '已暂停', COMPLETED: '已完成', CANCELLED: '已取消' },
  VisitStatus: { SCHEDULED: '已排班', IN_PROGRESS: '上门中', COMPLETED: '已完成', EXCEPTION: '异常挂起', CANCELLED: '已取消' },
  ExceptionType: { PATIENT_HOSPITALIZED: '患者临时住院', FAMILY_ABSENT: '家属不在家', SUPPLY_SHORTAGE: '耗材缺货', NURSE_OVERTIME: '护士上门超时', CONDITION_WORSENED: '患者病情恶化', ROUTE_BLOCKED: '道路/天气受阻', OTHER: '其他' },
  ExceptionStatus: { REPORTED: '已上报', CONFIRMED: '已核实', RESOLVED: '已处理' },
  CareResultStatus: { DONE: '已完成', SKIPPED: '未执行', ABNORMAL: '异常中止' },
  OrderType: { PRESCRIPTION: '调整处方', FOLLOW_UP: '安排复诊', REFERRAL: '建议转诊', CARE_PLAN_CHANGE: '调整护理方案' },
  OrderStatus: { PROPOSED: '已下达', REMOTE_REVIEWED: '远程复核通过', CONFIRMED: '已确认', EXECUTED: '已执行', VOID: '已作废' },
  CatalogCategory: { VISIT_FEE: '巡诊费', NURSING: '护理项目', CONSUMABLE: '普通耗材', COLDCHAIN_DRUG: '冷链药品', DRUG: '普通药品' },
  BillSourceType: { VISIT_CARE: '护理执行', MATERIAL: '耗材领用', ORDER: '医嘱费用' },
  BillStatus: { DRAFT: '待确认', CONFIRMED: '家属已确认', INSURANCE_REVIEWED: '医保复核完成', DISPUTED: '家属质疑', PENDING_REVIEW: '转诊待审核', FROZEN: '转诊冻结', SETTLED: '已结算' },
  ComplianceStatus: { PENDING: '待审查', COMPLIANT: '合规', VIOLATION: '存疑/违规' },
  RiskSeverity: { LOW: '一般', MEDIUM: '较重', HIGH: '严重' },
  RiskEventStatus: { OPEN: '待处理', CONFIRMED: '已确认', CLOSED: '已闭环' },
  MaterialTxType: { OUT: '领用消耗', IN: '入库', ADJUST: '盘点调整' },
  ReferralStatus: { CRITICAL_FLAGGED: '高危已标记', AMBULANCE_DISPATCHED: '救护车转运中', REFERRED: '转诊完成·待回传诊断', DIAGNOSIS_RECEIVED: '诊断已回传·费用已截止', DISCHARGED: '已出院·待医生决定', RESUMED: '家庭病床已恢复', WITHDRAWN: '已撤床闭环' },
  DiagnosisSource: { HOSPITAL_CALLBACK: '医院回传', FAMILY_SUPPLEMENT: '家属补充' }
}

export function label(group, value) {
  if (value === null || value === undefined || value === '') return '-'
  return L[group]?.[value] || value
}

export function tagType(group, value) {
  const map = {
    BedApplyStatus: { SUBMITTED: 'warning', APPROVED: 'success', REJECTED: 'danger', SUSPENDED: 'warning', WITHDRAWN: 'info', CANCELLED: 'info' },
    RiskLevel: { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger', CRITICAL: 'danger' },
    VisitStatus: { SCHEDULED: 'primary', IN_PROGRESS: 'warning', COMPLETED: 'success', EXCEPTION: 'danger', CANCELLED: 'info' },
    ExceptionStatus: { REPORTED: 'danger', CONFIRMED: 'warning', RESOLVED: 'success' },
    OrderStatus: { PROPOSED: 'warning', REMOTE_REVIEWED: 'primary', EXECUTED: 'success', VOID: 'info' },
    BillStatus: { DRAFT: 'warning', CONFIRMED: 'primary', INSURANCE_REVIEWED: 'success', DISPUTED: 'danger', PENDING_REVIEW: 'danger', FROZEN: 'info', SETTLED: 'success' },
    ComplianceStatus: { PENDING: 'info', COMPLIANT: 'success', VIOLATION: 'danger' },
    RiskSeverity: { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger' },
    RiskEventStatus: { OPEN: 'danger', CONFIRMED: 'warning', CLOSED: 'success' },
    ReferralStatus: { CRITICAL_FLAGGED: 'danger', AMBULANCE_DISPATCHED: 'warning', REFERRED: 'warning', DIAGNOSIS_RECEIVED: 'primary', DISCHARGED: 'warning', RESUMED: 'success', WITHDRAWN: 'info' }
  }
  return map[group]?.[value] || 'info'
}

export const ENUMS = L
