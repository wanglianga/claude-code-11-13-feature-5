package com.example.rtbf.web;

import com.example.rtbf.service.LineageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Tag(name = "资产登记与血缘")
@RestController
@RequestMapping("/api")
public class LineageController {

    private final LineageService lineage;

    public LineageController(LineageService lineage) {
        this.lineage = lineage;
    }

    public record RegisterAssetRequest(
            @NotBlank String assetCode,
            @NotBlank String assetName,
            @NotBlank String storageType,
            @NotBlank String targetRef,
            String externalParty,
            Integer retentionDays,
            @NotBlank String dispositionDefault,
            List<String> parentAssetCodes) {
    }

    @Operation(summary = "资产接入登记与血缘注册（同一事务，失败整体回滚）")
    @PostMapping("/assets/register")
    public Map<String, Object> register(@Valid @RequestBody RegisterAssetRequest req, Principal principal) {
        return lineage.registerAsset(new LineageService.AssetRegisterRequest(
            req.assetCode(), req.assetName(), req.storageType(), req.targetRef(),
            req.externalParty(), req.retentionDays(), req.dispositionDefault(),
            req.parentAssetCodes()), principal);
    }

    @Operation(summary = "已登记资产清单")
    @GetMapping("/assets")
    public List<Map<String, Object>> assets() {
        return lineage.listAssets();
    }

    @Operation(summary = "血缘边（含断链标记）")
    @GetMapping("/lineage/edges")
    public List<Map<String, Object>> edges() {
        return lineage.listEdges();
    }

    @Operation(summary = "血缘断链人工确认点")
    @GetMapping("/lineage/gaps")
    public List<Map<String, Object>> gaps() {
        return lineage.listGaps();
    }

    @Operation(summary = "实时血缘全图遍历预览（不落快照）")
    @PostMapping("/lineage/traverse")
    public List<LineageService.TraversedItem> traverse() {
        return lineage.traverse();
    }
}
