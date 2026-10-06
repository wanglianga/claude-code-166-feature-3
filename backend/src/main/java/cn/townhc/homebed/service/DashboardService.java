package cn.townhc.homebed.service;

import cn.townhc.homebed.domain.entity.*;
import cn.townhc.homebed.domain.enums.DomainEnums.*;
import cn.townhc.homebed.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 管理者看板：巡诊负荷、上门半径、耗材消耗、风险事件、异常类型分布、
 * 护士负荷排行、医保/自费金额趋势等。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final VisitRepository visitRepository;
    private final VisitExceptionRepository exceptionRepository;
    private final RiskEventRepository riskRepository;
    private final MaterialRepository materialRepository;
    private final MaterialTransactionRepository materialTxRepository;
    private final BedApplicationRepository applicationRepository;
    private final BillRepository billRepository;
    private final SysUserRepository userRepository;
    private final PatientRepository patientRepository;
    private final ReferralRepository referralRepository;

    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();

        List<Visit> allVisits = visitRepository.findAll();
        List<SysUser> nurses = userRepository.findByRoleAndActiveTrue(Role.NURSE);

        // 核心 KPI
        data.put("kpi", Map.of(
                "pendingApplications", applicationRepository.countByStatus(BedApplyStatus.SUBMITTED),
                "scheduledVisits", visitRepository.countByStatus(VisitStatus.SCHEDULED),
                "completedVisits", visitRepository.countByStatus(VisitStatus.COMPLETED),
                "exceptionVisits", visitRepository.countByStatus(VisitStatus.EXCEPTION),
                "openRiskEvents", riskRepository.countByStatus(RiskEventStatus.OPEN),
                "inHospitalReferrals", referralRepository.countByStatusIn(List.of(
                        ReferralStatus.AMBULANCE_DISPATCHED, ReferralStatus.REFERRED,
                        ReferralStatus.DIAGNOSIS_RECEIVED, ReferralStatus.DISCHARGED)),
                "activeBeds", applicationRepository.countByStatus(BedApplyStatus.APPROVED),
                "nursesOnDuty", nurses.size()
        ));

        // 护士巡诊负荷（近 7 天 + 在管计划）
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<Map<String, Object>> nurseLoad = new ArrayList<>();
        for (SysUser n : nurses) {
            List<Visit> mine = allVisits.stream()
                    .filter(v -> v.getNurse() != null && v.getNurse().getId().equals(n.getId())).toList();
            long weekCount = mine.stream().filter(v -> v.getScheduledAt() != null
                    && v.getScheduledAt().isAfter(weekAgo)).count();
            long completed = mine.stream().filter(v -> v.getStatus() == VisitStatus.COMPLETED).count();
            long overtime = mine.stream()
                    .filter(v -> v.getActualTravelMinutes() != null && v.getActualTravelMinutes() > 45).count();
            nurseLoad.add(new LinkedHashMap<>(Map.of(
                    "nurseName", n.getName(),
                    "title", n.getTitle() == null ? "" : n.getTitle(),
                    "weekVisitCount", weekCount,
                    "totalVisitCount", (long) mine.size(),
                    "completedCount", completed,
                    "overtimeCount", overtime,
                    "skills", n.getSkills() == null ? "" : n.getSkills()
            )));
        }
        data.put("nurseLoad", nurseLoad);

        // 上门半径分布（按在管/已批患者）
        List<Patient> patients = patientRepository.findAll();
        int[] radiusBuckets = new int[4]; // <1km, 1-3km, 3-5km, >5km
        List<Map<String, Object>> farPatients = new ArrayList<>();
        for (Patient p : patients) {
            // 取该患者最近一次批准申请的距离
            Integer dist = applicationRepository.findByPatientIdOrderByCreatedAtDesc(p.getId()).stream()
                    .filter(a -> a.getDistanceMeters() != null)
                    .findFirst().map(BedApplication::getDistanceMeters).orElse(null);
            if (dist == null) continue;
            if (dist < 1000) radiusBuckets[0]++;
            else if (dist < 3000) radiusBuckets[1]++;
            else if (dist < 5000) radiusBuckets[2]++;
            else {
                radiusBuckets[3]++;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("patientName", p.getName());
                m.put("address", p.getAddress());
                m.put("distanceMeters", dist);
                farPatients.add(m);
            }
        }
        data.put("radiusBuckets", Map.of(
                "lt1km", radiusBuckets[0], "km1to3", radiusBuckets[1],
                "km3to5", radiusBuckets[2], "gt5km", radiusBuckets[3]));
        data.put("farPatients", farPatients);

        // 异常类型分布
        Map<String, Long> exceptionDist = new LinkedHashMap<>();
        for (ExceptionType t : ExceptionType.values()) exceptionDist.put(t.label, 0L);
        exceptionRepository.findAll().forEach(e ->
                exceptionDist.merge(e.getType().label, 1L, Long::sum));
        data.put("exceptionDistribution", exceptionDist);

        // 风险事件分布与待处理列表
        Map<String, Long> riskDist = new LinkedHashMap<>();
        for (RiskSeverity s : RiskSeverity.values()) riskDist.put(s.label, 0L);
        riskRepository.findAll().forEach(r -> riskDist.merge(r.getSeverity().label, 1L, Long::sum));
        data.put("riskDistribution", riskDist);
        data.put("openRisks", riskRepository.findByStatusOrderByReportedAtDesc(RiskEventStatus.OPEN));

        // 耗材消耗 Top 与缺货/低库存预警
        Map<Long, Integer> consume = new HashMap<>();
        for (MaterialTransaction tx : materialTxRepository.findAll()) {
            if (tx.getType() == MaterialTxType.OUT) {
                consume.merge(tx.getMaterial().getId(), tx.getQuantity() == null ? 0 : tx.getQuantity(), Integer::sum);
            }
        }
        List<Map<String, Object>> materialConsume = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : consume.entrySet()) {
            materialRepository.findById(e.getKey()).ifPresent(m -> {
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("name", m.getName());
                mm.put("unit", m.getUnit());
                mm.put("usedQty", e.getValue());
                mm.put("stockQty", m.getStockQty());
                mm.put("coldChain", Boolean.TRUE.equals(m.getColdChain()));
                materialConsume.add(mm);
            });
        }
        materialConsume.sort((a, b) -> Integer.compare((int) b.get("usedQty"), (int) a.get("usedQty")));
        data.put("materialConsumeTop", materialConsume.stream().limit(10).toList());
        List<Map<String, Object>> shortage = new ArrayList<>();
        for (Material m : materialRepository.findByActiveTrueOrderByCodeAsc()) {
            if (m.getStockQty() != null && m.getSafetyStock() != null
                    && m.getStockQty() <= m.getSafetyStock()) {
                shortage.add(Map.of(
                        "name", m.getName(), "stockQty", m.getStockQty(),
                        "safetyStock", m.getSafetyStock(),
                        "coldChain", Boolean.TRUE.equals(m.getColdChain()),
                        "shortage", m.getStockQty() == 0));
            }
        }
        data.put("materialShortage", shortage);

        // 金额概览（近 30 天账单）
        LocalDate since = LocalDate.now().minusDays(30);
        BigDecimalWrap total = new BigDecimalWrap();
        billRepository.findAll().stream()
                .filter(b -> b.getCreatedAt() != null && b.getCreatedAt().toLocalDate().isAfter(since))
                .forEach(b -> {
                    total.total.add(b.getTotalAmount());
                    total.insurance.add(b.getInsuranceAmount());
                    total.self.add(b.getSelfPayAmount());
                });
        Map<String, Object> amount = new LinkedHashMap<>();
        amount.put("total", total.total);
        amount.put("insurance", total.insurance);
        amount.put("self", total.self);
        data.put("amount30d", amount);

        return data;
    }

    private static class BigDecimalWrap {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        java.math.BigDecimal insurance = java.math.BigDecimal.ZERO;
        java.math.BigDecimal self = java.math.BigDecimal.ZERO;
    }
}
