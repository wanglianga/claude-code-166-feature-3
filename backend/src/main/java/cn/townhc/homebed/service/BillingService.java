package cn.townhc.homebed.service;

import cn.townhc.homebed.common.BusinessException;
import cn.townhc.homebed.domain.entity.*;
import cn.townhc.homebed.domain.enums.DomainEnums.*;
import cn.townhc.homebed.repository.*;
import cn.townhc.homebed.security.LoginUser;
import cn.townhc.homebed.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 统一账单：护理执行、耗材领用、冷链药品合并到一张账单，
 * 按医保目录快照拆分医保报销与家属自费，杜绝“护士记了服务、财务不知如何结算”。
 */
@Service
@RequiredArgsConstructor
public class BillingService {

    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final VisitRepository visitRepository;
    private final VisitCareResultRepository careResultRepository;
    private final MaterialTransactionRepository materialTxRepository;
    private final ChargeCatalogRepository catalogRepository;
    private final BedApplicationRepository applicationRepository;
    private final SysUserRepository userRepository;
    private final ComplianceService complianceService;
    private final AuditService auditService;
    private final ReferralRepository referralRepository;

    /**
     * 财务按申请单 + 账期生成统一账单（幂等：已出账的来源行不重复计入）。
     */
    @Transactional
    public Bill generate(Long applicationId, LocalDate periodStart, LocalDate periodEnd) {
        LoginUser me = SecurityUtils.current();
        BedApplication app = applicationRepository.findById(applicationId)
                .orElseThrow(() -> new BusinessException(404, "建床申请不存在"));
        Patient patient = app.getPatient();

        // 转诊闭环：救护车转运中且尚未确定费用截至时间 → 未结算项目待审核，禁止出账
        List<Referral> waitingFeedback = referralRepository.findByPatientIdAndStatusIn(
                patient.getId(), List.of(ReferralStatus.AMBULANCE_DISPATCHED));
        if (!waitingFeedback.isEmpty()) {
            Referral r = waitingFeedback.get(0);
            throw new BusinessException("患者存在进行中的转诊（" + r.getReferralNo()
                    + "），正等待医院回传入院诊断或家属补充住院结果以确定费用截至时间，"
                    + "未结算项目已转入待审核，暂不能出账");
        }

        // 转诊住院窗口：[费用截至时间(=入院时间), 出院时间]，窗口内巡诊不计家庭病床费用
        List<Referral> cutoffReferrals = referralRepository.findByPatientIdAndStatusIn(
                patient.getId(), List.of(ReferralStatus.ADMITTED, ReferralStatus.DISCHARGED,
                        ReferralStatus.RESUMED, ReferralStatus.CLOSED))
                .stream().filter(r -> r.getFeeCutoffAt() != null).toList();

        List<Visit> visits = visitRepository.findByPatientIdOrderByScheduledAtDesc(patient.getId()).stream()
                .filter(v -> v.getCompletedAt() != null)
                .filter(v -> {
                    LocalDate d = v.getCompletedAt().toLocalDate();
                    return !d.isBefore(periodStart) && !d.isAfter(periodEnd);
                })
                .toList();
        // 剔除住院窗口内的巡诊（患者已住院，家庭病床不得继续计费）
        List<Visit> billable = new ArrayList<>();
        int hospitalizedExcluded = 0;
        LocalDateTime earliestCutoff = null;
        for (Visit v : visits) {
            boolean inHospital = false;
            for (Referral r : cutoffReferrals) {
                LocalDateTime windowEnd = r.getDischargedAt() != null
                        ? r.getDischargedAt() : LocalDateTime.now();
                if (!v.getCompletedAt().isBefore(r.getFeeCutoffAt())
                        && !v.getCompletedAt().isAfter(windowEnd)) {
                    inHospital = true;
                    if (earliestCutoff == null || r.getFeeCutoffAt().isBefore(earliestCutoff)) {
                        earliestCutoff = r.getFeeCutoffAt();
                    }
                    break;
                }
            }
            if (inHospital) hospitalizedExcluded++;
            else billable.add(v);
        }
        visits = billable;
        if (visits.isEmpty()) {
            throw new BusinessException("账期 " + periodStart + " ~ " + periodEnd
                    + " 内没有可结算的巡诊记录"
                    + (hospitalizedExcluded > 0 ? "（" + hospitalizedExcluded
                    + " 次巡诊发生在转诊住院期间，费用截至 " + earliestCutoff + "，不计费）" : "，无法出账"));
        }

        // 已出账来源（任意状态账单）防重复收费
        Set<String> billedKeys = new HashSet<>();
        for (BillItem bi : billItemRepository.findAll()) {
            billedKeys.add(bi.getSourceType() + ":" + bi.getSourceId());
        }

        Bill bill = new Bill();
        bill.setBillNo(ApplicationService.generateNo("ZD"));
        bill.setPatient(patient);
        bill.setApplication(app);
        bill.setPeriodStart(periodStart);
        bill.setPeriodEnd(periodEnd);
        bill.setStatus(BillStatus.DRAFT);
        bill.setCreatedBy(userRepository.findById(me.id()).orElse(null));
        bill = billRepository.save(bill);

        BigDecimal total = BigDecimal.ZERO;
        BigDecimal insuranceTotal = BigDecimal.ZERO;
        BigDecimal selfTotal = BigDecimal.ZERO;
        int skipped = 0;

        for (Visit visit : visits) {
            String serviceDate = visit.getCompletedAt().toLocalDate().toString();

            // 1) 护理服务执行项
            for (VisitCareResult r : careResultRepository.findByVisitId(visit.getId())) {
                if (r.getResultStatus() == CareResultStatus.SKIPPED) continue;
                String key = BillSourceType.VISIT_CARE + ":" + r.getId();
                if (billedKeys.contains(key)) { skipped++; continue; }
                ChargeCatalog cat = r.getCatalog() != null
                        ? catalogRepository.findById(r.getCatalog().getId()).orElse(null) : null;
                BillItem item = new BillItem();
                item.setBill(bill);
                item.setSourceType(BillSourceType.VISIT_CARE);
                item.setSourceId(r.getId());
                item.setSourceNo(visit.getVisitNo());
                item.setServiceDate(serviceDate);
                item.setItemCode(r.getItemCode());
                item.setItemName(r.getItemName());
                item.setCategory(cat != null ? cat.getCategory() : CatalogCategory.NURSING);
                item.setQuantity(r.getQuantity());
                item.setUnitPrice(r.getUnitPrice());
                item.setAmount(money(r.getUnitPrice().multiply(BigDecimal.valueOf(r.getQuantity()))));
                item.setReimbursable(cat != null && Boolean.TRUE.equals(cat.getReimbursable()));
                item.setInsuranceCategory(cat != null ? cat.getInsuranceCategory() : "丙类");
                item.setReimbursementRate(cat != null ? cat.getReimbursementRate() : null);
                item.setNote("巡诊护理：" + (r.getNote() == null ? "" : r.getNote()));
                applySplit(item, app);
                billItemRepository.save(item);
                billedKeys.add(key);
                total = total.add(item.getAmount());
                insuranceTotal = insuranceTotal.add(item.getInsuranceAmount());
                selfTotal = selfTotal.add(item.getSelfPayAmount());
            }

            // 2) 耗材领用 / 冷链药品出库
            for (MaterialTransaction tx : materialTxRepository.findByVisitId(visit.getId())) {
                if (tx.getType() != MaterialTxType.OUT) continue;
                String key = BillSourceType.MATERIAL + ":" + tx.getId();
                if (billedKeys.contains(key)) { skipped++; continue; }
                Material m = tx.getMaterial();
                ChargeCatalog cat = m.getCatalog() != null
                        ? catalogRepository.findById(m.getCatalog().getId()).orElse(null) : null;
                BigDecimal price = cat != null ? cat.getUnitPrice()
                        : (m.getUnitCost() != null ? m.getUnitCost() : BigDecimal.ZERO);
                BillItem item = new BillItem();
                item.setBill(bill);
                item.setSourceType(BillSourceType.MATERIAL);
                item.setSourceId(tx.getId());
                item.setSourceNo(visit.getVisitNo());
                item.setServiceDate(serviceDate);
                item.setItemCode(cat != null ? cat.getCode() : m.getCode());
                item.setItemName(m.getName() + (m.getSpec() != null ? "(" + m.getSpec() + ")" : ""));
                item.setCategory(cat != null ? cat.getCategory()
                        : (Boolean.TRUE.equals(m.getColdChain())
                        ? CatalogCategory.COLDCHAIN_DRUG : CatalogCategory.CONSUMABLE));
                item.setQuantity(tx.getQuantity());
                item.setUnitPrice(price);
                item.setAmount(money(price.multiply(BigDecimal.valueOf(tx.getQuantity()))));
                item.setReimbursable(cat != null && Boolean.TRUE.equals(cat.getReimbursable()));
                item.setInsuranceCategory(cat != null ? cat.getInsuranceCategory() : "丙类");
                item.setReimbursementRate(cat != null ? cat.getReimbursementRate() : null);
                item.setNote("上门领用"
                        + (Boolean.TRUE.equals(m.getColdChain()) && tx.getColdChainTemp() != null
                        ? "；冷链交接 " + tx.getColdChainTemp() + "℃"
                        : Boolean.TRUE.equals(m.getColdChain()) ? "；⚠未登记冷链温度" : "")
                        + (tx.getBatchNo() != null ? "；批号 " + tx.getBatchNo() : ""));
                applySplit(item, app);
                billItemRepository.save(item);
                billedKeys.add(key);
                total = total.add(item.getAmount());
                insuranceTotal = insuranceTotal.add(item.getInsuranceAmount());
                selfTotal = selfTotal.add(item.getSelfPayAmount());
            }
        }

        bill.setTotalAmount(money(total));
        bill.setInsuranceAmount(money(insuranceTotal));
        bill.setSelfPayAmount(money(selfTotal));
        bill = billRepository.save(bill);

        // 自动合规初审
        complianceService.autoReview(bill);

        auditService.log(me, "账单", "生成统一账单", bill.getId(), bill.getBillNo(),
                patient.getId(), "账期 " + periodStart + "~" + periodEnd
                        + "，总额 " + bill.getTotalAmount()
                        + "，医保 " + bill.getInsuranceAmount()
                        + "，自费 " + bill.getSelfPayAmount()
                        + (skipped > 0 ? "；跳过已出账明细 " + skipped + " 行（防重复收费）" : "")
                        + (hospitalizedExcluded > 0 ? "；剔除转诊住院期间巡诊 " + hospitalizedExcluded
                        + " 次（费用截至 " + earliestCutoff + "，住院期间不计费）" : ""));
        return bill;
    }

