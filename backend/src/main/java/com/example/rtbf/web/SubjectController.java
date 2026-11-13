package com.example.rtbf.web;

import com.example.rtbf.service.SubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Tag(name = "主体归并")
@RestController
@RequestMapping("/api")
public class SubjectController {

    private final SubjectService subjects;

    public SubjectController(SubjectService subjects) {
        this.subjects = subjects;
    }

    public record DecideRequest(@NotNull Boolean accept) {
    }

    public record RejectRequest(String reason) {
    }

    @Operation(summary = "按手机号/邮箱/证件号哈希扫描并归并主体（姓名不参与）")
    @PostMapping("/subjects/scan")
    public List<Map<String, Object>> scan(Principal principal) {
        return subjects.scanAndMerge(principal);
    }

    @Operation(summary = "候选主体清单")
    @GetMapping("/subjects")
    public List<Map<String, Object>> list() {
        return subjects.listSubjects();
    }

    @Operation(summary = "主体详情（含逐条标识符匹配项）")
    @GetMapping("/subjects/{subjectHash}")
    public Map<String, Object> detail(@PathVariable String subjectHash) {
        return subjects.getSubject(subjectHash);
    }

    @Operation(summary = "合规专员逐条接受/驳回标识符匹配")
    @PostMapping("/identifier-matches/{matchId}/decide")
    public Map<String, Object> decide(@PathVariable long matchId,
                                      @RequestBody DecideRequest req, Principal principal) {
        subjects.decideMatch(matchId, req.accept(), principal);
        return Map.of("matchId", matchId, "accepted", req.accept());
    }

    @Operation(summary = "确认主体（全部匹配项已逐条决定后）")
    @PostMapping("/subjects/{subjectHash}/confirm")
    public Map<String, Object> confirm(@PathVariable String subjectHash, Principal principal) {
        subjects.confirmSubject(subjectHash, principal);
        return Map.of("subjectHash", subjectHash, "status", "CONFIRMED");
    }

    @Operation(summary = "驳回主体候选")
    @PostMapping("/subjects/{subjectHash}/reject")
    public Map<String, Object> reject(@PathVariable String subjectHash,
                                      @RequestBody(required = false) RejectRequest req, Principal principal) {
        subjects.rejectSubject(subjectHash, req == null ? null : req.reason(), principal);
        return Map.of("subjectHash", subjectHash, "status", "REJECTED");
    }
}
