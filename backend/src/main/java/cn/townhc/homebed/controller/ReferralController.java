package cn.townhc.homebed.controller;

import cn.townhc.homebed.common.ApiResponse;
import cn.townhc.homebed.domain.entity.Referral;
import cn.townhc.homebed.domain.entity.ReferralNotification;
import cn.townhc.homebed.domain.enums.DomainEnums.ReferralDecision;
import cn.townhc.homebed.domain.enums.DomainEnums.ResumeDecision;
import cn.townhc.homebed.service.ReferralService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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
                                            @RequestParam(required = false) Long patientId) {
        return ApiResponse.ok(referralService.list(status, patientId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.ok(referralService.detail(id));
    }

    @Operation(summary = "标记病情恶化高危，立即通知家庭医生/家属/转诊医院")
    @PostMapping("/mark")
    public ApiResponse<Referral> mark(@RequestBody Map<String, Object> body) {
        return ApiResponse.ok(referralService.mark(
                body.get("visitId") == null ? null : Long.valueOf(body.get("visitId").toString()),
                body.get("patientId") == null ? null : Long.valueOf(body.get("patientId").toString()),
                (String) body.get("symptom"),
                (String) body.get("description"),
                (String) body.get("targetHospital"),
                (String) body.get("hospitalPhone"),
                (String) body.get("evidencePath")));
    }

    @Operation(summary = "医生远程判断：发起救护车转运（暂停服务+未结算待审核）或继续观察")
    @PostMapping("/{id}/decide")
    public ApiResponse<Referral> decide(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(referralService.decide(id,
                ReferralDecision.valueOf(body.get("decision")),
                body.get("note"), body.get("ambulanceNote")));
    }

    @Operation(summary = "登记医院回传（入院诊断+入院时间），确定费用截至时间")
    @PostMapping("/{id}/hospital-feedback")
    public ApiResponse<Referral> hospitalFeedback(@PathVariable Long id,
                                                  @RequestBody Map<String, String> body) {
        return ApiResponse.ok(referralService.hospitalFeedback(id,
                body.get("admissionDiagnosis"), parseTime(body.get("admittedAt"))));
    }

    @Operation(summary = "家属补充住院结果（入院诊断+入院时间），确定费用截至时间")
    @PostMapping("/{id}/family-feedback")
    public ApiResponse<Referral> familyFeedback(@PathVariable Long id,
                                                @RequestBody Map<String, String> body) {
        return ApiResponse.ok(referralService.familyFeedback(id,
                body.get("admissionDiagnosis"), parseTime(body.get("admittedAt")), body.get("note")));
    }

    @Operation(summary = "出院登记")
    @PostMapping("/{id}/discharge")
    public ApiResponse<Referral> discharge(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return ApiResponse.ok(referralService.discharge(id,
                parseTime(body.get("dischargedAt")), body.get("note")));
    }

    @Operation(summary = "出院后医生决定：恢复家庭病床 / 终止建床")
    @PostMapping("/{id}/resume-decision")
    public ApiResponse<Referral> resumeDecision(@PathVariable Long id,
                                                @RequestBody Map<String, String> body) {
        return ApiResponse.ok(referralService.resumeDecision(id,
                ResumeDecision.valueOf(body.get("decision")), body.get("note")));
    }

    @Operation(summary = "我的转诊通知")
    @GetMapping("/notifications/mine")
    public ApiResponse<List<ReferralNotification>> myNotifications() {
        return ApiResponse.ok(referralService.myNotifications());
    }

    @PostMapping("/notifications/{id}/read")
    public ApiResponse<ReferralNotification> readNotification(@PathVariable Long id) {
        return ApiResponse.ok(referralService.readNotification(id));
    }

    private LocalDateTime parseTime(String s) {
        return (s == null || s.isBlank()) ? null : LocalDateTime.parse(s);
    }
}
