package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.DiagnosisSource;
import cn.townhc.homebed.domain.enums.DomainEnums.ReferralStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 病情恶化转诊闭环单。
 * 护士巡诊发现高危（如呼吸困难）→ 立即标记并通知家庭医生/家属/转诊医院
 * → 医生远程判断发起救护车转运 → 家庭病床与巡诊计划暂停、未结算费用进入待审核
 * → 转诊完成等待医院回传/家属补充入院诊断 → 确定家庭病床费用截至时间
 * → 出院后由医生决定恢复家庭病床或撤床。
 */
@Getter
@Setter
@Entity
@Table(name = "referral")
public class Referral extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String referralNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    /** 关联家庭病床申请 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private BedApplication application;

    /** 关联巡诊计划（暂停/恢复对象） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private VisitPlan plan;

    /** 触发转诊的风险事件 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_event_id")
    private RiskEvent riskEvent;

    /** 发现病情恶化的巡诊任务（可空，电话上报场景） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReferralStatus status = ReferralStatus.CRITICAL_FLAGGED;

    /** 主诉/恶化表现，如“突发呼吸困难、口唇发绀” */
    @Column(nullable = false, length = 1024)
    private String symptom;

    /** 转诊原因补充 */
    @Column(length = 1024)
    private String description;

    // ===== 阶段1：高危标记与三方通知 =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "flagged_by_id")
    private SysUser flaggedBy;

    private LocalDateTime flaggedAt;

    /** 拟转诊医院名称 */
    @Column(length = 128)
    private String targetHospital;

    /** 转诊医院对接方式（科室/电话/接诊工位） */
    @Column(length = 256)
    private String targetHospitalContact;

    private LocalDateTime doctorNotifiedAt;
    private LocalDateTime familyNotifiedAt;
    private LocalDateTime hospitalNotifiedAt;

    /** 三方通知结果快照（平台短信/推送/医院对接回执） */
    @Column(length = 2048)
    private String notifySnapshot;

    // ===== 阶段2：医生远程判断 + 救护车 =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "judge_doctor_id")
    private SysUser judgeDoctor;

    private LocalDateTime judgedAt;

    @Column(length = 1024)
    private String judgeNote;

    private LocalDateTime ambulanceDispatchedAt;

    @Column(length = 512)
    private String ambulanceNote;

    /** 家庭病床/计划暂停时间 */
    private LocalDateTime bedSuspendedAt;

    /** 转入待审核的未结算账单数（冻结快照） */
    private Integer pendingBillCount = 0;

    // ===== 阶段3：转诊完成 =====
    private LocalDateTime referredAt;

    /** 实际接诊医院 */
    @Column(length = 128)
    private String admittedHospital;

    // ===== 阶段4：入院诊断回传 + 费用截止 =====
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private DiagnosisSource diagnosisSource;

    /** 入院诊断 */
    @Column(length = 1024)
    private String admissionDiagnosis;

    /** 医院/家属提交诊断时间 */
    private LocalDateTime diagnosisReceivedAt;

    /** 医院实际入院时间（决定费用截止的依据） */
    private LocalDateTime admittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "diagnosis_by_id")
    private SysUser diagnosisBy;

    /** 家庭病床费用截至时间：取救护车出发/实际入院较早者，医生可修正 */
    private LocalDateTime feeCutoffAt;

    @Column(length = 512)
    private String feeCutoffNote;

    // ===== 阶段5：出院与恢复/撤床决策 =====
    private LocalDate dischargeDate;

    @Column(length = 1024)
    private String dischargeNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decide_doctor_id")
    private SysUser decideDoctor;

    private LocalDateTime decidedAt;

    /** 恢复后首次巡诊日期（医生决定恢复时给出） */
    private LocalDate resumeVisitDate;

    /** 医生恢复/撤床意见 */
    @Column(length = 1024)
    private String decisionNote;

    private LocalDateTime closedAt;

    @Column(length = 1024)
    private String closeNote;
}
