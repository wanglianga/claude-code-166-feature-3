package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.BillStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统一账单：把同一周期内的护理服务、耗材领用、冷链药品、
 * 医保可报销项目与自费项目合并，家属一次性确认。
 */
@Getter
@Setter
@Entity
@Table(name = "bill")
public class Bill extends BaseEntity {
    @Column(nullable = false, unique = true, length = 32)
    private String billNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id")
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "application_id")
    private BedApplication application;

    /** 账期 */
    private LocalDate periodStart;
    private LocalDate periodEnd;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BillStatus status = BillStatus.DRAFT;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal insuranceAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal selfPayAmount = BigDecimal.ZERO;

    /** 财务生成人 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by_id")
    private SysUser createdBy;

    private LocalDateTime familyConfirmedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "confirmed_user_id")
    private SysUser confirmedUser;

    /** 家属确认意见 */
    @Column(length = 512)
    private String familyConfirmNote;

    /** 家属质疑内容 */
    @Column(length = 1024)
    private String disputeNote;

    private LocalDateTime disputedAt;

    /** 质疑处理说明 */
    @Column(length = 1024)
    private String disputeResolution;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "dispute_handler_id")
    private SysUser disputeHandler;

    private LocalDateTime disputeResolvedAt;

    private LocalDateTime settledAt;

    /** 医保结算批次号 */
    @Column(length = 32)
    private String insuranceSettleNo;

    /** 转诊闭环单：转诊期间冻结/费用截止的账单关联 */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "referral_id")
    private Referral referral;

    /** 本账单出账时应用的家庭病床费用截至时间（住院后服务不计费） */
    private java.time.LocalDateTime feeCutoffAt;

    /** 因费用截至被剔除的明细行数 */
    private Integer cutoffExcludedCount = 0;

    /** 账单冻结/待审核原因（转诊住院） */
    @Column(length = 512)
    private String freezeReason;
}
