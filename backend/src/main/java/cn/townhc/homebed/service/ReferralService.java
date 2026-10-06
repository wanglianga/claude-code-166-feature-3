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
 * 病情恶化转诊闭环。
 *
 * 闭环阶段：
 * 1) 护士巡诊发现呼吸困难等高危表现 → 立即标记高危并通知家庭医生/家属/转诊医院；
 * 2) 家庭医生远程判断 → 发起救护车转运，同时暂停家庭病床与巡诊计划、
 *    取消后续排班，未结算账单进入“转诊待审核”冻结；
 * 3) 患者送达医院，转诊完成，等待医院回传或家属补充入院诊断；
 * 4) 以救护车出发时间与实际入院时间较早者确定家庭病床费用截至时间，
 *    截至时间后的护理/耗材一律不计费，避免住院后巡诊计划继续计费；
 * 5) 出院后由家庭医生决定恢复家庭病床（计划恢复、账单解冻）或撤床闭环。
 */
@Service
@RequiredArgsConstructor
public class ReferralService {

    private final ReferralRepository referralRepository;
    private final RiskEventRepository riskRepository;
    private final PatientRepository patientRepository;
    private final BedApplicationRepository applicationRepository;
    private final VisitPlanRepository planRepository;
    private final VisitRepository visitRepository;
    private final VisitExceptionRepository exceptionRepository;
    private final BillRepository billRepository;
    private final BillItemRepository billItemRepository;
    private final SysUserRepository userRepository;
    private final NotifyService notifyService;
    private final AuditService auditService;

    /** 费用截止确定前、仍在住院转诊中的状态 */
    private static final List<ReferralStatus> IN_HOSPITAL = List.of(
            ReferralStatus.AMBULANCE_DISPATCHED, ReferralStatus.REFERRED);
    private static final List<ReferralStatus> OPEN_STATUSES = List.of(
            ReferralStatus.CRITICAL_FLAGGED, ReferralStatus.AMBULANCE_DISPATCHED,
            ReferralStatus.REFERRED, ReferralStatus.DIAGNOSIS_RECEIVED,
            ReferralStatus.DISCHARGED);

    @Transactional(readOnly = true)
    public List<Referral> list(String status, Long patientId, Boolean activeOnly) {
        LoginUser me = SecurityUtils.current();
        if (patientId != null) {
            List<Referral> byPatient = referralRepository.findByPatientIdOrderByFlaggedAtDesc(patientId);
            return me.role() == Role.FAMILY ? filterFamily(me, byPatient) : byPatient;
        }
        List<Referral> base;
        if (status != null && !status.isBlank()) {
            base = referralRepository.findByStatusOrderByFlaggedAtDesc(ReferralStatus.valueOf(status));
        } else {
            base = referralRepository.findAllByOrderByFlaggedAtDesc();
        }
        if (me.role() == Role.FAMILY) base = filterFamily(me, base);
        if (Boolean.TRUE.equals(activeOnly)) {
            return base.stream().filter(r -> OPEN_STATUSES.contains(r.getStatus())).toList();
        }
        return base;
    }

    private List<Referral> filterFamily(LoginUser me, List<Referral> list) {
        return list.stream()
                .filter(r -> r.getPatient().getFamilyUser() != null
                        && r.getPatient().getFamilyUser().getId().equals(me.id()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        Referral r = referralRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "转诊单不存在"));
        LoginUser me = SecurityUtils.current();
        if (me.role() == Role.FAMILY && (r.getPatient().getFamilyUser() == null
                || !r.getPatient().getFamilyUser().getId().equals(me.id()))) {
            throw new BusinessException(403, "只能查看本人绑定患者的转诊单");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("referral", r);
        map.put("notifications", notifyService.byBizNo(r.getReferralNo()));
        map.put("frozenBills", billRepository.findAll().stream()
                .filter(b -> r.equals(b.getReferral()))
                .sorted(Comparator.comparing(Bill::getCreatedAt).reversed())
                .toList());
        return map;
    }

    public record FlagCommand(Long patientId, Long visitId, Long riskEventId, Long applicationId,
                              String symptom, String description, String targetHospital,
                              String targetHospitalContact, String evidencePath) {}

    /** 阶段1：护士/医生立即标记高危并三方通知 */
    @Transactional
    public Referral flagCritical(FlagCommand cmd) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.NURSE && me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅护士/家庭医生可标记高危转诊");
        }
        Patient patient = patientRepository.findById(cmd.patientId())
                .orElseThrow(() -> new BusinessException(404, "患者不存在"));

