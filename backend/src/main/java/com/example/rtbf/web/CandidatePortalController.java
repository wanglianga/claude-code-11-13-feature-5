package com.example.rtbf.web;

import com.example.rtbf.service.CandidatePortalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 候选人查询门户：免登录，凭限时凭证访问。
 * 不回显敏感明文、不透露其他主体信息；凭证过期或因归属修订失效后须重新核验身份。
 */
@Tag(name = "候选人查询门户（限时凭证）")
@RestController
@RequestMapping("/api/public/portal")
public class CandidatePortalController {

    private final CandidatePortalService portal;

    public CandidatePortalController(CandidatePortalService portal) {
        this.portal = portal;
    }

    public record IssueTokenRequest(@NotBlank String accountId, @NotBlank String idType,
                                    @NotBlank String idValue, String purpose) {
    }

    public record PortalDisputeRequest(@NotBlank String tokenRef, @NotBlank String kind,
                                       String disputedAccount, String wrongSubjectHash, String clue) {
    }

    public record PortalClueRequest(@NotBlank String tokenRef, String clueType,
                                    @NotBlank String content) {
    }

    @Operation(summary = "凭账号+标识符知识校验，签发限时查询凭证")
    @PostMapping("/tokens")
    public Map<String, Object> issueToken(@Valid @RequestBody IssueTokenRequest req) {
        return portal.issueToken(new CandidatePortalService.TokenIssueRequest(
            req.accountId(), req.idType(), req.idValue(), req.purpose()), null);
    }

    @Operation(summary = "限时查看隐藏敏感信息的办理说明（不回显明文）")
    @GetMapping("/instruction")
    public Map<String, Object> instruction(@RequestParam @NotBlank String tokenRef) {
        return portal.viewInstruction(tokenRef);
    }

    @Operation(summary = "提交身份异议/账号线索（重复异议自动合并补证）")
    @PostMapping("/disputes")
    public Map<String, Object> submitDispute(@Valid @RequestBody PortalDisputeRequest req) {
        return portal.submitDispute(req.tokenRef(), req.kind(), req.disputedAccount(),
                                    req.wrongSubjectHash(), req.clue());
    }

    @Operation(summary = "补交线索或证明材料（证据版本递增）")
    @PostMapping("/disputes/{id}/clues")
    public Map<String, Object> submitClue(@PathVariable long id, @Valid @RequestBody PortalClueRequest req) {
        return portal.submitClue(req.tokenRef(), id, req.clueType(), req.content());
    }

    @Operation(summary = "办理追踪：异议状态、证据版本、答复版本（仅限本凭证主体）")
    @GetMapping("/track")
    public Map<String, Object> track(@RequestParam @NotBlank String tokenRef) {
        return portal.track(tokenRef);
    }
}