    private void applySplit(BillItem item, BedApplication app) {
        boolean eligible = Boolean.TRUE.equals(app.getInsuranceEligible())
                && Boolean.TRUE.equals(item.getReimbursable())
                && item.getReimbursementRate() != null
                && app.getPatient().getInsuranceType() != InsuranceType.NONE;
        if (eligible) {
            BigDecimal ins = money(item.getAmount().multiply(item.getReimbursementRate()));
            item.setInsuranceAmount(ins);
            item.setSelfPayAmount(money(item.getAmount().subtract(ins)));
        } else {
            item.setInsuranceAmount(BigDecimal.ZERO);
            item.setSelfPayAmount(item.getAmount());
        }
    }

    private BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional(readOnly = true)
    public List<Bill> list(String status, Long patientId) {
        LoginUser me = SecurityUtils.current();
        if (me.role() == Role.FAMILY) {
            List<Patient> patients = null;
            // 家属只看绑定患者的账单
            return billRepository.findAllByOrderByCreatedAtDesc().stream()
                    .filter(b -> b.getPatient().getFamilyUser() != null
                            && b.getPatient().getFamilyUser().getId().equals(me.id()))
                    .filter(b -> status == null || status.isBlank()
                            || b.getStatus() == BillStatus.valueOf(status))
                    .toList();
        }
        if (patientId != null) return billRepository.findByPatientIdOrderByPeriodEndDesc(patientId);
        if (status != null && !status.isBlank()) {
            return billRepository.findByStatusOrderByCreatedAtDesc(BillStatus.valueOf(status));
        }
        return billRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Bill bill = billRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "账单不存在"));
        LoginUser me = SecurityUtils.current();
        if (me.role() == Role.FAMILY && (bill.getPatient().getFamilyUser() == null
                || !bill.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能查看本人账单");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bill", bill);
        map.put("items", billItemRepository.findByBillId(id));
        map.put("compliance", complianceService.byBill(id));
        return map;
    }

    /** 家属确认账单（对每行费用知情确认） */
    @Transactional
    public Bill familyConfirm(Long billId, String note) {
        LoginUser me = SecurityUtils.current();
        Bill bill = billRepository.findById(billId).orElseThrow(() -> new BusinessException(404, "账单不存在"));
        if (me.role() == Role.FAMILY && (bill.getPatient().getFamilyUser() == null
                || !bill.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能确认本人账单");
        }
        if (bill.getStatus() != BillStatus.DRAFT) {
            throw new BusinessException("仅待确认账单可确认，当前：" + bill.getStatus().label);
        }
        bill.setStatus(BillStatus.CONFIRMED);
        bill.setFamilyConfirmedAt(LocalDateTime.now());
        bill.setConfirmedUser(userRepository.findById(me.id()).orElse(null));
        bill.setFamilyConfirmNote(note);
        bill = billRepository.save(bill);
        auditService.log(me, "账单", "家属确认", bill.getId(), bill.getBillNo(),
                bill.getPatient().getId(), "家属确认意见：" + note);
        return bill;
    }

    /** 家属质疑收费（账单进入争议状态，可逐项核对还原） */
    @Transactional
    public Bill dispute(Long billId, String disputeNote) {
        LoginUser me = SecurityUtils.current();
        Bill bill = billRepository.findById(billId).orElseThrow(() -> new BusinessException(404, "账单不存在"));
        if (me.role() == Role.FAMILY && (bill.getPatient().getFamilyUser() == null
                || !bill.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能质疑本人账单");
        }
        if (bill.getStatus() == BillStatus.SETTLED) {
            throw new BusinessException("已结算账单不可质疑，请走退费流程");
        }
        bill.setStatus(BillStatus.DISPUTED);
        bill.setDisputeNote(disputeNote);
        bill.setDisputedAt(LocalDateTime.now());
        bill = billRepository.save(bill);
        auditService.log(me, "账单", "家属质疑收费", bill.getId(), bill.getBillNo(),
                bill.getPatient().getId(), "质疑内容：" + disputeNote);
        return bill;
    }

    /** 财务处理家属质疑：逐项回溯来源单据后给出答复，账单回到待确认 */
    @Transactional
    public Bill resolveDispute(Long billId, String resolution, boolean adjust) {
        LoginUser me = SecurityUtils.current();
        Bill bill = billRepository.findById(billId).orElseThrow(() -> new BusinessException(404, "账单不存在"));
        if (bill.getStatus() != BillStatus.DISPUTED) {
            throw new BusinessException("仅争议中账单可处理质疑");
        }
        bill.setDisputeResolution(resolution);
        bill.setDisputeHandler(userRepository.findById(me.id()).orElse(null));
        bill.setDisputeResolvedAt(LocalDateTime.now());

        if (adjust) {
            // 家属异议成立：剔除无签字/重复/存疑项后重算
            List<BillItem> items = billItemRepository.findByBillId(bill.getId());
            BigDecimal total = BigDecimal.ZERO, ins = BigDecimal.ZERO, self = BigDecimal.ZERO;
            for (BillItem item : items) {
                if (item.getNote() != null && item.getNote().contains("【异议剔除】")) continue;
                total = total.add(item.getAmount());
                ins = ins.add(item.getInsuranceAmount());
                self = self.add(item.getSelfPayAmount());
            }
            bill.setTotalAmount(money(total));
            bill.setInsuranceAmount(money(ins));
            bill.setSelfPayAmount(money(self));
        }
        bill.setStatus(BillStatus.DRAFT);
        bill = billRepository.save(bill);
        auditService.log(me, "账单", "答复收费质疑", bill.getId(), bill.getBillNo(),
                bill.getPatient().getId(), "是否调整=" + adjust + "；答复：" + resolution);
        return bill;
    }

    /** 标记某明细异议成立并剔除重算 */
    @Transactional
    public Bill removeItem(Long billId, Long itemId, String reason) {
        LoginUser me = SecurityUtils.current();
        BillItem item = billItemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "明细不存在"));
        if (!item.getBill().getId().equals(billId)) throw new BusinessException("明细与账单不匹配");
        item.setNote("【异议剔除】" + reason + "；原备注：" + item.getNote());
        item.setAmount(BigDecimal.ZERO);
        item.setInsuranceAmount(BigDecimal.ZERO);
        item.setSelfPayAmount(BigDecimal.ZERO);
        item.setQuantity(0);
        billItemRepository.save(item);
        auditService.log(me, "账单", "剔除争议明细", itemId, item.getBill().getBillNo(),
                item.getBill().getPatient().getId(), reason);
        return item.getBill();
    }

    /** 医保复核完成并结算 */
    @Transactional
    public Bill settle(Long billId, String insuranceSettleNo) {
        LoginUser me = SecurityUtils.current();
        Bill bill = billRepository.findById(billId).orElseThrow(() -> new BusinessException(404, "账单不存在"));
        if (bill.getStatus() != BillStatus.CONFIRMED && bill.getStatus() != BillStatus.INSURANCE_REVIEWED) {
            throw new BusinessException("仅家属确认后的账单可结算，当前：" + bill.getStatus().label);
        }
        bill.setStatus(BillStatus.SETTLED);
        bill.setInsuranceSettleNo(insuranceSettleNo);
        bill.setSettledAt(LocalDateTime.now());
        bill = billRepository.save(bill);
        auditService.log(me, "账单", "医保结算", bill.getId(), bill.getBillNo(),
                bill.getPatient().getId(), "医保结算批次号：" + insuranceSettleNo);
        return bill;
    }
}