        // 同一患者已有未闭环转诊单时拦截，避免重复发起
        referralRepository.findFirstByPatientIdAndStatusInOrderByFlaggedAtDesc(patient.getId(), OPEN_STATUSES)
                .ifPresent(existing -> {
                    throw new BusinessException("该患者已有进行中的转诊单 " + existing.getReferralNo()
                            + "（" + existing.getStatus().label + "），请勿重复标记");
                });

        // 找到在管（已批准）建床申请与执行中计划
        BedApplication app = cmd.applicationId() == null ? activeApplication(patient.getId())
                : applicationRepository.findById(cmd.applicationId()).orElse(null);
        Visit visit = cmd.visitId() == null ? null
                : visitRepository.findById(cmd.visitId()).orElse(null);
        VisitPlan plan = visit != null ? visit.getPlan()
                : planRepository.findByPatientIdAndStatus(patient.getId(), PlanStatus.ACTIVE)
                    .stream().findFirst().orElse(null);
        if (app == null && plan != null) app = plan.getApplication();

        // 关联或新建高危风险事件
        RiskEvent risk;
        if (cmd.riskEventId() != null) {
            risk = riskRepository.findById(cmd.riskEventId())
                    .orElseThrow(() -> new BusinessException(404, "风险事件不存在"));
            risk.setSeverity(RiskSeverity.HIGH);
        } else {
            risk = new RiskEvent();
            risk.setRiskNo(ApplicationService.generateNo("FX"));
            risk.setPatient(patient);
            risk.setVisit(visit);
            risk.setRiskType("病情恶化·呼吸困难");
            risk.setSeverity(RiskSeverity.HIGH);
            risk.setDescription("巡诊发现高危表现：" + cmd.symptom()
                    + (cmd.description() == null ? "" : "。" + cmd.description()));
            risk.setEvidencePath(cmd.evidencePath());
            risk.setStatus(RiskEventStatus.OPEN);
            risk.setReporter(userRepository.findById(me.id()).orElse(null));
            risk.setReportedAt(LocalDateTime.now());
        }
        if (risk.getHandlePlan() == null) {
            risk.setHandlePlan("立即标记高危，通知家庭医生/家属/转诊医院，等待医生远程判断是否救护车转运");
        }
        risk = riskRepository.save(risk);

        Referral r = new Referral();
        r.setReferralNo(ApplicationService.generateNo("ZZ"));
        r.setPatient(patient);
        r.setApplication(app);
        r.setPlan(plan);
        r.setRiskEvent(risk);
        r.setVisit(visit);
        r.setStatus(ReferralStatus.CRITICAL_FLAGGED);
        r.setSymptom(cmd.symptom());
        r.setDescription(cmd.description());
        r.setTargetHospital(cmd.targetHospital());
        r.setTargetHospitalContact(cmd.targetHospitalContact());
        r.setFlaggedBy(userRepository.findById(me.id()).orElse(null));
        r.setFlaggedAt(LocalDateTime.now());
        r = referralRepository.save(r);

        // 若在巡诊现场，登记病情恶化异常并挂起任务
        if (visit != null && visit.getStatus() != VisitStatus.COMPLETED
                && visit.getStatus() != VisitStatus.CANCELLED) {
            VisitException ex = new VisitException();
            ex.setVisit(visit);
            ex.setPatient(patient);
            ex.setType(ExceptionType.CONDITION_WORSENED);
            ex.setDescription("病情恶化高危：" + cmd.symptom() + "；转诊单 " + r.getReferralNo());
            ex.setEvidencePath(cmd.evidencePath());
            ex.setStatus(ExceptionStatus.REPORTED);
            exceptionRepository.save(ex);
            visit.setStatus(VisitStatus.EXCEPTION);
            visit.setArrangementNote("病情恶化高危，转诊单 " + r.getReferralNo() + "，任务挂起");
            visitRepository.save(visit);
        }

