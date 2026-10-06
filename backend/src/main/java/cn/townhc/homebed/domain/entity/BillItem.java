package cn.townhc.homebed.domain.entity;

import cn.townhc.homebed.domain.enums.DomainEnums.BillSourceType;
import cn.townhc.homebed.domain.enums.DomainEnums.CatalogCategory;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;

/**
 * 账单明细行。保存收费时的名称/价格/报销比例快照，
 * 即使之后医保目录变化也能还原当次结算依据。
 * 每行可回溯到来源单据（巡诊记录/耗材出库流水）。
 */
@Getter
@Setter
@Entity
@Table(name = "bill_item")
public class BillItem extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bill_id")
    private Bill bill;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BillSourceType sourceType;

    /** 来源单据ID */
    private Long sourceId;

    /** 来源单号，便于展示与追溯 */
    @Column(length = 32)
    private String sourceNo;

    /** 产生服务的巡诊时间（文本快照） */
    @Column(length = 32)
    private String serviceDate;

    @Column(length = 32)
    private String itemCode;

    @Column(nullable = false, length = 64)
    private String itemName;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private CatalogCategory category;

    private Integer quantity = 1;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    private Boolean reimbursable = false;

    @Column(length = 8)
    private String insuranceCategory;

    @Column(precision = 4, scale = 2)
    private BigDecimal reimbursementRate;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal insuranceAmount = BigDecimal.ZERO;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal selfPayAmount = BigDecimal.ZERO;

    @Column(length = 512)
    private String note;

    /** 是否因转诊费用截止（患者已住院）被剔除、不计费 */
    private Boolean cutoffExcluded = false;
}
