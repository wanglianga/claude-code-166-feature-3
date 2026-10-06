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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 病情恶化转诊闭环。
 * <pre>
 * 护士巡诊发现呼吸困难等恶化 → 立即标记高危，通知家庭医生/家属/转诊医院
 *   → 医生远程判断：发起救护车转运（家庭病床暂停、未结算项目转待审核、未上门任务取消）
 *     → 等待医院回传入院诊断 或 家属补充住院结果 → 确定家庭病床费用截至时间
 *       → 出院登记 → 医生决定恢复家庭病床（计划重启并补排任务）或终止建床
 * </pre>
 */
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final ReferralRepository referralRepository;
    private final ReferralNotificationRepository notificationRepository;
    private final PatientRepository patientRepository;
    private final VisitRepository visitRepository;
    private final VisitPlanRepository planRepository;
    private final BedApplicationRepository applicationRepository;
    private final BillRepository billRepository;
    private final RiskEventRepository riskEventRepository;
    private final SysUserRepository userRepository;
    private final AuditService auditService;

    // ===== 查询 =====

    @Transactional(readOnly = true)
    public List<Referral> list(String status, Long patientId) {
        LoginUser me = SecurityUtils.current();
        List<Referral> all;
        if (patientId != null) {
            all = referralRepository.findByPatientIdOrderByCreatedAtDesc(patientId);
        } else if (status != null && !status.isBlank()) {
            all = referralRepository.findByStatusOrderByCreatedAtDesc(ReferralStatus.valueOf(status));
        } else {
            all = referralRepository.findAllByOrderByCreatedAtDesc();
        }
        if (me.role() == Role.FAMILY) {
            return all.stream()
                    .filter(r -> r.getPatient().getFamilyUser() != null
                            && r.getPatient().getFamilyUser().getId().equals(me.id()))
                    .toList();
        }
        return all;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Referral r = get(id);
        LoginUser me = SecurityUtils.current();
        if (me.role() == Role.FAMILY) {
            checkFamilyOwns(r, me);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("referral", r);
        map.put("notifications", notificationRepository.findByReferralIdOrderBySentAtAsc(id));
        // 未结算项目提示：待确认/待审核账单数
        long pendingBills = billRepository.findByPatientIdOrderByPeriodEndDesc(r.getPatient().getId())
                .stream().filter(b -> b.getStatus() == BillStatus.DRAFT
                        || b.getStatus() == BillStatus.PENDING_REVIEW).count();
        map.put("pendingBillCount", pendingBills);
        return map;
    }

    private Referral get(Long id) {
        return referralRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "转诊单不存在"));
    }

    private void checkFamilyOwns(Referral r, LoginUser me) {
        if (r.getPatient().getFamilyUser() == null
                || !r.getPatient().getFamilyUser().getId().equals(me.id())) {
            throw new BusinessException(403, "只能查看/操作本人家人的转诊单");
        }
    }

    // ===== 1. 护士/医生标记高危并通知三方 =====

    @Transactional
    public Referral mark(Long visitId, Long patientId, String symptom, String description,
                         String targetHospital, String hospitalPhone, String evidencePath) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅护士或医生可标记病情恶化");
        }
        Visit visit = null;
        Patient patient;
        if (visitId != null) {
            visit = visitRepository.findById(visitId)
                    .orElseThrow(() -> new BusinessException(404, "巡诊任务不存在"));
            patient = visit.getPatient();
        } else if (patientId != null) {
            patient = patientRepository.findById(patientId)
                    .orElseThrow(() -> new BusinessException(404, "患者不存在"));
        } else {
            throw new BusinessException("缺少巡诊任务或患者信息");
        }
        if (symptom == null || symptom.isBlank()) {
            throw new BusinessException("请填写主要症状（如：呼吸困难）");
        }
        // 同一患者存在未闭环转诊时禁止重复发起
        List<Referral> active = referralRepository.findByPatientIdAndStatusIn(patient.getId(),
                List.of(ReferralStatus.MARKED, ReferralStatus.AMBULANCE_DISPATCHED,
                        ReferralStatus.ADMITTED, ReferralStatus.DISCHARGED));
        if (!active.isEmpty()) {
            throw new BusinessException("该患者已存在进行中的转诊闭环（" + active.get(0).getReferralNo()
                    + "，" + active.get(0).getStatus().label + "），请勿重复发起");
        }

        // 执行中的计划与批准中的申请（用于暂停与通知定位）
        VisitPlan activePlan = planRepository.findByPatientIdOrderByStartDateDesc(patient.getId())
                .stream().filter(p -> p.getStatus() == PlanStatus.ACTIVE).findFirst().orElse(null);
        BedApplication approvedApp = applicationRepository
                .findByPatientIdOrderByCreatedAtDesc(patient.getId()).stream()
                .filter(a -> a.getStatus() == BedApplyStatus.APPROVED).findFirst().orElse(null);

        // 联动高危风险事件
        SysUser reporter = userRepository.findById(me.id()).orElse(null);
        RiskEvent risk = new RiskEvent();
        risk.setRiskNo(ApplicationService.generateNo("FX"));
        risk.setPatient(patient);
        risk.setVisit(visit);
        risk.setRiskType("病情恶化");
        risk.setSeverity(RiskSeverity.HIGH);
        risk.setDescription("【高危】" + symptom + "。" + (description == null ? "" : description)
                + "（已启动转诊闭环，等待医生远程判断）");
        risk.setStatus(RiskEventStatus.OPEN);
        risk.setReporter(reporter);
        risk.setReportedAt(LocalDateTime.now());
        risk = riskEventRepository.save(risk);

        Referral r = new Referral();
        r.setReferralNo(ApplicationService.generateNo("ZZ"));
        r.setPatient(patient);
        r.setVisit(visit);
        r.setRiskEvent(risk);
        r.setApplication(approvedApp);
        r.setPlan(activePlan);
        r.setSymptom(symptom);
        r.setDescription(description);
        r.setTargetHospital(targetHospital);
        r.setHospitalPhone(hospitalPhone);
        r.setEvidencePath(evidencePath);
        r.setStatus(ReferralStatus.MARKED);
        r = referralRepository.save(r);

        // 立即通知：家庭医生（站内）、家属（站内）、转诊医院（短信模拟）
        String base = "患者" + patient.getName() + "（" + patient.getAge() + "岁，"
                + (patient.getDiseaseCategory() == null ? "" : patient.getDiseaseCategory().label)
                + "）巡诊中发现【" + symptom + "】，护士/医生已标记高危。";
        SysUser doctor = activePlan != null && activePlan.getDoctor() != null ? activePlan.getDoctor()
                : approvedApp != null ? approvedApp.getDoctor() : null;
        notify(r, NotifyRecipientType.DOCTOR, doctor,
                doctor != null ? doctor.getName() : "值班家庭医生",
                doctor != null ? doctor.getPhone() : null, "PLATFORM",
                base + "请尽快远程判断是否需要救护车转运（转诊单 " + r.getReferralNo() + "）。");
        SysUser family = patient.getFamilyUser();
        notify(r, NotifyRecipientType.FAMILY, family,
                patient.getPrimaryCaregiver() != null ? patient.getPrimaryCaregiver() : "家属",
                patient.getPrimaryContactPhone(), "PLATFORM",
                base + "平台已通知家庭医生远程判断，请保持电话畅通并配合转诊安排。");
        notify(r, NotifyRecipientType.HOSPITAL, null,
                targetHospital != null ? targetHospital : "转诊医院", hospitalPhone, "SMS",
                "【转诊预告】乡镇卫生院家庭病床患者" + patient.getName() + "（" + patient.getAge() + "岁）病情恶化："
                        + symptom + "，可能转诊贵院，请做好接诊准备；入院后请回传入院诊断与入院时间。");
        r.setNotifiedDoctorAt(LocalDateTime.now());
        r.setNotifiedFamilyAt(LocalDateTime.now());
        r.setNotifiedHospitalAt(LocalDateTime.now());
        r = referralRepository.save(r);

        auditService.log(me, "转诊闭环", "标记高危并通知三方", r.getId(), r.getReferralNo(),
                patient.getId(), "症状=" + symptom + "；已通知家庭医生/家属/转诊医院["
                        + (targetHospital == null ? "-" : targetHospital) + "]；联动风险事件 " + risk.getRiskNo());
        return r;
    }

    private ReferralNotification notify(Referral r, NotifyRecipientType type, SysUser recipientUser,
                                        String recipientName, String phone, String channel, String content) {
        ReferralNotification n = new ReferralNotification();
        n.setReferral(r);
        n.setRecipientType(type);
        n.setRecipientUser(recipientUser);
        n.setRecipientName(recipientName);
        n.setRecipientPhone(phone);
        n.setChannel(channel);
        n.setContent(content);
        n.setSentAt(LocalDateTime.now());
        return notificationRepository.save(n);
    }

    // ===== 2. 医生远程判断：救护车转运 / 继续观察 =====

    @Transactional
    public Referral decide(Long id, ReferralDecision decision, String note, String ambulanceNote) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家庭医生可远程判断转诊");
        }
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.MARKED) {
            throw new BusinessException("当前状态[" + r.getStatus().label + "]不可远程判断，仅待判断状态可操作");
        }
        SysUser doctor = userRepository.findById(me.id()).orElse(null);
        r.setDecisionDoctor(doctor);
        r.setDecision(decision);
        r.setDecisionNote(note);
        r.setDecidedAt(LocalDateTime.now());

        if (decision == ReferralDecision.OBSERVE) {
            r.setStatus(ReferralStatus.CANCELLED);
            r = referralRepository.save(r);
            notify(r, NotifyRecipientType.FAMILY, r.getPatient().getFamilyUser(),
                    r.getPatient().getPrimaryCaregiver(), r.getPatient().getPrimaryContactPhone(),
                    "PLATFORM", "患者" + r.getPatient().getName() + "经医生远程判断暂不需转诊，继续居家观察，"
                            + "家庭病床服务照常。" + (note == null ? "" : "医生意见：" + note));
            auditService.log(me, "转诊闭环", "远程判断·继续观察", r.getId(), r.getReferralNo(),
                    r.getPatient().getId(), note);
            return r;
        }

        // 发起救护车转运
        r.setStatus(ReferralStatus.AMBULANCE_DISPATCHED);
        r.setAmbulanceNote(ambulanceNote);
        r.setAmbulanceDispatchedAt(LocalDateTime.now());
        Patient patient = r.getPatient();

        // ① 家庭病床服务暂停：执行中计划暂停、建床申请挂起
        List<VisitPlan> plans = planRepository.findByPatientIdOrderByStartDateDesc(patient.getId());
        int pausedPlans = 0;
        for (VisitPlan p : plans) {
            if (p.getStatus() == PlanStatus.ACTIVE) {
                p.setStatus(PlanStatus.PAUSED);
                planRepository.save(p);
                pausedPlans++;
            }
        }
        int suspendedApps = 0;
        for (BedApplication a : applicationRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId())) {
            if (a.getStatus() == BedApplyStatus.APPROVED) {
                a.setStatus(BedApplyStatus.SUSPENDED);
                applicationRepository.save(a);
                suspendedApps++;
            }
        }

        // ② 未上门任务取消，避免患者住院期间巡诊计划继续执行/计费
        int cancelledVisits = 0;
        for (Visit v : visitRepository.findByPatientIdOrderByScheduledAtDesc(patient.getId())) {
            if (v.getStatus() == VisitStatus.SCHEDULED) {
                v.setStatus(VisitStatus.CANCELLED);
                v.setArrangementNote((v.getArrangementNote() == null ? "" : v.getArrangementNote() + "；")
                        + "病情恶化转诊[" + r.getReferralNo() + "]，家庭病床暂停，任务取消");
                visitRepository.save(v);
                cancelledVisits++;
            }
        }

        // ③ 未结算项目进入待审核
        int pendingBills = 0;
        for (Bill b : billRepository.findByPatientIdOrderByPeriodEndDesc(patient.getId())) {
            if (b.getStatus() == BillStatus.DRAFT) {
                b.setStatus(BillStatus.PENDING_REVIEW);
                billRepository.save(b);
                pendingBills++;
            }
        }
        r = referralRepository.save(r);

        // ④ 转运通知：家属 + 转诊医院
        notify(r, NotifyRecipientType.FAMILY, patient.getFamilyUser(),
                patient.getPrimaryCaregiver(), patient.getPrimaryContactPhone(), "PLATFORM",
                "患者" + patient.getName() + "经医生远程判断需立即转诊，救护车已派出"
                        + (ambulanceNote == null ? "" : "（" + ambulanceNote + "）")
                        + "，家庭病床服务已暂停，未结算费用转入待审核，待入院结果确定后结算。");
        notify(r, NotifyRecipientType.HOSPITAL, null,
                r.getTargetHospital() != null ? r.getTargetHospital() : "转诊医院",
                r.getHospitalPhone(), "SMS",
                "【转诊转运】家庭病床患者" + patient.getName() + "（" + patient.getAge() + "岁，"
                        + r.getSymptom() + "）救护车转运中，请做好接诊准备；入院后请回传入院诊断与入院时间。");

        auditService.log(me, "转诊闭环", "远程判断·发起救护车转运", r.getId(), r.getReferralNo(),
                patient.getId(), "暂停计划 " + pausedPlans + " 个、挂起建床申请 " + suspendedApps
                        + " 个、取消未上门任务 " + cancelledVisits + " 次、待审核账单 " + pendingBills
                        + " 张；转运说明：" + ambulanceNote + "；医生意见：" + note);
        return r;
    }

    // ===== 3. 医院回传入院诊断 / 家属补充住院结果 → 确定费用截至时间 =====

    @Transactional
    public Referral hospitalFeedback(Long id, String admissionDiagnosis, LocalDateTime admittedAt) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.ADMIN && me.role() != Role.FAMILY_DOCTOR) {
            throw new BusinessException(403, "仅管理者或医生可代登记医院回传");
        }
        Referral r = get(id);
        return applyAdmission(me, r, FeedbackSource.HOSPITAL, admissionDiagnosis, admittedAt, null);
    }

    @Transactional
    public Referral familyFeedback(Long id, String admissionDiagnosis, LocalDateTime admittedAt, String note) {
        LoginUser me = SecurityUtils.current();
        Referral r = get(id);
        if (me.role() == Role.FAMILY) {
            checkFamilyOwns(r, me);
        } else if (me.role() != Role.ADMIN && me.role() != Role.FAMILY_DOCTOR) {
            throw new BusinessException(403, "仅家属可补充住院结果");
        }
        return applyAdmission(me, r, FeedbackSource.FAMILY, admissionDiagnosis, admittedAt, note);
    }

    private Referral applyAdmission(LoginUser me, Referral r, FeedbackSource source,
                                    String admissionDiagnosis, LocalDateTime admittedAt, String note) {
        if (r.getStatus() != ReferralStatus.AMBULANCE_DISPATCHED) {
            throw new BusinessException("当前状态[" + r.getStatus().label + "]不可登记入院结果，"
                    + "仅救护车转运中可回传/补充");
        }
        if (admittedAt == null) {
            throw new BusinessException("必须填写入院时间，用于确定家庭病床费用截至时间");
        }
        if (admissionDiagnosis == null || admissionDiagnosis.isBlank()) {
            throw new BusinessException("必须填写入院诊断");
        }
        r.setFeedbackSource(source);
        r.setFeedbackBy(userRepository.findById(me.id()).orElse(null));
        r.setFeedbackAt(LocalDateTime.now());
        r.setAdmissionDiagnosis(admissionDiagnosis);
        r.setAdmittedAt(admittedAt);
        // 费用截至时间 = 入院时间：此后不再计家庭病床费用
        r.setFeeCutoffAt(admittedAt);
        r.setFeeCutoffNote(source.label + "确认入院时间，家庭病床费用截至该时点；住院期间巡诊不计费"
                + (note != null && !note.isBlank() ? "；补充说明：" + note : ""));
        r.setStatus(ReferralStatus.ADMITTED);
        r = referralRepository.save(r);

        // 费用截至已确定：待审核账单退回待确认，可按截至时间重新审核结算
        int released = 0;
        for (Bill b : billRepository.findByPatientIdOrderByPeriodEndDesc(r.getPatient().getId())) {
            if (b.getStatus() == BillStatus.PENDING_REVIEW) {
                b.setStatus(BillStatus.DRAFT);
                billRepository.save(b);
                released++;
            }
        }
        auditService.log(me, "转诊闭环", "确认入院·确定费用截至", r.getId(), r.getReferralNo(),
                r.getPatient().getId(), "来源=" + source.label + "；入院诊断=" + admissionDiagnosis
                        + "；入院时间=" + admittedAt + "；费用截至该时点；" + released + " 张待审核账单退回待确认");
        return r;
    }

    // ===== 4. 出院登记 =====

    @Transactional
    public Referral discharge(Long id, LocalDateTime dischargedAt, String note) {
        LoginUser me = SecurityUtils.current();
        Referral r = get(id);
        if (me.role() == Role.FAMILY) {
            checkFamilyOwns(r, me);
        } else if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅医生/管理者/家属可登记出院");
        }
        if (r.getStatus() != ReferralStatus.ADMITTED) {
            throw new BusinessException("当前状态[" + r.getStatus().label + "]不可登记出院，仅已入院状态可操作");
        }
        LocalDateTime time = dischargedAt != null ? dischargedAt : LocalDateTime.now();
        if (r.getAdmittedAt() != null && time.isBefore(r.getAdmittedAt())) {
            throw new BusinessException("出院时间不能早于入院时间 " + r.getAdmittedAt());
        }
        r.setDischargedAt(time);
        r.setDischargeNote(note);
        r.setStatus(ReferralStatus.DISCHARGED);
        r = referralRepository.save(r);
        notify(r, NotifyRecipientType.DOCTOR,
                r.getPlan() != null ? r.getPlan().getDoctor() : null,
                "家庭医生", null, "PLATFORM",
                "患者" + r.getPatient().getName() + "已出院（" + time + "），请评估是否恢复家庭病床服务。");
        auditService.log(me, "转诊闭环", "出院登记", r.getId(), r.getReferralNo(),
                r.getPatient().getId(), "出院时间=" + time + "；" + note);
        return r;
    }

    // ===== 5. 出院后医生决定：恢复家庭病床 / 终止建床 =====

    @Transactional
    public Referral resumeDecision(Long id, ResumeDecision decision, String note) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家庭医生可决定恢复或终止家庭病床");
        }
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.DISCHARGED) {
            throw new BusinessException("当前状态[" + r.getStatus().label + "]不可做恢复决定，仅已出院状态可操作");
        }
        SysUser doctor = userRepository.findById(me.id()).orElse(null);
        r.setResumeDoctor(doctor);
        r.setResumeDecision(decision);
        r.setResumeDecidedAt(LocalDateTime.now());
        r.setResumeNote(note);
        Patient patient = r.getPatient();

        if (decision == ResumeDecision.RESUME) {
            int resumedPlans = 0;
            int generated = 0;
            for (VisitPlan p : planRepository.findByPatientIdOrderByStartDateDesc(patient.getId())) {
                if (p.getStatus() != PlanStatus.PAUSED) continue;
                p.setStatus(PlanStatus.ACTIVE);
                planRepository.save(p);
                resumedPlans++;
                generated += regenerateVisits(p, patient);
            }
            int resumedApps = 0;
            for (BedApplication a : applicationRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId())) {
                if (a.getStatus() == BedApplyStatus.SUSPENDED) {
                    a.setStatus(BedApplyStatus.APPROVED);
                    applicationRepository.save(a);
                    resumedApps++;
                }
            }
            r.setStatus(ReferralStatus.RESUMED);
            r = referralRepository.save(r);
            notify(r, NotifyRecipientType.FAMILY, patient.getFamilyUser(),
                    patient.getPrimaryCaregiver(), patient.getPrimaryContactPhone(), "PLATFORM",
                    "患者" + patient.getName() + "出院后经医生评估，家庭病床服务已恢复，"
                            + "新排上门任务 " + generated + " 次。" + (note == null ? "" : "医生意见：" + note));
            auditService.log(me, "转诊闭环", "恢复家庭病床", r.getId(), r.getReferralNo(),
                    patient.getId(), "恢复计划 " + resumedPlans + " 个、恢复建床申请 " + resumedApps
                            + " 个、补排巡诊 " + generated + " 次；医生意见：" + note);
        } else {
            int cancelledPlans = 0;
            for (VisitPlan p : planRepository.findByPatientIdOrderByStartDateDesc(patient.getId())) {
                if (p.getStatus() == PlanStatus.PAUSED) {
                    p.setStatus(PlanStatus.CANCELLED);
                    planRepository.save(p);
                    cancelledPlans++;
                }
            }
            int terminatedApps = 0;
            for (BedApplication a : applicationRepository.findByPatientIdOrderByCreatedAtDesc(patient.getId())) {
                if (a.getStatus() == BedApplyStatus.SUSPENDED) {
                    a.setStatus(BedApplyStatus.TERMINATED);
                    applicationRepository.save(a);
                    terminatedApps++;
                }
            }
            r.setStatus(ReferralStatus.CLOSED);
            r = referralRepository.save(r);
            notify(r, NotifyRecipientType.FAMILY, patient.getFamilyUser(),
                    patient.getPrimaryCaregiver(), patient.getPrimaryContactPhone(), "PLATFORM",
                    "患者" + patient.getName() + "出院后经医生评估，家庭病床服务终止"
                            + "（费用截至入院时间 " + r.getFeeCutoffAt() + "）。"
                            + (note == null ? "" : "医生意见：" + note));
            auditService.log(me, "转诊闭环", "终止家庭病床", r.getId(), r.getReferralNo(),
                    patient.getId(), "取消计划 " + cancelledPlans + " 个、终止建床申请 " + terminatedApps
                            + " 个；医生意见：" + note);
        }
        return r;
    }

    /** 恢复服务时，按计划剩余周期补排巡诊任务（与排班服务同一时段模式） */
    private int regenerateVisits(VisitPlan plan, Patient patient) {
        LocalDate today = LocalDate.now();
        LocalDate end = plan.getExpectedEndDate();
        if (end == null || end.isBefore(today)) return 0;
        int timesPerWeek = plan.getTimesPerWeek() == null ? 2 : Math.min(plan.getTimesPerWeek(), 5);
        int[] slots = switch (timesPerWeek) {
            case 1 -> new int[]{0};
            case 2 -> new int[]{0, 3};
            case 3 -> new int[]{0, 2, 4};
            case 4 -> new int[]{0, 1, 3, 4};
            default -> new int[]{0, 1, 2, 3, 4};
        };
        LocalTime[] dayTimes = {LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0),
                LocalTime.of(14, 30), LocalTime.of(15, 30), LocalTime.of(16, 30)};
        int generated = 0;
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        while (!monday.isAfter(end)) {
            int daySlot = 0;
            for (int offset : slots) {
                LocalDate date = monday.plusDays(offset);
                if (date.isBefore(today) || date.isAfter(end)) continue;
                Visit v = new Visit();
                v.setVisitNo(ApplicationService.generateNo("XZ"));
                v.setPlan(plan);
                v.setPatient(patient);
                v.setNurse(plan.getNurse());
                v.setScheduledAt(LocalDateTime.of(date, dayTimes[Math.min(daySlot, dayTimes.length - 1)]));
                daySlot++;
                v.setStatus(VisitStatus.SCHEDULED);
                v.setEstimatedTravelMinutes(plan.getApplication() != null
                        ? plan.getApplication().getTravelMinutes() : null);
                v.setArrangementNote("转诊出院后恢复家庭病床，补排巡诊");
                visitRepository.save(v);
                generated++;
            }
            monday = monday.plusWeeks(1);
        }
        return generated;
    }

    // ===== 通知中心 =====

    @Transactional(readOnly = true)
    public List<ReferralNotification> myNotifications() {
        LoginUser me = SecurityUtils.current();
        return notificationRepository.findByRecipientUserIdOrderBySentAtDesc(me.id());
    }

    @Transactional
    public ReferralNotification readNotification(Long id) {
        LoginUser me = SecurityUtils.current();
        ReferralNotification n = notificationRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "通知不存在"));
        if (n.getRecipientUser() == null || !n.getRecipientUser().getId().equals(me.id())) {
            throw new BusinessException(403, "只能已读本人的通知");
        }
        if (n.getReadAt() == null) {
            n.setReadAt(LocalDateTime.now());
            n = notificationRepository.save(n);
        }
        return n;
    }
}