        // 三方通知：家庭医生、家属、转诊医院
        SysUser doctor = plan != null && plan.getDoctor() != null ? plan.getDoctor()
                : app != null ? app.getDoctor() : null;
        SysUser familyUser = patient.getFamilyUser();
        String hospital = cmd.targetHospital() != null && !cmd.targetHospital().isBlank()
                ? cmd.targetHospital() : "县人民医院急诊科";
        String subject = "【高危】患者 " + patient.getName() + " 病情恶化需立即处置";
        String content = "患者 " + patient.getName() + "（" + patient.getGender() + "/" + patient.getAge()
                + "岁）巡诊发现：" + cmd.symptom()
                + "。已立即标记高危，转诊单 " + r.getReferralNo()
                + "，请尽快登录平台查看体征/现场记录并进行远程判断，必要时发起救护车转运。";
        List<NotifyService.SentNotify> sent = notifyService.notifyCritical(
                doctor == null ? null : doctor.getId(),
                doctor == null ? "家庭医生" : doctor.getName(),
                familyUser == null ? null : familyUser.getId(),
                patient.getPrimaryCaregiver(), patient.getPrimaryContactPhone(),
                hospital, cmd.targetHospitalContact(),
                subject, content, r.getReferralNo(), patient.getId());

        LocalDateTime now = LocalDateTime.now();
        r.setDoctorNotifiedAt(now);
        r.setFamilyNotifiedAt(now);
        r.setHospitalNotifiedAt(now);
        r.setNotifySnapshot(sent.stream()
                .map(s -> s.receiverName() + "·" + s.channel() + "·" + s.status())
                .reduce((a, b) -> a + "；" + b).orElse("无"));
        r = referralRepository.save(r);

