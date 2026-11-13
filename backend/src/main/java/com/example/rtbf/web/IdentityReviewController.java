package com.example.rtbf.web;

import com.example.rtbf.service.AttributionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 身份错配复核工作台（内部，需登录）。
 * 客服（operator）核验证据、合规专员（compliance）裁定；关系/异议/答复三套状态独立维护。
 */
@Tag(name = "身份错配复核")
@RestController
@RequestMapping("/api/identity")
public class IdentityReviewController {

    private final AttributionService attribution;

    public IdentityReviewController(AttributionService attribution) {
        this.attribution = attribution;
    }

    public record DisputeRequest(@NotBlank String subjectHash, @NotBlank String applicantAccount,
                                 String disputedAccount, @NotBlank String kind, String clue) {
    }

    public record EvidenceRequest(@NotBlank String clueType, @NotBlank String content) {
    }

    public record ManualProofRequest(@NotNull Boolean sufficient, String note) {
    }

    public record AgentVerifyRequest(@NotNull Boolean accepted, String note) {
    }

    public record AdjudicateRequest(@NotBlank String decision, @NotNull Integer baseEvidenceVersion,
                                    @NotBlank String rationale) {
    }

    public record NoteRequest(String note) {
    }

    public record IncidentRequest(@NotNull Long revisionId) {
    }

    @Operation(summary = "扫描账号关系：手机号同号→待核验；证件指纹冲突→阻断归并")
    @PostMapping("/relations/scan")
    public Map<String, Object> scanRelations(Principal principal) {
        return attribution.scanRelations(principal);
    }

    @Operation(summary = "账号关系清单（可按状态过滤）")
    @GetMapping("/relations")
    public List<Map<String, Object>> relations(@RequestParam(required = false) String status) {
        return attribution.listRelations(status);
    }

    @Operation(summary = "账号归属判定版本链（只追加，旧结论不改写）")
    @GetMapping("/attributions")
    public List<Map<String, Object>> attributions(@RequestParam(required = false) String accountId) {
        return attribution.listAttribution(accountId);
    }

    @Operation(summary = "身份异议清单（可按状态过滤）")
    @GetMapping("/disputes")
    public List<Map<String, Object>> disputes(@RequestParam(required = false) String status) {
        return attribution.listDisputes(status);
    }

    @Operation(summary = "异议详情：证据、修订版本链、影响项、答复")
    @GetMapping("/disputes/{id}")
    public Map<String, Object> disputeDetail(@PathVariable long id) {
        return attribution.getDispute(id);
    }

    @Operation(summary = "代申请人登记身份异议（门户提交与本入口共用合并补证逻辑）")
    @PostMapping("/disputes")
    public Map<String, Object> createDispute(@Valid @RequestBody DisputeRequest req, Principal principal) {
        return attribution.submitDispute(req.subjectHash(), req.applicantAccount(),
            req.disputedAccount(), req.kind(), req.clue(), principal);
    }

    @Operation(summary = "补交线索/证明（证据版本递增）")
    @PostMapping("/disputes/{id}/evidence")
    public Map<String, Object> addEvidence(@PathVariable long id, @Valid @RequestBody EvidenceRequest req,
                                           Principal principal) {
        return attribution.addEvidence(id, req.clueType(), req.content(), principal);
    }

    @Operation(summary = "联系方式失效：转人工证明通道")
    @PostMapping("/disputes/{id}/contact-unreachable")
    public Map<String, Object> unreachable(@PathVariable long id, Principal principal) {
        return attribution.markContactUnreachable(id, principal);
    }

    @Operation(summary = "审核人工证明：不足则保持待核验，不透露其他主体信息")
    @PostMapping("/disputes/{id}/manual-proof")
    public Map<String, Object> manualProof(@PathVariable long id, @Valid @RequestBody ManualProofRequest req,
                                           Principal principal) {
        return attribution.reviewManualProof(id, req.sufficient(), req.note(), principal);
    }

    @Operation(summary = "客服核验证据：通过则升级合规裁定，不通过退回待核验")
    @PostMapping("/disputes/{id}/agent-verify")
    public Map<String, Object> agentVerify(@PathVariable long id, @Valid @RequestBody AgentVerifyRequest req,
                                           Principal principal) {
        return attribution.agentVerify(id, req.accepted(), req.note(), principal);
    }

    @Operation(summary = "合规裁定：仅接受基于当前证据版本的结论（并发相反结论之一被拒）")
    @PostMapping("/disputes/{id}/adjudicate")
    public Map<String, Object> adjudicate(@PathVariable long id, @Valid @RequestBody AdjudicateRequest req,
                                          Principal principal) {
        return attribution.adjudicate(id, req.decision(), req.baseEvidenceVersion(),
                                      req.rationale(), principal);
    }

    @Operation(summary = "修订影响项清单")
    @GetMapping("/impact-items")
    public List<Map<String, Object>> impacts(@RequestParam(required = false) Long revisionId) {
        return attribution.listImpactItems(revisionId);
    }

    @Operation(summary = "拆开共享资料的错误主体引用（资料仍被合法引用则保留）")
    @PostMapping("/impact-items/{id}/split")
    public Map<String, Object> split(@PathVariable long id, Principal principal) {
        return attribution.splitSharedReference(id, principal);
    }

    @Operation(summary = "执行补充删除（尚未清除的错挂位置）")
    @PostMapping("/impact-items/{id}/supplemental-delete")
    public Map<String, Object> supplementalDelete(@PathVariable long id, Principal principal) {
        return attribution.executeSupplementalDelete(id, principal);
    }

    @Operation(summary = "登记不可恢复事实（已物理删除位置）")
    @PostMapping("/impact-items/{id}/irrecoverable")
    public Map<String, Object> irrecoverable(@PathVariable long id,
                                             @RequestBody(required = false) NoteRequest req,
                                             Principal principal) {
        return attribution.recordIrrecoverable(id, req == null ? null : req.note(), principal);
    }

    @Operation(summary = "人工确认办结影响项")
    @PostMapping("/impact-items/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable long id, @RequestBody(required = false) NoteRequest req,
                                       Principal principal) {
        return attribution.resolveImpact(id, req == null ? null : req.note(), principal);
    }

    @Operation(summary = "作业遇裁定：未清除位置暂停、已处理位置登记事故（暂停≠撤回成功）")
    @PostMapping("/tickets/{ticketId}/incidents")
    public Map<String, Object> incident(@PathVariable long ticketId, @Valid @RequestBody IncidentRequest req,
                                        Principal principal) {
        return attribution.raiseExecutionIncident(ticketId, req.revisionId(), principal);
    }

    @Operation(summary = "事故/暂停记录清单")
    @GetMapping("/incidents")
    public List<Map<String, Object>> incidents(@RequestParam(required = false) Long ticketId) {
        return attribution.listIncidents(ticketId);
    }

    @Operation(summary = "暂停位置按最新证据版本重新核对后恢复")
    @PostMapping("/dispositions/{id}/resume-paused")
    public Map<String, Object> resumePaused(@PathVariable long id, Principal principal) {
        return attribution.resumePausedPosition(id, principal);
    }
}
