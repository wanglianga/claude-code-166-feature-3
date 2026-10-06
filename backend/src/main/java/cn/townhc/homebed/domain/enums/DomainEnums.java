package cn.townhc.homebed.domain.enums;

/**
 * 平台领域枚举集中定义。JPA 统一以 EnumType.STRING 持久化。
 */
public final class DomainEnums {
    private DomainEnums() {}

    public enum Role {
        FAMILY("家属"),
        FAMILY_DOCTOR("家庭医生"),
        NURSE("护士"),
        ADMIN("卫生院管理者"),
        FINANCE("财务"),
        INSURANCE_AUDITOR("医保经办");

        public final String label;
        Role(String label) { this.label = label; }
    }

    public enum BedApplyStatus {
        DRAFT("草稿"),
        SUBMITTED("待评估"),
        APPROVED("批准建床"),
        REJECTED("驳回"),
        SUSPENDED("暂停建床"),
        TERMINATED("转诊终止建床"),
        CANCELLED("家属撤销");

        public final String label;
        BedApplyStatus(String label) { this.label = label; }
    }

    public enum RiskLevel {
        LOW("低风险"), MEDIUM("中风险"), HIGH("高风险"), CRITICAL("极高风险");
        public final String label;
        RiskLevel(String label) { this.label = label; }
    }

    public enum DiseaseCategory {
        POST_SURGICAL("术后康复"),
        DIABETES("糖尿病"),
        HYPERTENSION("高血压"),
        STROKE_REHAB("脑卒中康复"),
        COPD("慢阻肺"),
        PRESSURE_ULCER("压疮"),
        OTHER("其他");
        public final String label;
        DiseaseCategory(String label) { this.label = label; }
    }

    public enum InsuranceType {
        URBAN_EMPLOYEE("城镇职工医保"),
        URBAN_RESIDENT("城乡居民医保"),
        NONE("无医保");
        public final String label;
        InsuranceType(String label) { this.label = label; }
    }

    /** 评估时勾选的护理需求类型 */
    public enum CareItemType {
        VITALS("生命体征监测"),
        BLOOD_PRESSURE("血压测量"),
        BLOOD_GLUCOSE("血糖测量"),
        PRESSURE_ULCER("压疮护理"),
        DRESSING("伤口换药"),
        CATHETER("导尿护理"),
        INJECTION("注射/输液"),
        MEDICATION_REMINDER("用药提醒与核对"),
        HEALTH_EDUCATION("健康指导");
        public final String label;
        CareItemType(String label) { this.label = label; }
    }

    public enum PlanStatus { ACTIVE("执行中"), PAUSED("已暂停"), COMPLETED("已完成"), CANCELLED("已取消");
        public final String label; PlanStatus(String l) { this.label = l; } }

    public enum VisitStatus {
        SCHEDULED("已排班"), IN_PROGRESS("上门中"), COMPLETED("已完成"),
        EXCEPTION("异常挂起"), CANCELLED("已取消");
        public final String label; VisitStatus(String l) { this.label = l; } }

    public enum ExceptionType {
        PATIENT_HOSPITALIZED("患者临时住院"),
        FAMILY_ABSENT("家属不在家"),
        SUPPLY_SHORTAGE("耗材缺货"),
        NURSE_OVERTIME("护士上门超时"),
        CONDITION_WORSENED("患者病情恶化"),
        ROUTE_BLOCKED("道路/天气受阻"),
        OTHER("其他");
        public final String label; ExceptionType(String l) { this.label = l; } }

    public enum ExceptionStatus { REPORTED("已上报"), CONFIRMED("已核实"), RESOLVED("已处理");
        public final String label; ExceptionStatus(String l) { this.label = l; } }

    public enum CareResultStatus { DONE("已完成"), SKIPPED("未执行"), ABNORMAL("异常中止");
        public final String label; CareResultStatus(String l) { this.label = l; } }

    public enum OrderType {
        PRESCRIPTION("调整处方"),
        FOLLOW_UP("安排复诊"),
        REFERRAL("建议转诊"),
        CARE_PLAN_CHANGE("调整护理方案");
        public final String label; OrderType(String l) { this.label = l; } }

    public enum OrderStatus {
        PROPOSED("已下达"), REMOTE_REVIEWED("远程复核通过"), CONFIRMED("已确认"),
        EXECUTED("已执行"), VOID("已作废");
        public final String label; OrderStatus(String l) { this.label = l; } }

    public enum CatalogCategory {
        VISIT_FEE("巡诊费"),
        NURSING("护理项目"),
        CONSUMABLE("普通耗材"),
        COLDCHAIN_DRUG("冷链药品"),
        DRUG("普通药品");
        public final String label; CatalogCategory(String l) { this.label = l; } }

    public enum BillSourceType { VISIT_CARE("护理执行"), MATERIAL("耗材领用"), ORDER("医嘱费用");
        public final String label; BillSourceType(String l) { this.label = l; } }

    public enum BillStatus {
        DRAFT("待确认"), PENDING_REVIEW("转诊待审核"), CONFIRMED("家属已确认"),
        INSURANCE_REVIEWED("医保复核完成"),
        DISPUTED("家属质疑"), SETTLED("已结算");
        public final String label; BillStatus(String l) { this.label = l; } }

    public enum ComplianceStatus { PENDING("待审查"), COMPLIANT("合规"), VIOLATION("存疑/违规");
        public final String label; ComplianceStatus(String l) { this.label = l; } }

    public enum RiskSeverity { LOW("一般"), MEDIUM("较重"), HIGH("严重");
        public final String label; RiskSeverity(String l) { this.label = l; } }

    public enum RiskEventStatus { OPEN("待处理"), CONFIRMED("已确认"), CLOSED("已闭环");
        public final String label; RiskEventStatus(String l) { this.label = l; } }

    public enum MaterialTxType { OUT("领用消耗"), IN("入库"), ADJUST("盘点调整");
        public final String label; MaterialTxType(String l) { this.label = l; } }

    /** 病情恶化转诊闭环状态机 */
    public enum ReferralStatus {
        MARKED("已标记高危·待医生远程判断"),
        AMBULANCE_DISPATCHED("救护车转运中·待入院回传"),
        ADMITTED("已入院·费用截至已确定"),
        DISCHARGED("已出院·待医生恢复决定"),
        RESUMED("已恢复家庭病床"),
        CLOSED("已闭环·终止建床"),
        CANCELLED("医生判断无需转诊");
        public final String label; ReferralStatus(String l) { this.label = l; } }

    /** 医生远程判断结论 */
    public enum ReferralDecision { AMBULANCE("发起救护车转运"), OBSERVE("继续居家观察");
        public final String label; ReferralDecision(String l) { this.label = l; } }

    /** 出院后医生对家庭病床的恢复决定 */
    public enum ResumeDecision { RESUME("恢复家庭病床"), TERMINATE("终止家庭病床");
        public final String label; ResumeDecision(String l) { this.label = l; } }

    /** 入院结果的来源：医院回传 / 家属补充 */
    public enum FeedbackSource { HOSPITAL("医院回传"), FAMILY("家属补充");
        public final String label; FeedbackSource(String l) { this.label = l; } }

    /** 转诊通知接收方 */
    public enum NotifyRecipientType { DOCTOR("家庭医生"), FAMILY("家属"), HOSPITAL("转诊医院");
        public final String label; NotifyRecipientType(String l) { this.label = l; } }
}
