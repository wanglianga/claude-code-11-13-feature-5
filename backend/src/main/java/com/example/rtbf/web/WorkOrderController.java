package com.example.rtbf.web;

import com.example.rtbf.service.WorkOrderService;
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

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Tag(name = "注销工单与删除作业")
@RestController
@RequestMapping("/api")
public class WorkOrderController {

    private final WorkOrderService tickets;

    public WorkOrderController(WorkOrderService tickets) {
        this.tickets = tickets;
    }

    public record CreateRequest(@NotBlank String subjectHash, @NotBlank String channel, String sourceRef) {
    }

    public record GapConfirmRequest(boolean inScope) {
    }

    public record CancelRequest(@NotBlank String reason) {
    }

    public record ReturnRequest(@NotBlank String reason) {
    }

    public record ReceiptRequest(@NotBlank String noticeRef, @NotBlank String receiptRef) {
    }

    public record ResolveIssueRequest(@NotBlank String note) {
    }

    public record RetrainRequest(@NotBlank String decision, String performanceBefore,
                                 String performanceAfter, @NotBlank String justification) {
    }

    public record SupplementRequest(@NotBlank String assetCode, @NotBlank String reason) {
    }

    @Operation(summary = "创建注销工单（主体哈希+渠道幂等，重复提交并单）")
    @PostMapping("/tickets")
    public Map<String, Object> create(@Valid @RequestBody CreateRequest req, Principal principal) {
        return tickets.createTicket(
            new WorkOrderService.CreateTicketRequest(req.subjectHash(), req.channel(), req.sourceRef()),
            principal);
    }

    @Operation(summary = "工单列表（可按状态过滤）")
    @GetMapping("/tickets")
    public List<Map<String, Object>> list(@RequestParam(required = false) String status) {
        return tickets.listTickets(status);
    }

    @Operation(summary = "工单详情：快照、处置、对账、模型卡、通知、增补、审计时间线")
    @GetMapping("/tickets/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return tickets.getTicket(id);
    }

    @Operation(summary = "沿血缘实时遍历并冻结不可变枚举快照")
    @PostMapping("/tickets/{id}/freeze-enumeration")
    public Map<String, Object> freeze(@PathVariable long id, Principal principal) {
        return tickets.freezeEnumeration(id, principal);
    }

    @Operation(summary = "断链点人工确认（纳入/排除，枚举清单条目不增删）")
    @PostMapping("/tickets/{id}/gaps/{gapId}/confirm")
    public Map<String, Object> confirmGap(@PathVariable long id, @PathVariable long gapId,
                                          @RequestBody GapConfirmRequest req, Principal principal) {
        tickets.confirmGapItem(id, gapId, req.inScope(), principal);
        return Map.of("ticketId", id, "gapId", gapId, "inScope", req.inScope());
    }

    @Operation(summary = "执行前影响面预览，进入可撤回延迟窗口")
    @PostMapping("/tickets/{id}/preview")
    public Map<String, Object> preview(@PathVariable long id, Principal principal) {
        return tickets.preview(id, principal);
    }

    @Operation(summary = "候选人撤回注销 / 延迟窗口内撤回（数据不动，工单作废）")
    @PostMapping("/tickets/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id, @Valid @RequestBody CancelRequest req,
                                      Principal principal) {
        tickets.cancel(id, req.reason(), principal);
        return Map.of("ticketId", id, "status", "CANCELLED");
    }

    @Operation(summary = "退回当前处置节点（主体与冻结结论保留）")
    @PostMapping("/tickets/{id}/return")
    public Map<String, Object> doReturn(@PathVariable long id, @Valid @RequestBody ReturnRequest req,
                                        Principal principal) {
        tickets.returnTicket(id, req.reason(), principal);
        return Map.of("ticketId", id, "status", "RETURNED");
    }

    @Operation(summary = "续办退回工单")
    @PostMapping("/tickets/{id}/resume")
    public Map<String, Object> resume(@PathVariable long id, Principal principal) {
        tickets.resume(id, principal);
        return Map.of("ticketId", id, "resumed", true);
    }

    @Operation(summary = "执行删除作业（工单+存储位置幂等，可安全重放）")
    @PostMapping("/tickets/{id}/execute")
    public Map<String, Object> execute(@PathVariable long id, Principal principal) {
        return tickets.execute(id, principal);
    }

    @Operation(summary = "保留期到期重放（仅处理到期的 SCHEDULED 位置）")
    @PostMapping("/tickets/{id}/retry-retention")
    public Map<String, Object> retryRetention(@PathVariable long id, Principal principal) {
        return tickets.retryExpiredRetention(id, principal);
    }

    @Operation(summary = "登记第三方销毁回执（回执未到只能挂起）")
    @PostMapping("/tickets/{id}/receipts")
    public Map<String, Object> receipt(@PathVariable long id, @Valid @RequestBody ReceiptRequest req,
                                       Principal principal) {
        return tickets.registerReceipt(id, req.noticeRef(), req.receiptRef(), principal);
    }

    @Operation(summary = "逐位置对账；对不上的位置单列复核，阻止关单")
    @PostMapping("/tickets/{id}/reconcile")
    public Map<String, Object> reconcile(@PathVariable long id, Principal principal) {
        return tickets.reconcile(id, principal);
    }

    @Operation(summary = "合规复核解决对账问题")
    @PostMapping("/issues/{issueId}/resolve")
    public Map<String, Object> resolveIssue(@PathVariable long issueId,
                                            @Valid @RequestBody ResolveIssueRequest req, Principal principal) {
        tickets.resolveIssue(issueId, req.note(), principal);
        return Map.of("issueId", issueId, "status", "RESOLVED");
    }

    @Operation(summary = "模型卡重训决策（参数不可回退，效果变化与物理删除分开展示）")
    @PostMapping("/model-cards/{id}/retrain-decision")
    public Map<String, Object> retrain(@PathVariable long id, @Valid @RequestBody RetrainRequest req,
                                       Principal principal) {
        tickets.decideRetrain(id, req.decision(), req.performanceBefore(),
                              req.performanceAfter(), req.justification(), principal);
        return Map.of("modelCardId", id, "decision", req.decision());
    }

    @Operation(summary = "事后补登记资产 → 生成增补工单（原快照不可改写）")
    @PostMapping("/tickets/{id}/supplements")
    public Map<String, Object> supplement(@PathVariable long id, @Valid @RequestBody SupplementRequest req,
                                          Principal principal) {
        return tickets.createSupplement(id, req.assetCode(), req.reason(), principal);
    }

    @Operation(summary = "执行增补工单")
    @PostMapping("/supplements/{id}/execute")
    public Map<String, Object> executeSupplement(@PathVariable long id, Principal principal) {
        return tickets.executeSupplement(id, principal);
    }

    @Operation(summary = "生成证据包（快照+处置+差额+模型卡+对账索引，哈希封存）")
    @PostMapping("/tickets/{id}/evidence-package")
    public Map<String, Object> evidence(@PathVariable long id, Principal principal) {
        return tickets.buildEvidencePackage(id, principal);
    }

    @Operation(summary = "证据包索引列表")
    @GetMapping("/tickets/{id}/evidence-package")
    public List<Map<String, Object>> evidenceList(@PathVariable long id) {
        return tickets.listEvidence(id);
    }
}
