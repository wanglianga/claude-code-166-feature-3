package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.FeedbackSource;
import cn.townhc.homebed.domain.enums.DomainEnums.ReferralDecision;
import cn.townhc.homebed.domain.enums.DomainEnums.ReferralStatus;
import cn.townhc.homebed.domain.enums.DomainEnums.ResumeDecision;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 病情恶化转诊闭环单。
 * 护士巡诊发现病情恶化（如呼吸困难）→ 标记高危并通知医生/家属/转诊医院
 * → 医生远程判断发起救护车转运（家庭病床暂停、未结算项目待审核）
 * → 等待医院回传入院诊断或家属补充结果（确定费用截至时间）
 * → 出院后医生决定恢复或终止家庭病床。
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

    /** 发现病情恶化的巡诊任务（可空，电话随访等场景无巡诊单） */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id")
    private Visit visit;

    /** 联动生成的高危风险事件 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "risk_event_id")
    private RiskEvent riskEvent;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "application_id")
    private BedApplication application;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id")
    private VisitPlan plan;

    /** 主要症状，如：呼吸困难 */
    @Column(nullable = false, length = 64)
    private String symptom;

    @Column(length = 1024)
    private String description;

    /** 拟转诊医院 */
    @Column(length = 64)
    private String targetHospital;

    @Column(length = 20)
    private String hospitalPhone;

    @Column(length = 256)
    private String evidencePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReferralStatus status = ReferralStatus.MARKED;

    // ===== 三方通知 =====
    private LocalDateTime notifiedDoctorAt;
    private LocalDateTime notifiedFamilyAt;
    private LocalDateTime notifiedHospitalAt;

    // ===== 医生远程判断 =====
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "decision_doctor_id")
    private SysUser decisionDoctor;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ReferralDecision decision;

    @Column(length = 1024)
    private String decisionNote;

    private LocalDateTime decidedAt;

    /** 救护车转运说明（车牌/随车医护/预计到达等） */
    @Column(length = 512)
    private String ambulanceNote;

    private LocalDateTime ambulanceDispatchedAt;

    // ===== 入院结果（医院回传或家属补充） =====
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private FeedbackSource feedbackSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feedback_by_id")
    private SysUser feedbackBy;

    private LocalDateTime feedbackAt;

    /** 入院诊断 */
    @Column(length = 512)
    private String admissionDiagnosis;

    /** 入院时间（即费用截至依据） */
    private LocalDateTime admittedAt;

    /** 家庭病床费用截至时间：入院时点起不再计家庭病床费用 */
    private LocalDateTime feeCutoffAt;

    @Column(length = 512)
    private String feeCutoffNote;

    // ===== 出院 =====
    private LocalDateTime dischargedAt;

    @Column(length = 512)
    private String dischargeNote;

    // ===== 出院后医生恢复决定 =====
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private ResumeDecision resumeDecision;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resume_doctor_id")
    private SysUser resumeDoctor;

    private LocalDateTime resumeDecidedAt;

    @Column(length = 512)
    private String resumeNote;
}
