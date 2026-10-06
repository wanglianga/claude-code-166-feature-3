package cn.townhc.homebed.controller;

import cn.townhc.homebed.common.ApiResponse;
import cn.townhc.homebed.domain.entity.Bill;
import cn.townhc.homebed.domain.entity.Referral;
import cn.townhc.homebed.domain.enums.DomainEnums.DiagnosisSource;
import cn.townhc.homebed.service.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Tag(name = "病情恶化转诊闭环")
@RestController
@RequestMapping("/api/referrals")
@RequiredArgsConstructor
public class ReferralController {

    private final ReferralService referralService;

    @GetMapping
    public ApiResponse<List<Referral>> list(@RequestParam(required = false) String status,
                                            @RequestParam(required = false) Long patientId,
                                            @RequestParam(required = false) Boolean activeOnly) {
        return ApiResponse.ok(referralService.list(status, patientId, activeOnly));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(referralService.detail(id));
    }

    @Operation(summary = "护士/医生立即标记高危并通知家庭医生、家属、转诊医院")
    @PostMapping("/flag")
    public ApiResponse<Referral> flag(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(referralService.flagCritical(new ReferralService.FlagCommand(
                Long.valueOf(body.get("patientId").toString()),
                body.get("visitId") == null ? null : Long.valueOf(body.get("visitId").toString()),
                body.get("riskEventId") == null ? null : Long.valueOf(body.get("riskEventId").toString()),
                body.get("applicationId") == null ? null : Long.valueOf(body.get("applicationId").toString()),
                (String) body.getOrDefault("symptom", "呼吸困难"),
                (String) body.get("description"),
                (String) body.get("targetHospital"),
                (String) body.get("targetHospitalContact"),
                (String) body.get("evidencePath"))));
    }

    @Operation(summary = "家庭医生远程判断后发起救护车转运（暂停家庭病床/计划、取消后续巡诊、冻结未结算账单）")
    @PostMapping("/{id}/ambulance")
    public ApiResponse<Referral> ambulance(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(referralService.dispatchAmbulance(id,
                (String) body.get("targetHospital"),
                (String) body.getOrDefault("judgeNote", ""),
                (String) body.getOrDefault("ambulanceNote", "")));
    }

    @Operation(summary = "医生远程判断暂不需救护车，就地/门诊处置观察")
    @PostMapping("/{id}/hold")
    public ApiResponse<Referral> hold(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(referralService.holdOnSite(id, body.getOrDefault("judgeNote", "")));
    }

    @Operation(summary = "确认患者送达医院，转诊完成")
    @PostMapping("/{id}/referred")
    public ApiResponse<Referral> referred(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> body) {
        LocalDateTime admittedAt = body != null && body.get("admittedAt") != null
                ? LocalDateTime.parse(body.get("admittedAt").toString()) : null;
        return ApiResponse.ok(referralService.completeReferral(id,
                body == null ? null : (String) body.get("admittedHospital"),
                admittedAt,
                body == null ? "" : (String) body.getOrDefault("note", "")));
    }

    @Operation(summary = "医院回传或家属补充入院诊断，确定家庭病床费用截至时间")
    @PostMapping("/{id}/diagnosis")
    public ApiResponse<Referral> diagnosis(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        LocalDateTime admittedAt = body.get("admittedAt") == null
                ? null : LocalDateTime.parse(body.get("admittedAt").toString());
        DiagnosisSource source = body.get("source") == null ? null
                : DiagnosisSource.valueOf(body.get("source").toString());
        return ApiResponse.ok(referralService.receiveDiagnosis(id,
                new ReferralService.DiagnosisCommand(source,
                        (String) body.get("admissionDiagnosis"),
                        admittedAt,
                        (String) body.getOrDefault("note", ""))));
    }

    @Operation(summary = "登记患者出院")
    @PostMapping("/{id}/discharge")
    public ApiResponse<Referral> discharge(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        LocalDate date = body.get("dischargeDate") == null ? null
                : LocalDate.parse(body.get("dischargeDate").toString());
        return ApiResponse.ok(referralService.discharge(id, date,
                (String) body.getOrDefault("dischargeNote", "")));
    }

    @Operation(summary = "出院后家庭医生决定恢复家庭病床（恢复计划、解冻账单）")
    @PostMapping("/{id}/resume")
    public ApiResponse<Referral> resume(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        LocalDate date = body.get("resumeVisitDate") == null ? null
                : LocalDate.parse(body.get("resumeVisitDate").toString());
        return ApiResponse.ok(referralService.resumeBed(id, date,
                (String) body.getOrDefault("decisionNote", "")));
    }

    @Operation(summary = "出院后家庭医生决定撤床，不再恢复")
    @PostMapping("/{id}/withdraw")
    public ApiResponse<Referral> withdraw(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(referralService.withdrawBed(id, body.getOrDefault("decisionNote", "")));
    }

    @Operation(summary = "财务/医保审核转诊冻结账单（按费用截止剔除住院后明细后放行）")
    @PostMapping("/{id}/bills/{billId}/review")
    public ApiResponse<Bill> reviewBill(@PathVariable Long id, @PathVariable Long billId,
                                        @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(referralService.reviewFrozenBill(id, billId,
                Boolean.TRUE.equals(body.get("approved")),
                (String) body.getOrDefault("auditNote", "")));
    }
}