        auditService.log(me, "转诊闭环", "标记高危并三方通知", r.getId(), r.getReferralNo(),
                patient.getId(), "主诉=" + cmd.symptom() + "；通知：" + r.getNotifySnapshot());
        return r;
    }

    /** 阶段2：家庭医生远程判断，发起救护车转运 */
    @Transactional
    public Referral dispatchAmbulance(Long id, String targetHospital, String judgeNote,
                                      String ambulanceNote) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家庭医生可远程判断并发起救护车");
        }
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.CRITICAL_FLAGGED) {
            throw new BusinessException("当前状态[" + r.getStatus().label + "]不可发起救护车");
        }
        Patient patient = r.getPatient();
        LocalDateTime now = LocalDateTime.now();

        r.setJudgeDoctor(userRepository.findById(me.id()).orElse(null));
        r.setJudgedAt(now);
        r.setJudgeNote(judgeNote);
        if (targetHospital != null && !targetHospital.isBlank()) r.setTargetHospital(targetHospital);
        r.setAmbulanceDispatchedAt(now);
        r.setAmbulanceNote(ambulanceNote);
        r.setAdmittedHospital(r.getTargetHospital());
        r.setStatus(ReferralStatus.AMBULANCE_DISPATCHED);

        // 1) 暂停家庭病床：申请单 → 暂停建床
        BedApplication app = r.getApplication();
        if (app != null && app.getStatus() == BedApplyStatus.APPROVED) {
            app.setStatus(BedApplyStatus.SUSPENDED);
            applicationRepository.save(app);
        }
        // 2) 暂停执行中巡诊计划
        if (r.getPlan() != null && r.getPlan().getStatus() == PlanStatus.ACTIVE) {
            r.getPlan().setStatus(PlanStatus.PAUSED);
            r.getPlan().setScheduleExplanation(
                    (r.getPlan().getScheduleExplanation() == null ? ""
                            : r.getPlan().getScheduleExplanation() + "\n")
                            + "【转诊暂停】" + now + " 患者病情恶化转诊，计划暂停，出院后由医生决定是否恢复");
            planRepository.save(r.getPlan());
        }
        // 同患者其它执行中计划一并暂停
        for (VisitPlan p : planRepository.findByPatientIdAndStatus(patient.getId(), PlanStatus.ACTIVE)) {
            if (r.getPlan() != null && p.getId().equals(r.getPlan().getId())) continue;
            p.setStatus(PlanStatus.PAUSED);
            planRepository.save(p);
        }
        // 3) 取消后续待上门排班，杜绝住院后仍按计划上门/计费（上门中的任务保留）
        int cancelled = 0;
        for (Visit v : visitRepository.findByPatientIdOrderByScheduledAtDesc(patient.getId())) {
            if (v.getStatus() == VisitStatus.SCHEDULED
                    && v.getScheduledAt() != null && !v.getScheduledAt().isBefore(now)) {
                v.setStatus(VisitStatus.CANCELLED);
                v.setArrangementNote("转诊单 " + r.getReferralNo()
                        + "：患者已救护车转运住院，后续巡诊取消，出院后按医生决定重排");
                visitRepository.save(v);
                cancelled++;
            }
        }
        r.setBedSuspendedAt(now);

        // 4) 未结算账单进入待审核（冻结）
        int frozen = freezeUnsettledBills(r);
        r.setPendingBillCount(frozen);

        r = referralRepository.save(r);

        // 通知家属与医院救护车已出发
        notifyService.sendUser(patient.getFamilyUser() == null ? null : patient.getFamilyUser().getId(),
                patient.getPrimaryCaregiver(), "站内+短信", "【转诊】救护车已出发",
                "家庭医生已远程判断并发起救护车转运至 " + r.getTargetHospital()
                        + "，家庭病床服务自 " + now.toLocalDate() + " 起暂停，"
                        + frozen + " 张未结算账单进入待审核。",
                "REFERRAL", r.getReferralNo(), patient.getId());
        notifyService.sendExternal(r.getTargetHospital() + "·急诊预检", "区域转诊平台",
                "【120转运】家庭病床高危患者转入",
                "患者 " + patient.getName() + " 由家庭医生判断后救护车转运，主诉：" + r.getSymptom()
                        + "；随车备注：" + ambulanceNote,
                "REFERRAL", r.getReferralNo(), patient.getId());

        auditService.log(me, "转诊闭环", "远程判断·发起救护车", r.getId(), r.getReferralNo(),
                patient.getId(), "判断意见=" + judgeNote + "；目标医院=" + r.getTargetHospital()
                        + "；暂停计划并取消后续巡诊 " + cancelled + " 次；冻结未结算账单 " + frozen + " 张");
        return r;
    }

    /** 医生远程判断为暂不需转诊（高危转就地处置），记录意见，不进入转运 */
    @Transactional
    public Referral holdOnSite(Long id, String judgeNote) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家庭医生可远程判断");
        }
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.CRITICAL_FLAGGED) {
            throw new BusinessException("当前状态不可就地处置");
        }
        r.setJudgeDoctor(userRepository.findById(me.id()).orElse(null));
        r.setJudgedAt(LocalDateTime.now());
        r.setJudgeNote("经远程评估暂不上救护车，就地/门诊处置：" + judgeNote);
        // 就地处置：风险事件保留开放观察，转诊单标记后可继续再发起
        r.setAmbulanceNote("HOLD：" + judgeNote);
        r = referralRepository.save(r);
        auditService.log(me, "转诊闭环", "远程判断·就地处置", r.getId(), r.getReferralNo(),
                r.getPatient().getId(), judgeNote);
        return r;
    }

    /** 阶段3：患者送达医院，转诊完成 */
    @Transactional
    public Referral completeReferral(Long id, String admittedHospital, LocalDateTime admittedAt, String note) {
        LoginUser me = SecurityUtils.current();
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.AMBULANCE_DISPATCHED) {
            throw new BusinessException("仅救护车转运中的转诊单可确认送达");
        }
        if (admittedHospital != null && !admittedHospital.isBlank()) r.setAdmittedHospital(admittedHospital);
        if (r.getAdmittedHospital() == null) r.setAdmittedHospital(r.getTargetHospital());
        r.setReferredAt(LocalDateTime.now());
        if (admittedAt != null) r.setAdmittedAt(admittedAt);
        r.setDescription((r.getDescription() == null ? "" : r.getDescription() + "；")
                + "转诊送达：" + note);
        r.setStatus(ReferralStatus.REFERRED);
        r = referralRepository.save(r);

        notifyService.sendUser(r.getPatient().getFamilyUser() == null ? null
                        : r.getPatient().getFamilyUser().getId(),
                r.getPatient().getPrimaryCaregiver(), "站内+短信", "【转诊】已送达医院",
                "患者已送达 " + r.getAdmittedHospital()
                        + "。请在取得入院诊断后通过平台补充，医院回传或家属补充后将核定家庭病床费用截至时间。",
                "REFERRAL", r.getReferralNo(), r.getPatient().getId());

        auditService.log(me, "转诊闭环", "转诊送达医院", r.getId(), r.getReferralNo(),
                r.getPatient().getId(), "接诊医院=" + r.getAdmittedHospital() + "；" + note);
        return r;
    }

    public record DiagnosisCommand(DiagnosisSource source, String admissionDiagnosis,
                                   LocalDateTime admittedAt, String note) {}

    /** 阶段4：医院回传或家属补充入院诊断，确定费用截至时间 */
    @Transactional
    public Referral receiveDiagnosis(Long id, DiagnosisCommand cmd) {
        LoginUser me = SecurityUtils.current();
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.REFERRED && r.getStatus() != ReferralStatus.AMBULANCE_DISPATCHED) {
            throw new BusinessException("仅转诊完成后可回传/补充入院诊断，当前：" + r.getStatus().label);
        }
        if (cmd.admissionDiagnosis() == null || cmd.admissionDiagnosis().isBlank()) {
            throw new BusinessException("入院诊断不能为空");
        }
        DiagnosisSource source = cmd.source();
        if (source == null) {
            source = me.role() == Role.FAMILY ? DiagnosisSource.FAMILY_SUPPLEMENT
                    : DiagnosisSource.HOSPITAL_CALLBACK;
        }
        if (me.role() == Role.FAMILY && source != DiagnosisSource.FAMILY_SUPPLEMENT) {
            throw new BusinessException(403, "家属只能以“家属补充”方式提交入院结果，医院回传由医院/平台完成");
        }
        if (source == DiagnosisSource.HOSPITAL_CALLBACK
                && me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN
                && me.role() != Role.FINANCE && me.role() != Role.INSURANCE_AUDITOR) {
            throw new BusinessException(403, "医院回传入院诊断由医生/管理/财务/医保岗位登记");
        }

        LocalDateTime admittedAt = cmd.admittedAt() != null ? cmd.admittedAt()
                : (r.getAdmittedAt() != null ? r.getAdmittedAt() : LocalDateTime.now());
        r.setDiagnosisSource(source);
        r.setAdmissionDiagnosis(cmd.admissionDiagnosis());
        r.setAdmittedAt(admittedAt);
        r.setDiagnosisReceivedAt(LocalDateTime.now());
        r.setDiagnosisBy(userRepository.findById(me.id()).orElse(null));
        if (r.getReferredAt() == null) r.setReferredAt(r.getDiagnosisReceivedAt());

        // 费用截至时间：救护车出发与实际入院取较早者（服务在患者离家/入院即终止）
        LocalDateTime cutoff;
        if (r.getAmbulanceDispatchedAt() != null) {
            cutoff = r.getAmbulanceDispatchedAt().isBefore(admittedAt)
                    ? r.getAmbulanceDispatchedAt() : admittedAt;
        } else {
            cutoff = admittedAt;
        }
        r.setFeeCutoffAt(cutoff);
        r.setFeeCutoffNote("依据" + source.label + "，取救护车出发("
                + r.getAmbulanceDispatchedAt() + ")与实际入院(" + admittedAt + ")较早者");
        r.setStatus(ReferralStatus.DIAGNOSIS_RECEIVED);
        r = referralRepository.save(r);

        // 对冻结的待审核账单应用费用截止（剔除住院后明细，重算金额），账单仍待审核
        int reviewed = applyCutoffToFrozenBills(r);

        notifyService.sendUser(r.getPatient().getFamilyUser() == null ? null
                        : r.getPatient().getFamilyUser().getId(),
                r.getPatient().getPrimaryCaregiver(), "站内+短信", "【转诊】入院诊断已登记·费用已截止",
                "入院诊断：" + cmd.admissionDiagnosis() + "；家庭病床费用截至 "
                        + cutoff.toLocalDate() + "，此后护理/耗材不计费。",
                "REFERRAL", r.getReferralNo(), r.getPatient().getId());

        auditService.log(me, "转诊闭环",
                source == DiagnosisSource.HOSPITAL_CALLBACK ? "医院回传诊断" : "家属补充诊断",
                r.getId(), r.getReferralNo(), r.getPatient().getId(),
                "诊断=" + cmd.admissionDiagnosis() + "；入院时间=" + admittedAt
                        + "；费用截至=" + cutoff + "；已核定待审核账单 " + reviewed + " 张");
        return r;
    }

    /** 阶段5a：登记出院信息 */
    @Transactional
    public Referral discharge(Long id, LocalDate dischargeDate, String dischargeNote) {
        LoginUser me = SecurityUtils.current();
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.DIAGNOSIS_RECEIVED) {
            throw new BusinessException("入院诊断与费用截止确定后才能登记出院");
        }
        r.setDischargeDate(dischargeDate != null ? dischargeDate : LocalDate.now());
        r.setDischargeNote(dischargeNote);
        r.setStatus(ReferralStatus.DISCHARGED);
        r = referralRepository.save(r);
        auditService.log(me, "转诊闭环", "登记出院", r.getId(), r.getReferralNo(),
                r.getPatient().getId(), "出院日期=" + r.getDischargeDate() + "；" + dischargeNote);
        return r;
    }

    /** 阶段5b：出院后医生决定恢复家庭病床（计划恢复、账单解冻） */
    @Transactional
    public Referral resumeBed(Long id, LocalDate resumeVisitDate, String decisionNote) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家庭医生可决定恢复家庭病床");
        }
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.DISCHARGED && r.getStatus() != ReferralStatus.DIAGNOSIS_RECEIVED) {
            throw new BusinessException("须先登记出院，再由医生决定恢复");
        }
        LocalDateTime now = LocalDateTime.now();
        BedApplication app = r.getApplication();
        if (app != null && app.getStatus() == BedApplyStatus.SUSPENDED) {
            app.setStatus(BedApplyStatus.APPROVED);
            applicationRepository.save(app);
        }
        if (r.getPlan() != null && r.getPlan().getStatus() == PlanStatus.PAUSED) {
            r.getPlan().setStatus(PlanStatus.ACTIVE);
            r.getPlan().setScheduleExplanation(
                    (r.getPlan().getScheduleExplanation() == null ? ""
                            : r.getPlan().getScheduleExplanation() + "\n")
                            + "【转诊恢复】" + now + " 出院后医生评估恢复家庭病床，首次上门 "
                            + (resumeVisitDate != null ? resumeVisitDate : "待排班"));
            planRepository.save(r.getPlan());
        }
        releaseFrozenBills(r);

        r.setDecideDoctor(userRepository.findById(me.id()).orElse(null));
        r.setDecidedAt(now);
        r.setResumeVisitDate(resumeVisitDate);
        r.setDecisionNote("同意恢复家庭病床：" + decisionNote);
        r.setStatus(ReferralStatus.RESUMED);
        r.setClosedAt(now);
        r.setCloseNote("患者出院，家庭病床恢复，转诊闭环完成");
        r = referralRepository.save(r);

        // 恢复风险事件
        if (r.getRiskEvent() != null && r.getRiskEvent().getStatus() != RiskEventStatus.CLOSED) {
            RiskEvent risk = r.getRiskEvent();
            risk.setStatus(RiskEventStatus.CLOSED);
            risk.setCloseNote("转诊住院治疗后出院，家庭病床恢复，闭环");
            risk.setClosedAt(now);
            riskRepository.save(risk);
        }

        auditService.log(me, "转诊闭环", "医生决定恢复家庭病床", r.getId(), r.getReferralNo(),
                r.getPatient().getId(), "首次上门=" + resumeVisitDate + "；" + decisionNote);
        return r;
    }

    /** 阶段5c：出院后医生决定撤床（不再恢复），闭环 */
    @Transactional
    public Referral withdrawBed(Long id, String decisionNote) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FAMILY_DOCTOR && me.role() != Role.ADMIN) {
            throw new BusinessException(403, "仅家庭医生可决定撤床");
        }
        Referral r = get(id);
        if (r.getStatus() != ReferralStatus.DISCHARGED && r.getStatus() != ReferralStatus.DIAGNOSIS_RECEIVED) {
            throw new BusinessException("须先确定诊断/出院信息后再撤床");
        }
        LocalDateTime now = LocalDateTime.now();
        BedApplication app = r.getApplication();
        if (app != null) {
            app.setStatus(BedApplyStatus.WITHDRAWN);
            applicationRepository.save(app);
        }
        if (r.getPlan() != null && r.getPlan().getStatus() != PlanStatus.COMPLETED) {
            r.getPlan().setStatus(PlanStatus.CANCELLED);
            planRepository.save(r.getPlan());
        }
        // 撤床：待审核账单应用截止后释放回待确认，供家属确认结算
        releaseFrozenBills(r);

        r.setDecideDoctor(userRepository.findById(me.id()).orElse(null));
        r.setDecidedAt(now);
        r.setDecisionNote("出院后评估不再恢复家庭病床，予以撤床：" + decisionNote);
        r.setStatus(ReferralStatus.WITHDRAWN);
        r.setClosedAt(now);
        r.setCloseNote("家庭病床撤销，转诊闭环完成");
        r = referralRepository.save(r);

        if (r.getRiskEvent() != null && r.getRiskEvent().getStatus() != RiskEventStatus.CLOSED) {
            RiskEvent risk = r.getRiskEvent();
            risk.setStatus(RiskEventStatus.CLOSED);
            risk.setCloseNote("转诊后撤床，闭环");
            risk.setClosedAt(now);
            riskRepository.save(risk);
        }

        auditService.log(me, "转诊闭环", "医生决定撤床", r.getId(), r.getReferralNo(),
                r.getPatient().getId(), decisionNote);
        return r;
    }

    /** 财务/管理对待审核账单逐张审核通过（费用截止已剔除住院项） */
    @Transactional
    public Bill reviewFrozenBill(Long referralId, Long billId, boolean approved, String auditNote) {
        LoginUser me = SecurityUtils.current();
        if (me.role() != Role.FINANCE && me.role() != Role.ADMIN && me.role() != Role.INSURANCE_AUDITOR) {
            throw new BusinessException(403, "仅财务/医保/管理可审核冻结账单");
        }
        Referral r = get(referralId);
        Bill bill = billRepository.findById(billId)
                .orElseThrow(() -> new BusinessException(404, "账单不存在"));
        if (!r.equals(bill.getReferral())) throw new BusinessException("账单不属于该转诊单");
        if (bill.getStatus() != BillStatus.PENDING_REVIEW) {
            throw new BusinessException("仅转诊待审核账单可审核，当前：" + bill.getStatus().label);
        }
        if (!approved) {
            bill.setFreezeReason("审核未通过，维持冻结：" + auditNote);
            bill = billRepository.save(bill);
            auditService.log(me, "转诊闭环", "待审核账单驳回", bill.getId(), bill.getBillNo(),
                    bill.getPatient().getId(), auditNote);
            return bill;
        }
        bill.setStatus(BillStatus.DRAFT);
        bill.setFreezeReason((bill.getFreezeReason() == null ? "" : bill.getFreezeReason()
                + "；") + "审核通过(" + auditNote + ")，已按费用截至 " + bill.getFeeCutoffAt()
                + " 剔除住院后明细，退回家属确认");
        bill = billRepository.save(bill);
        auditService.log(me, "转诊闭环", "待审核账单通过", bill.getId(), bill.getBillNo(),
                bill.getPatient().getId(), auditNote);
        return bill;
    }

    // ===== 计费联动支撑（供 BillingService 调用） =====

    /**
     * 患者当前有效费用截至时间（住院后服务不计费）。
     * 仅当最近一次转诊仍处于“已核定截止但未终结”阶段时生效；
     * 患者恢复家庭病床（RESUMED）后，截止住院事件成为历史，新产生的服务应正常计费
     * （历史账单的截止快照已保存在 bill.fee_cutoff_at，不受影响）。
     */
    @Transactional(readOnly = true)
    public LocalDateTime feeCutoffOfPatient(Long patientId) {
        List<Referral> all = referralRepository.findByPatientIdOrderByFlaggedAtDesc(patientId);
        if (all.isEmpty()) return null;
        Referral latest = all.get(0);
        if (latest.getStatus() == ReferralStatus.RESUMED
                || latest.getStatus() == ReferralStatus.WITHDRAWN
                || latest.getStatus() == ReferralStatus.CRITICAL_FLAGGED) {
            return null;
        }
        return latest.getFeeCutoffAt();
    }

    /**
     * 出账前校验：患者处于救护车转运/转诊住院且费用截止尚未核定时，
     * 不允许生成账单（避免住院期间巡诊计划继续计费）。
     * @return 已核定的费用截至时间（可能为 null，表示无限制）
     */
    @Transactional(readOnly = true)
    public LocalDateTime assertBillingAllowed(Long patientId) {
        Referral active = referralRepository
                .findFirstByPatientIdAndStatusInOrderByFlaggedAtDesc(patientId, IN_HOSPITAL).orElse(null);
        if (active != null) {
            throw new BusinessException("患者正在转诊住院中（转诊单 " + active.getReferralNo()
                    + "），入院诊断与费用截至时间尚未核定，暂不能出账；待医院回传或家属补充诊断后再生成");
        }
        return feeCutoffOfPatient(patientId);
    }

    // ===== 内部方法 =====

    private Referral get(Long id) {
        return referralRepository.findById(id)
                .orElseThrow(() -> new BusinessException(404, "转诊单不存在"));
    }

    private BedApplication activeApplication(Long patientId) {
        return applicationRepository.findByPatientIdOrderByCreatedAtDesc(patientId).stream()
                .filter(a -> a.getStatus() == BedApplyStatus.APPROVED
                        || a.getStatus() == BedApplyStatus.SUSPENDED)
                .findFirst().orElse(null);
    }

    /** 冻结所有未结算账单（非已结算），进入转诊待审核 */
    private int freezeUnsettledBills(Referral r) {
        int n = 0;
        for (Bill b : billRepository.findByPatientIdOrderByPeriodEndDesc(r.getPatient().getId())) {
            if (b.getStatus() == BillStatus.SETTLED || b.getStatus() == BillStatus.PENDING_REVIEW) continue;
            b.setReferral(r);
            b.setStatus(BillStatus.PENDING_REVIEW);
            b.setFreezeReason("转诊单 " + r.getReferralNo() + "：患者住院，未结算费用待入院诊断回传后按费用截至核定");
            billRepository.save(b);
            n++;
        }
        return n;
    }

    /**
     * 按费用截至时间处理待审核账单：
     * 服务时间晚于截至时间的明细标记剔除并清零，重算账单金额。
     */
    private int applyCutoffToFrozenBills(Referral r) {
        int n = 0;
        LocalDate cutoffDate = r.getFeeCutoffAt().toLocalDate();
        for (Bill b : billRepository.findByPatientIdOrderByPeriodEndDesc(r.getPatient().getId())) {
            if (b.getStatus() != BillStatus.PENDING_REVIEW || !r.equals(b.getReferral())) continue;
            int excluded = 0;
            for (BillItem item : billItemRepository.findByBillId(b.getId())) {
                if (Boolean.TRUE.equals(item.getCutoffExcluded())) continue;
                LocalDate serviceDate = parseDate(item.getServiceDate());
                if (serviceDate != null && serviceDate.isAfter(cutoffDate)) {
                    item.setCutoffExcluded(true);
                    item.setNote("【转诊费用截止剔除】服务日 " + serviceDate + " 晚于费用截至 "
                            + cutoffDate + "，患者已住院，不计费；原备注：" + item.getNote());
                    item.setAmount(BigDecimal.ZERO);
                    item.setInsuranceAmount(BigDecimal.ZERO);
                    item.setSelfPayAmount(BigDecimal.ZERO);
                    item.setQuantity(0);
                    billItemRepository.save(item);
                    excluded++;
                }
            }
            recalc(b);
            b.setFeeCutoffAt(r.getFeeCutoffAt());
            b.setCutoffExcludedCount((b.getCutoffExcludedCount() == null ? 0 : b.getCutoffExcludedCount()) + excluded);
            billRepository.save(b);
            n++;
        }
        return n;
    }

    /** 账单解冻：有费用截止则确保已剔除，退回待确认供家属确认 */
    private void releaseFrozenBills(Referral r) {
        for (Bill b : billRepository.findByPatientIdOrderByPeriodEndDesc(r.getPatient().getId())) {
            if (b.getStatus() != BillStatus.PENDING_REVIEW || !r.equals(b.getReferral())) continue;
            if (b.getFeeCutoffAt() == null && r.getFeeCutoffAt() != null) {
                applyCutoffToFrozenBills(r);
            }
            b.setStatus(BillStatus.DRAFT);
            b.setFreezeReason((b.getFreezeReason() == null ? "" : b.getFreezeReason()
                    + "；") + "转诊流程结束，账单解冻退回家属确认");
            billRepository.save(b);
        }
    }

    private void recalc(Bill b) {
        BigDecimal total = BigDecimal.ZERO, ins = BigDecimal.ZERO, self = BigDecimal.ZERO;
        for (BillItem item : billItemRepository.findByBillId(b.getId())) {
            if (Boolean.TRUE.equals(item.getCutoffExcluded())) continue;
            total = total.add(item.getAmount());
            ins = ins.add(item.getInsuranceAmount());
            self = self.add(item.getSelfPayAmount());
        }
        b.setTotalAmount(total.setScale(2, RoundingMode.HALF_UP));
        b.setInsuranceAmount(ins.setScale(2, RoundingMode.HALF_UP));
        b.setSelfPayAmount(self.setScale(2, RoundingMode.HALF_UP));
    }

    private LocalDate parseDate(String s) {
        try { return s == null ? null : LocalDate.parse(s); }
        catch (Exception e) { return null; }
    }
}
