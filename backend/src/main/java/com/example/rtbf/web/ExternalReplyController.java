package com.example.rtbf.web;

import com.example.rtbf.service.ExternalReplyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 对外答复管理：独立状态与版本；全部影响项办结后才生成新版本，旧答复按日期可查回。
 */
@Tag(name = "对外答复（版本化）")
@RestController
@RequestMapping("/api/replies")
public class ExternalReplyController {

    private final ExternalReplyService replies;

    public ExternalReplyController(ExternalReplyService replies) {
        this.replies = replies;
    }

    public record GenerateRequest(@NotBlank String title, String bodyText) {
    }

    @Operation(summary = "生成对外说明（前置：异议已裁定且全部影响项有明确处理结果）")
    @PostMapping("/disputes/{id}")
    public Map<String, Object> generate(@PathVariable long id, @Valid @RequestBody GenerateRequest req,
                                        Principal principal) {
        return replies.generateReply(id, req.title(), req.bodyText(), principal);
    }

    @Operation(summary = "答复版本清单（可按异议或主体过滤）")
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(required = false) Long disputeId,
                                          @RequestParam(required = false) String subjectHash) {
        return replies.listReplies(disputeId, subjectHash);
    }

    @Operation(summary = "按日期查回原答复（含已被取代的历史版本）")
    @GetMapping("/by-date")
    public Map<String, Object> byDate(@RequestParam @NotBlank String subjectHash,
                                      @RequestParam(required = false)
                                      @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return replies.getReplyByDate(subjectHash, date);
    }
}
