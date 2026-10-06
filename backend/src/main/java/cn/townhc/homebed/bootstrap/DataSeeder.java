package cn.townhc.homebed.bootstrap;

import cn.townhc.homebed.domain.entity.*;
import cn.townhc.homebed.domain.enums.DomainEnums.*;
import cn.townhc.homebed.repository.*;
import cn.townhc.homebed.service.ApplicationService;
import cn.townhc.homebed.service.BillingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;

/**
 * 演示数据初始化：仅在 sys_user 为空时执行一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final SysUserRepository userRepository;
    private final PatientRepository patientRepository;
    private final BedApplicationRepository applicationRepository;
    private final CareNeedRepository careNeedRepository;
    private final VisitPlanRepository planRepository;
    private final VisitRepository visitRepository;
    private final VisitCareResultRepository careResultRepository;
    private final VisitPhotoRepository photoRepository;
    private final VisitExceptionRepository exceptionRepository;
    private final MaterialRepository materialRepository;
    private final MaterialTransactionRepository materialTxRepository;
    private final ChargeCatalogRepository catalogRepository;
    private final MedicalOrderRepository orderRepository;
    private final RiskEventRepository riskRepository;
    private final ReferralRepository referralRepository;
    private final ReferralNotificationRepository referralNotificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final BillingService billingService;

    @Value("${app.upload.dir}")
    private String uploadDir;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (userRepository.count() > 0) {
            log.info("检测到已有数据，跳过种子初始化");
            return;
        }
        log.info("开始写入演示数据……");

        // ===== 账号 =====
        String pwd = passwordEncoder.encode("123456");
        SysUser family = user("family", "王建国", Role.FAMILY, "家属", "13800001111", pwd, null);
        SysUser doctor1 = user("doctor", "李建国", Role.FAMILY_DOCTOR, "主治医师", "13900002222", pwd, null);
        SysUser doctor2 = user("doctor2", "周敏", Role.FAMILY_DOCTOR, "副主任医师", "13900003333", pwd, null);
        SysUser nurse1 = user("nurse1", "刘芳", Role.NURSE, "主管护师", "13700004444", pwd,
                "压疮护理,导尿,换药,注射");
        SysUser nurse2 = user("nurse2", "陈雪", Role.NURSE, "护师", "13700005555", pwd, "换药,注射");
        SysUser nurse3 = user("nurse3", "赵秀兰", Role.NURSE, "护士", "13700006666", pwd, "压疮护理,导尿");
        SysUser admin = user("admin", "吴院长", Role.ADMIN, "卫生院负责人", "13600007777", pwd, null);
        SysUser finance = user("finance", "郑财务", Role.FINANCE, "结算会计", "13600008888", pwd, null);
        SysUser insurance = user("insurance", "钱经办", Role.INSURANCE_AUDITOR, "医保经办", "13600009999", pwd, null);
        userRepository.saveAll(List.of(family, doctor1, doctor2, nurse1, nurse2, nurse3, admin, finance, insurance));

        // ===== 患者 =====
        Patient p1 = patient("王王氏", "女", 82, "卧床", DiseaseCategory.STROKE_REHAB,
                InsuranceType.URBAN_RESIDENT, "青山村三组 18 号", 30.6706, 117.4952, family,
                "王建国(儿子)", "13800001111");
        Patient p2 = patient("李长顺", "男", 69, "轮椅", DiseaseCategory.POST_SURGICAL,
                InsuranceType.URBAN_EMPLOYEE, "河东新区安置点 7 栋", 30.6886, 117.4952, family,
                "李长顺配偶·刘桂兰", "13800002111");
        // 同时合并糖尿病
        p2.setDiseaseCategory(DiseaseCategory.DIABETES);
        Patient p3 = patient("孙桂英", "女", 76, "轮椅", DiseaseCategory.COPD,
                InsuranceType.URBAN_RESIDENT, "大岭村(山后) 2 号", 30.7066, 117.5252, family,
                "孙桂英儿媳·周小丽", "13800003111");
        Patient p4 = patient("周志高", "男", 71, "可扶行", DiseaseCategory.HYPERTENSION,
                InsuranceType.URBAN_RESIDENT, "镇中社区康乐巷 3 号", 30.6516, 117.4952, family,
                "周志高配偶·黄秀珍", "13800004111");
        patientRepository.saveAll(List.of(p1, p2, p3, p4));

        // ===== 收费/医保目录 =====
        ChargeCatalog visitFee = catalog("XZ001", "家庭病床巡诊费", CatalogCategory.VISIT_FEE, "次",
                bd("40"), true, "甲类", bd("0.90"), false, "每日限 1 次", 1);
        ChargeCatalog ulcer = catalog("HL001", "压疮护理", CatalogCategory.NURSING, "次",
                bd("35"), true, "乙类", bd("0.70"), false, null, null);
        ChargeCatalog dressing = catalog("HL002", "伤口换药", CatalogCategory.NURSING, "次",
                bd("25"), true, "乙类", bd("0.70"), false, "每日限 1 次", 1);
        ChargeCatalog catheter = catalog("HL003", "导尿护理", CatalogCategory.NURSING, "次",
                bd("30"), true, "乙类", bd("0.70"), false, null, null);
        ChargeCatalog glucose = catalog("HL004", "血糖监测", CatalogCategory.NURSING, "次",
                bd("8"), true, "甲类", bd("0.90"), false, null, null);
        ChargeCatalog bp = catalog("HL005", "血压监测", CatalogCategory.NURSING, "次",
                bd("5"), true, "甲类", bd("0.90"), false, null, null);
        ChargeCatalog inject = catalog("HL006", "肌肉/皮下注射费", CatalogCategory.NURSING, "次",
                bd("12"), true, "乙类", bd("0.70"), false, null, null);
        ChargeCatalog dressingKit = catalog("HC001", "一次性换药包", CatalogCategory.CONSUMABLE, "个",
                bd("15"), true, "乙类", bd("0.60"), false, null, null);
        ChargeCatalog catheterKit = catalog("HC002", "一次性导尿包", CatalogCategory.CONSUMABLE, "套",
                bd("22"), true, "乙类", bd("0.60"), false, null, null);
        ChargeCatalog gauze = catalog("HC003", "无菌纱布块", CatalogCategory.CONSUMABLE, "块",
                bd("2"), false, "丙类", bd("0"), false, null, null);
        ChargeCatalog insulin = catalog("DR001", "门冬胰岛素注射液(冷链)", CatalogCategory.COLDCHAIN_DRUG, "支",
                bd("68"), true, "乙类", bd("0.70"), true, "2-8℃冷链保存运输", null);
        ChargeCatalog antibiotic = catalog("DR002", "头孢呋辛酯片", CatalogCategory.DRUG, "盒",
                bd("26"), true, "甲类", bd("0.90"), false, null, null);
        catalogRepository.saveAll(List.of(visitFee, ulcer, dressing, catheter, glucose, bp,
                inject, dressingKit, catheterKit, gauze, insulin, antibiotic));

        // ===== 耗材库存 =====
        Material mDressingKit = material("HC001", "一次性换药包", "无菌独立包装", "个",
                CatalogCategory.CONSUMABLE, 18, 10, bd("9"), dressingKit, false);
        Material mCatheterKit = material("HC002", "一次性导尿包", "F16", "套",
                CatalogCategory.CONSUMABLE, 6, 8, bd("14"), catheterKit, false);
        Material mGauze = material("HC003", "无菌纱布块", "8cm*8cm", "块",
                CatalogCategory.CONSUMABLE, 120, 40, bd("0.8"), gauze, false);
        Material mInsulin = material("DR001", "门冬胰岛素注射液", "300IU*3ml", "支",
                CatalogCategory.COLDCHAIN_DRUG, 9, 6, bd("52"), insulin, true);
        Material mAntibiotic = material("DR002", "头孢呋辛酯片", "0.25g*12片", "盒",
                CatalogCategory.DRUG, 14, 5, bd("18"), antibiotic, false);
        materialRepository.saveAll(List.of(mDressingKit, mCatheterKit, mGauze, mInsulin, mAntibiotic));

        // ===== 申请单 =====
        BedApplication a1 = submittedApp(p1, family,
                "老人脑卒中后长期卧床，右侧肢体活动不利，需定期上门测血压、压疮护理与康复指导。",
                "血压监测、压疮护理、健康指导");
        applicationRepository.save(a1);
        for (CareItemType t : List.of(CareItemType.BLOOD_PRESSURE, CareItemType.PRESSURE_ULCER,
                CareItemType.HEALTH_EDUCATION)) {
            careNeedRepository.save(need(a1, t, t == CareItemType.BLOOD_PRESSURE ? 3 : 2, null));
        }

        BedApplication a2 = approvedApp(p2, family, doctor1,
                "右膝关节置换术后 2 周，合并 2 型糖尿病，行动不便，需上门换药、血糖监测和注射胰岛素。",
                "术后换药、血糖监测、胰岛素注射",
                (int) Math.round(ApplicationService.distanceMeters(
                        ApplicationService.TOWN_HC_LAT, ApplicationService.TOWN_HC_LNG,
                        p2.getLatitude(), p2.getLongitude())),
                "配偶长期在家照护，能配合观察伤口与提醒服药", "安置点住房宽敞，光线好，具备换药条件",
                true, "职工医保参保正常，糖尿病门诊慢病待遇在有效期",
                RiskLevel.MEDIUM, "术后伤口感染风险；低血糖风险；独居时段(白天仅配偶)应急能力一般",
                "符合家庭病床建床条件，建议每周巡诊 3 次，伤口隔日换药，监测血糖并调整胰岛素用量",
                "JC-0002");
        applicationRepository.save(a2);
        for (CareItemType t : List.of(CareItemType.DRESSING, CareItemType.BLOOD_GLUCOSE,
                CareItemType.INJECTION, CareItemType.VITALS)) {
            careNeedRepository.save(need(a2, t, t == CareItemType.BLOOD_GLUCOSE ? 4 : 3, null));
        }

        BedApplication a3 = approvedApp(p3, family, doctor1,
                "慢阻肺急性加重出院后需氧疗随访，居住大岭村山后，距离较远，家属要求上门。",
                "生命体征、血氧观察、用药提醒",
                (int) Math.round(ApplicationService.distanceMeters(
                        ApplicationService.TOWN_HC_LAT, ApplicationService.TOWN_HC_LNG,
                        p3.getLatitude(), p3.getLongitude())),
                "儿媳白天在镇上打零工，午间不在家", "山区平房，冬季湿冷，氧疗设备可用",
                true, "居民医保正常",
                RiskLevel.HIGH, "急性加重复发风险高；居住偏远，急救到达时间长",
                "可以建床，但须纳入高风险随访：同片区路线拼单上门，病情加重立即转诊并提前告知家属路程限制",
                "JC-0003");
        applicationRepository.save(a3);
        for (CareItemType t : List.of(CareItemType.VITALS, CareItemType.MEDICATION_REMINDER,
                CareItemType.HEALTH_EDUCATION)) {
            careNeedRepository.save(need(a3, t, 2, null));
        }

        BedApplication a4 = approvedApp(p4, family, doctor1,
                "高血压 3 级，近期血压波动，要求建床随访。",
                "血压监测、用药提醒",
                (int) Math.round(ApplicationService.distanceMeters(
                        ApplicationService.TOWN_HC_LAT, ApplicationService.TOWN_HC_LNG,
                        p4.getLatitude(), p4.getLongitude())),
                "配偶在家照护", "镇区楼房，条件好",
                true, "居民医保正常",
                RiskLevel.LOW, "血压波动，暂无严重并发症",
                "符合建床条件，每周 2 次随访测压，强化用药依从性", "JC-0004");
        applicationRepository.save(a4);
        for (CareItemType t : List.of(CareItemType.BLOOD_PRESSURE, CareItemType.MEDICATION_REMINDER)) {
            careNeedRepository.save(need(a4, t, 2, null));
        }

        // ===== 历史计划与巡诊（患者2：已完成 3 次） =====
        VisitPlan plan2 = new VisitPlan();
        plan2.setPlanNo("JH20260920001");
        plan2.setApplication(a2);
        plan2.setPatient(p2);
        plan2.setDoctor(doctor1);
        plan2.setNurse(nurse1);
        plan2.setStartDate(LocalDate.now().minusWeeks(2));
        plan2.setExpectedEndDate(LocalDate.now().plusWeeks(2));
        plan2.setTimesPerWeek(3);
        plan2.setStatus(PlanStatus.ACTIVE);
        plan2.setPlanContent("伤口换药 3次/周；血糖监测 4次/周；胰岛素注射 3次/周；生命体征 3次/周");
        plan2.setScheduleExplanation("""
                · 选择责任护士【刘芳】：具备 换药/注射 技能，与河东片区在管患者路线重合
                ⚠ 患者距卫生院约 3.3 公里，在 5 公里上门半径内，沿河东线拼单安排
                · 未选【陈雪】：不熟练压疮护理；本周负荷较高
                · 未选【赵秀兰】：无注射资质
                """);
        planRepository.save(plan2);

        // 两次完成的巡诊（同一天：上午常规+傍晚应急，制造同项目两次→合规 R1 演示）
        LocalDateTime day = LocalDateTime.now().minusDays(6).withHour(9).withMinute(10).withSecond(0).withNano(0);
        String sign1 = placeholderImage("sign", "家属签字 刘桂兰 " + day.toLocalDate(), new Color(255, 240, 220));
        String scene1 = placeholderImage("scene", "上门现场照 " + day.toLocalDate(), new Color(225, 240, 255));
        String dressingPhoto = placeholderImage("dressing", "右膝伤口换药 " + day.toLocalDate(), new Color(230, 255, 235));
        Visit v1 = completedVisit(plan2, p2, nurse1, day, 132, 82, 78, 36.6, 196, 97,
                "右膝手术切口对合良好，无明显渗液，轻度红肿", "已用碘伏消毒并更换无菌敷料",
                null, "核对门冬胰岛素 12U 餐前皮下注射，家属已掌握轮换注射部位",
                "患者精神可，伤口疼痛评分 2 分", "刘桂兰", sign1,
                new String[]{scene1, dressingPhoto});
        addCareResults(v1, visitFee, bp, glucose, dressing, inject);
        consume(v1, mDressingKit, 1, null, 5.2, nurse1, "术后换药");
        consume(v1, mGauze, 2, null, null, nurse1, "换药消耗");
        consume(v1, mInsulin, 1, "B20260801", 5.1, nurse1, "当次注射用");
        // 照片记录
        for (String path : new String[]{scene1, dressingPhoto, sign1}) {
            VisitPhoto ph = new VisitPhoto();
            ph.setVisit(v1);
            ph.setFilePath(path);
            ph.setPhotoType(path.contains("dressing") ? "dressing"
                    : path.contains("sign") ? "signature" : "scene");
            ph.setCaption(path);
            photoRepository.save(ph);
        }

        LocalDateTime dayEvening = day.withHour(17).withMinute(20);
        String sign1b = placeholderImage("sign", "家属签字 刘桂兰(晚间补换)", new Color(255, 240, 220));
        Visit v1b = completedVisit(plan2, p2, nurse1, dayEvening, 138, 85, 80, 36.7, 210, 97,
                null, "患者午后不慎弄湿敷料，家属来电后护士顺路再次换药",
                null, "再次核对晚间用药", "敷料渗湿后重新换药",
                "刘桂兰", sign1b, new String[]{sign1b});
        // 第二次换药 → 与 HL002 每日限 1 次冲突（R1 重复收费演示，同时也是真实的“一日两换”争议素材）
        addCareResults(v1b, bp, dressing);
        consume(v1b, mDressingKit, 1, null, 5.6, nurse1, "敷料渗湿补换");
        VisitPhoto phb = new VisitPhoto();
        phb.setVisit(v1b);
        phb.setFilePath(sign1b);
        phb.setPhotoType("signature");
        phb.setCaption(sign1b);
        photoRepository.save(phb);

        // 3 天前一次：冷链温度未登记（R5 演示）+ 血糖偏高风险
        LocalDateTime day2 = LocalDateTime.now().minusDays(3).withHour(9).withMinute(5).withSecond(0).withNano(0);
        String sign2 = placeholderImage("sign", "家属签字 刘桂兰", new Color(255, 240, 220));
        Visit v2 = completedVisit(plan2, p2, nurse1, day2, 145, 88, 82, 36.8, 248, 96,
                "骶尾部皮肤发红，未破溃，指导定时翻身", "切口愈合良好，已拆线",
                null, "门冬胰岛素剂量已记录，提示近两日餐后血糖偏高",
                "空腹血糖偏高，已报家庭医生", "刘桂兰", sign2, new String[]{sign2});
        addCareResults(v2, visitFee, bp, glucose, ulcer, inject);
        consume(v2, mInsulin, 1, "B20260801", null, nurse1, "交接温度未登记(设备故障)");
        consume(v2, mGauze, 1, null, null, nurse1, "压疮预防护理");
        VisitPhoto ph2 = new VisitPhoto();
        ph2.setVisit(v2);
        ph2.setFilePath(sign2);
        ph2.setPhotoType("signature");
        photoRepository.save(ph2);

        // 风险事件：血糖偏高（已闭环）+ 血压事件留待 v3 演示在开放列表
        RiskEvent closedRisk = new RiskEvent();
        closedRisk.setRiskNo("FX20260930001");
        closedRisk.setPatient(p2);
        closedRisk.setVisit(v2);
        closedRisk.setRiskType("血糖偏高");
        closedRisk.setSeverity(RiskSeverity.MEDIUM);
        closedRisk.setDescription("空腹血糖记录值偏高(13.8mmol/L)，已通知医生调整胰岛素");
        closedRisk.setStatus(RiskEventStatus.CLOSED);
        closedRisk.setReporter(nurse1);
        closedRisk.setReportedAt(v2.getCompletedAt());
        closedRisk.setConfirmer(doctor1);
        closedRisk.setHandlePlan("门冬胰岛素早餐前增加 2U，连续监测 3 天空腹及餐后血糖");
        closedRisk.setCloseNote("复测空腹血糖 7.2mmol/L，控制改善，闭环观察");
        closedRisk.setClosedAt(LocalDateTime.now().minusDays(1));
        riskRepository.save(closedRisk);

        // 医嘱：调整处方（已执行）+ 复诊（远程复核演示）
        MedicalOrder o1 = new MedicalOrder();
        o1.setOrderNo("YZ20261001001");
        o1.setPatient(p2);
        o1.setVisit(v2);
        o1.setPlan(plan2);
        o1.setDoctor(doctor1);
        o1.setOrderType(OrderType.PRESCRIPTION);
        o1.setContent("门冬胰岛素早餐前由 10U 调整为 12U；继续监测空腹+晚餐后 2h 血糖并记录");
        o1.setDiagnosisBasis("近 3 天空腹血糖 9.8-13.8mmol/L，餐后控制不佳");
        o1.setStatus(OrderStatus.EXECUTED);
        o1.setReviewer(doctor2);
        o1.setReviewComment("剂量调整幅度合理，注意防范低血糖");
        o1.setReviewedAt(LocalDateTime.now().minusDays(2));
        o1.setExecutionNote("护士已向家属交代并执行调整");
        o1.setExecutedAt(LocalDateTime.now().minusDays(2));
        orderRepository.save(o1);

        MedicalOrder o2 = new MedicalOrder();
        o2.setOrderNo("YZ20261002001");
        o2.setPatient(p2);
        o2.setVisit(v2);
        o2.setPlan(plan2);
        o2.setDoctor(doctor1);
        o2.setOrderType(OrderType.FOLLOW_UP);
        o2.setContent("10 月 10 日上午到镇卫生院全科门诊复诊，复查糖化血红蛋白与肝肾功能");
        o2.setDiagnosisBasis("糖尿病季度随访到期");
        o2.setStatus(OrderStatus.REMOTE_REVIEWED);
        o2.setReviewer(doctor2);
        o2.setReviewComment("同意复诊安排");
        o2.setReviewedAt(LocalDateTime.now().minusDays(1));
        orderRepository.save(o2);

        // 未来计划任务：一条正常待上门，一条“家属不在家”已改期，一条耗材缺货挂起
        Visit future1 = visitRepository.save(scheduledVisit(plan2, p2, nurse1,
                LocalDateTime.now().plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0),
                "按计划常规巡诊"));
        Visit future2 = visitRepository.save(scheduledVisit(plan2, p2, nurse1,
                LocalDateTime.now().plusDays(3).withHour(9).withMinute(0).withSecond(0).withNano(0),
                "原预约家属临时不在家，已改期"));
        VisitException absent = new VisitException();
        absent.setVisit(future2);
        absent.setPatient(p2);
        absent.setType(ExceptionType.FAMILY_ABSENT);
        absent.setDescription("家属来电称全天在镇医院陪亲属，无法开门签字，要求改到周末");
        absent.setStatus(ExceptionStatus.RESOLVED);
        absent.setHandler(admin);
        absent.setHandleConclusion("改期至周日上午，并提前一天电话再确认");
        absent.setHandledAt(LocalDateTime.now().minusHours(5));
        exceptionRepository.save(absent);

        Visit future3 = visitRepository.save(scheduledVisit(plan2, p2, nurse1,
                LocalDateTime.now().plusDays(4).withHour(10).withMinute(0).withSecond(0).withNano(0),
                "耗材缺货挂起：导尿包库存不足，到货后恢复"));
        future3.setStatus(VisitStatus.EXCEPTION);
        visitRepository.save(future3);
        VisitException shortAge = new VisitException();
        shortAge.setVisit(future3);
        shortAge.setPatient(p2);
        shortAge.setType(ExceptionType.SUPPLY_SHORTAGE);
        shortAge.setDescription("一次性导尿包仅剩 6 套且多个患者共用，本批次尚未配送");
        shortAge.setStatus(ExceptionStatus.REPORTED);
        exceptionRepository.save(shortAge);

        // ===== 患者3（远距离高风险）计划与任务 =====
        VisitPlan plan3 = new VisitPlan();
        plan3.setPlanNo("JH20261001002");
        plan3.setApplication(a3);
        plan3.setPatient(p3);
        plan3.setDoctor(doctor1);
        plan3.setNurse(nurse3);
        plan3.setStartDate(LocalDate.now());
        plan3.setExpectedEndDate(LocalDate.now().plusWeeks(4));
        plan3.setTimesPerWeek(2);
        plan3.setStatus(PlanStatus.ACTIVE);
        plan3.setPlanContent("生命体征 2次/周；用药提醒；健康指导");
        plan3.setScheduleExplanation("""
                · 选择责任护士【赵秀兰】：本周负荷最低，周二/周五负责大岭片区
                ⚠ 患者距卫生院约 6 公里，超出 5 公里常规半径：仅安排同片区拼单上门，雨雪天改期并提前通知
                ⚙ 家属午间不在家：上门统一约在上午 10 点前，需家属在场签字
                """);
        planRepository.save(plan3);
        visitRepository.save(scheduledVisit(plan3, p3, nurse3,
                LocalDateTime.now().plusDays(1).withHour(9).withMinute(30).withSecond(0).withNano(0),
                "大岭片区拼单第一户，需家属在场"));
        visitRepository.save(scheduledVisit(plan3, p3, nurse3,
                LocalDateTime.now().plusDays(4).withHour(9).withMinute(30).withSecond(0).withNano(0),
                "大岭片区拼单第一户"));

        // 一条开放风险事件（患者3，血氧偏低，建议转诊）
        RiskEvent openRisk = new RiskEvent();
        openRisk.setRiskNo("FX20261003001");
        openRisk.setPatient(p3);
        openRisk.setRiskType("血氧偏低");
        openRisk.setSeverity(RiskSeverity.HIGH);
        openRisk.setDescription("电话随访中家属反映患者气促明显，指脉氧自测约 89%，建议尽快上门评估并转诊");
        openRisk.setStatus(RiskEventStatus.OPEN);
        openRisk.setReporter(doctor1);
        openRisk.setReportedAt(LocalDateTime.now().minusHours(8));
        riskRepository.save(openRisk);

        // 病情恶化转诊闭环演示：护士巡诊发现孙桂英呼吸困难，已标记高危并通知三方，待医生远程判断
        Referral referral = new Referral();
        referral.setReferralNo("ZZ20261005001");
        referral.setPatient(p3);
        referral.setRiskEvent(openRisk);
        referral.setApplication(a3);
        referral.setPlan(plan3);
        referral.setSymptom("呼吸困难");
        referral.setDescription("上午巡诊发现患者气促加重、端坐呼吸，指脉氧 88%，口唇轻度发绀，"
                + "慢阻肺急性加重可能，建议立即转诊上级医院。");
        referral.setTargetHospital("县人民医院");
        referral.setHospitalPhone("0566-120");
        referral.setStatus(ReferralStatus.MARKED);
        referral.setNotifiedDoctorAt(LocalDateTime.now().minusHours(2));
        referral.setNotifiedFamilyAt(LocalDateTime.now().minusHours(2));
        referral.setNotifiedHospitalAt(LocalDateTime.now().minusHours(2));
        referralRepository.save(referral);
        seedNotify(referral, NotifyRecipientType.DOCTOR, doctor1, doctor1.getName(), doctor1.getPhone(),
                "PLATFORM", "患者孙桂英（76岁，慢阻肺）巡诊中发现【呼吸困难】，已标记高危，"
                + "请尽快远程判断是否需要救护车转运（转诊单 ZZ20261005001）。");
        seedNotify(referral, NotifyRecipientType.FAMILY, family, p3.getPrimaryCaregiver(),
                p3.getPrimaryContactPhone(), "PLATFORM",
                "患者孙桂英（76岁，慢阻肺）巡诊中发现【呼吸困难】，已标记高危，平台已通知家庭医生远程判断，"
                + "请保持电话畅通并配合转诊安排。");
        seedNotify(referral, NotifyRecipientType.HOSPITAL, null, "县人民医院", "0566-120", "SMS",
                "【转诊预告】乡镇卫生院家庭病床患者孙桂英（76岁）病情恶化：呼吸困难，可能转诊贵院，"
                + "请做好接诊准备；入院后请回传入院诊断与入院时间。");

        // 患者4：待生成计划（批准未排班）
        // 患者1：待评估

        // 生成一张演示账单（账期覆盖最近 14 天，自动合规初审）
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new cn.townhc.homebed.security.LoginUser(
                        finance.getId(), finance.getUsername(), finance.getName(), finance.getRole()),
                null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority(
                "ROLE_" + finance.getRole().name())));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            billingService.generate(a2.getId(),
                    LocalDate.now().minusDays(14), LocalDate.now());
            log.info("演示统一账单已生成（护理+耗材+冷链合并，含合规初审结果）");
        } catch (Exception e) {
            log.warn("演示账单生成跳过：{}", e.getMessage());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }

        log.info("演示数据写入完成。测试账号: family/doctor/doctor2/nurse1/nurse2/nurse3/admin/finance/insurance，密码均为 123456");
    }

    // ===== helpers =====

    private SysUser user(String username, String name, Role role, String title,
                         String phone, String pwd, String skills) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword(pwd);
        u.setName(name);
        u.setRole(role);
        u.setTitle(title);
        u.setPhone(phone);
        u.setStaffNo("WS" + (1000 + new Random().nextInt(8000)));
        u.setSkills(skills);
        u.setActive(true);
        return u;
    }

    private Patient patient(String name, String gender, int age, String mobility,
                            DiseaseCategory disease, InsuranceType ins, String addr,
                            double lat, double lng, SysUser family, String caregiver, String phone) {
        Patient p = new Patient();
        p.setName(name);
        p.setGender(gender);
        p.setAge(age);
        p.setPhone(phone);
        p.setIdCard("3402" + (1000000000L + new Random().nextInt(899999999)));
        p.setAddress(addr);
        p.setLatitude(lat);
        p.setLongitude(lng);
        p.setInsuranceType(ins);
        p.setInsuranceNo("YB" + (100000 + new Random().nextInt(800000)));
        p.setMobilityLevel(mobility);
        p.setDiseaseCategory(disease);
        p.setPrimaryCaregiver(caregiver);
        p.setPrimaryContactPhone(phone);
        p.setEmergencyContact(caregiver);
        p.setEmergencyPhone(phone);
        p.setFamilyUser(family);
        return p;
    }

    private ChargeCatalog catalog(String code, String name, CatalogCategory cat, String unit,
                                  BigDecimal price, boolean reimburse, String insCat, BigDecimal rate,
                                  boolean coldChain, String limitRule, Integer dailyLimit) {
        ChargeCatalog c = new ChargeCatalog();
        c.setCode(code);
        c.setName(name);
        c.setCategory(cat);
        c.setUnit(unit);
        c.setUnitPrice(price);
        c.setReimbursable(reimburse);
        c.setInsuranceCategory(insCat);
        c.setReimbursementRate(rate);
        c.setColdChain(coldChain);
        c.setLimitRule(limitRule);
        c.setDailyLimit(dailyLimit);
        c.setActive(true);
        c.setEffectiveFrom(LocalDate.now().minusMonths(6));
        c.setVersionNote("初始目录版本");
        return c;
    }

    private Material material(String code, String name, String spec, String unit,
                              CatalogCategory cat, int stock, int safety, BigDecimal cost,
                              ChargeCatalog linked, boolean coldChain) {
        Material m = new Material();
        m.setCode(code);
        m.setName(name);
        m.setSpec(spec);
        m.setUnit(unit);
        m.setCategory(cat);
        m.setStockQty(stock);
        m.setSafetyStock(safety);
        m.setUnitCost(cost);
        m.setCatalog(linked);
        m.setColdChain(coldChain);
        m.setNearestExpiry(LocalDate.now().plusMonths(10));
        m.setActive(true);
        return m;
    }

    private BedApplication baseApp(Patient p, SysUser family, String reason, String expected) {
        BedApplication a = new BedApplication();
        a.setApplyNo(ApplicationService.generateNo("FB"));
        a.setPatient(p);
        a.setApplicantUser(family);
        a.setApplyReason(reason);
        a.setExpectedItems(expected);
        return a;
    }

    private BedApplication submittedApp(Patient p, SysUser family, String reason, String expected) {
        BedApplication a = baseApp(p, family, reason, expected);
        a.setStatus(BedApplyStatus.SUBMITTED);
        return a;
    }

    private BedApplication approvedApp(Patient p, SysUser family, SysUser doctor, String reason,
                                       String expected, int distance, String caregiver, String living,
                                       boolean insEligible, String insNote, RiskLevel risk,
                                       String riskNote, String conclusion, String bedNo) {
        BedApplication a = baseApp(p, family, reason, expected);
        a.setStatus(BedApplyStatus.APPROVED);
        a.setDoctor(doctor);
        a.setAssessedAt(LocalDateTime.now().minusDays(10));
        a.setApprovedAt(LocalDateTime.now().minusDays(10));
        a.setDistanceMeters(distance);
        a.setTravelMinutes(Math.max(5, Math.round(distance / 500f)));
        a.setCaregiverCondition(caregiver);
        a.setLivingCondition(living);
        a.setInsuranceEligible(insEligible);
        a.setInsuranceNote(insNote);
        a.setRiskLevel(risk);
        a.setRiskNotes(riskNote);
        a.setAssessmentConclusion(conclusion);
        a.setBedNo(bedNo);
        return a;
    }

    private CareNeed need(BedApplication app, CareItemType type, int times, String note) {
        CareNeed n = new CareNeed();
        n.setApplication(app);
        n.setItemType(type);
        n.setTimesPerWeek(times);
        n.setNote(note);
        return n;
    }

    private void seedNotify(Referral r, NotifyRecipientType type, SysUser recipientUser,
                            String recipientName, String phone, String channel, String content) {
        ReferralNotification n = new ReferralNotification();
        n.setReferral(r);
        n.setRecipientType(type);
        n.setRecipientUser(recipientUser);
        n.setRecipientName(recipientName);
        n.setRecipientPhone(phone);
        n.setChannel(channel);
        n.setContent(content);
        n.setSentAt(LocalDateTime.now().minusHours(2));
        referralNotificationRepository.save(n);
    }

    private Visit scheduledVisit(VisitPlan plan, Patient p, SysUser nurse,
                                 LocalDateTime time, String note) {
        Visit v = new Visit();
        v.setVisitNo(ApplicationService.generateNo("XZ"));
        v.setPlan(plan);
        v.setPatient(p);
        v.setNurse(nurse);
        v.setScheduledAt(time);
        v.setStatus(VisitStatus.SCHEDULED);
        v.setEstimatedTravelMinutes(plan.getApplication().getTravelMinutes());
        v.setArrangementNote(note);
        return v;
    }

    private Visit completedVisit(VisitPlan plan, Patient p, SysUser nurse, LocalDateTime scheduled,
                                 int sys, int dia, int hr, double temp, int glucose, int spo2,
                                 String ulcerNote, String dressingNote, String catheterNote,
                                 String medNote, String overall, String signName, String signPath,
                                 String[] photos) {
        Visit v = scheduledVisit(plan, p, nurse, scheduled, "按计划上门");
        v.setStatus(VisitStatus.COMPLETED);
        v.setStartedAt(scheduled.minusMinutes(plan.getApplication().getTravelMinutes() == null ? 20
                : plan.getApplication().getTravelMinutes()));
        v.setCompletedAt(scheduled.plusMinutes(40));
        v.setActualTravelMinutes(20 + new Random().nextInt(30));
        v.setSystolicPressure(sys);
        v.setDiastolicPressure(dia);
        v.setHeartRate(hr);
        v.setTemperature(temp);
        v.setBloodGlucose(glucose);
        v.setOxygenSaturation(spo2);
        v.setBreathRate(18);
        v.setPressureUlcerNote(ulcerNote);
        v.setDressingNote(dressingNote);
        v.setCatheterNote(catheterNote);
        v.setMedicationNote(medNote);
        v.setOverallNote(overall);
        v.setFamilySignName(signName);
        v.setFamilySignPath(signPath);
        v.setFamilySignedAt(v.getCompletedAt());
        v.setNurseLocationSnapshot(p.getAddress());
        return visitRepository.save(v);
    }

    private void addCareResults(Visit v, ChargeCatalog... cats) {
        for (ChargeCatalog c : cats) {
            VisitCareResult r = new VisitCareResult();
            r.setVisit(v);
            r.setCatalog(c);
            r.setItemCode(c.getCode());
            r.setItemName(c.getName());
            r.setUnitPrice(c.getUnitPrice());
            r.setQuantity(1);
            r.setResultStatus(CareResultStatus.DONE);
            careResultRepository.save(r);
        }
    }

    private void consume(Visit v, Material m, int qty, String batch, Double temp, SysUser nurse, String note) {
        m.setStockQty(m.getStockQty() - qty);
        materialRepository.save(m);
        MaterialTransaction tx = new MaterialTransaction();
        tx.setMaterial(m);
        tx.setType(MaterialTxType.OUT);
        tx.setQuantity(qty);
        tx.setVisit(v);
        tx.setOperator(nurse);
        tx.setBatchNo(batch);
        tx.setColdChainTemp(temp);
        tx.setNote(note);
        tx.setOccurredAt(v.getCompletedAt());
        materialTxRepository.save(tx);
    }

    private BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    /** 用 AWT 生成占位证据图（签字/现场），避免演示数据出现裂图 */
    private String placeholderImage(String type, String text, Color bg) throws Exception {
        String month = String.valueOf(LocalDate.now().getMonthValue());
        String year = String.valueOf(LocalDate.now().getYear());
        Path dir = Path.of(uploadDir, type, year, month);
        Files.createDirectories(dir);
        String filename = type + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + ".png";
        Path target = dir.resolve(filename);

        BufferedImage img = new BufferedImage(480, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, 480, 240);
        g.setColor(new Color(80, 80, 80));
        g.setFont(new Font("SansSerif", Font.PLAIN, 18));
        g.drawString("Home-bed evidence", 24, 40);
        g.setFont(new Font("SansSerif", Font.BOLD, 22));
        g.setColor(new Color(30, 60, 110));
        int y = 110;
        for (String line : text.split("(?<=\\G.{16})")) {
            if (line.isBlank()) continue;
            g.drawString(line, 24, y);
            y += 32;
        }
        g.dispose();
        ImageIO.write(img, "png", new File(target.toUri()));
        return "/uploads/" + type + "/" + year + "/" + month + "/" + filename;
    }
}
