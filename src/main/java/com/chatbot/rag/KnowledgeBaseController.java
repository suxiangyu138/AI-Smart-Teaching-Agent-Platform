package com.chatbot.rag;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.rag.document.MathPixClient;
import com.chatbot.rag.document.ScanOcrClient;
import com.chatbot.rag.model.KnowledgeBaseStats;
import com.chatbot.rag.model.SearchResult;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库管理 REST API
 *
 * @author suxiangyu
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeBaseController {

    private final RagService ragService;
    private final ScanOcrClient ocrClient;
    private final MathPixClient mathPixClient;

    public KnowledgeBaseController(RagService ragService, ScanOcrClient ocrClient,
                                    MathPixClient mathPixClient) {
        this.ragService = ragService;
        this.ocrClient = ocrClient;
        this.mathPixClient = mathPixClient;
    }

    /** OCR 服务诊断 */
    @GetMapping("/ocr-status")
    public Map<String, Object> getOcrStatus() {
        boolean paddleAvailable = ocrClient.isAvailable();
        boolean mathPixAvailable = mathPixClient.isAvailable();
        return Map.of("paddleOcr", paddleAvailable,
                "mathPix", mathPixAvailable,
                "message", mathPixAvailable ? "MathPix 就绪"
                        : paddleAvailable ? "PaddleOCR 就绪" : "无可用 OCR");
    }

    /** 配置 MathPix API Key */
    @PostMapping("/mathpix-config")
    public Map<String, Object> configMathPix(@RequestBody Map<String, String> body) {
        String appId = body.get("appId");
        String appKey = body.get("appKey");
        if (appId == null || appKey == null || appId.isBlank() || appKey.isBlank()) {
            return Map.of("success", false, "error", "缺少 appId 或 appKey");
        }
        mathPixClient.configure(appId, appKey);
        return Map.of("success", true, "message", "MathPix 已配置");
    }

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/stats")
    public KnowledgeBaseStats getStats() {
        return ragService.getStats();
    }

    /**
     * 检索调试接口：返回 top-K 分块及得分（不调用 LLM，用于评估检索质量）
     * body: { query, stage?, allowUniversityExtend?, topK? }
     */
    @PostMapping("/search")
    public Map<String, Object> searchDebug(@RequestBody Map<String, Object> body) {
        String query = body.get("query") == null ? "" : body.get("query").toString();
        if (query.isBlank()) {
            return error("缺少 query 参数");
        }
        String stage = body.get("stage") == null
                ? UnifiedChatRequest.STAGE_SENIOR : body.get("stage").toString();
        boolean allowExtend = Boolean.TRUE.equals(body.get("allowUniversityExtend"));
        int topK = body.get("topK") instanceof Number n
                ? Math.max(1, Math.min(20, n.intValue())) : 5;

        List<SearchResult> results = ragService.searchDebug(query, stage, allowExtend, topK);
        return Map.of("success", true, "query", query, "stage", stage,
                "count", results.size(), "results",
                results.stream().map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("score", String.format("%.1f%%", r.getScore() * 100));
                    m.put("sourceFile", r.getSourceFile());
                    m.put("chapter", r.getChunk().getChapterTitle() != null
                            ? r.getChunk().getChapterTitle() : "");
                    m.put("knowledgePoint", r.getChunk().getKnowledgePoint() != null
                            ? r.getChunk().getKnowledgePoint() : "");
                    m.put("snippet", r.getSnippet());
                    return m;
                }).toList());
    }

    /**
     * 获取已索引文件列表
     */
    @GetMapping("/files")
    public List<Map<String, Object>> listFiles() {
        return ragService.getStats().getTotalDocuments() > 0
                ? List.of(Map.of("files", ragService.getStats()))
                : List.of();
    }

    /**
     * 索引单个 PDF 文件
     */
    @PostMapping("/index/pdf")
    public Map<String, Object> indexPdf(@RequestBody Map<String, String> body) {
        String filePath = body.get("filePath");
        if (filePath == null || filePath.isBlank()) {
            return error("缺少 filePath 参数");
        }
        Path pdfPath = Path.of(filePath);
        if (!Files.exists(pdfPath)) {
            return error("文件不存在: " + filePath);
        }
        // 优先用显式指定，否则从路径自动检测学段
        String stage = body.get("stage");
        if (stage == null) {
            stage = detectStageFromPath(pdfPath);
        }

        try {
            int count = ragService.indexPdf(pdfPath, stage,
                    body.get("apiKey"), body.get("baseUrl"));
            return Map.of("success", true,
                    "fileName", pdfPath.getFileName().toString(),
                    "stage", stage,
                    "chunkCount", count,
                    "message", "索引成功，共 " + count + " 个切片");
        } catch (Exception e) {
            return error("索引失败: " + e.getMessage());
        }
    }

    /**
     * 上传 PDF 并立即索引（multipart/form-data）
     * <p>
     * 文件保存到知识库目录，学段可选（留空时从文件名自动识别）。
     * apiKey/baseUrl 用于云端向量嵌入，留空则使用本地嵌入。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "stage", defaultValue = "") String stage,
            @RequestParam(value = "apiKey", defaultValue = "") String apiKey,
            @RequestParam(value = "baseUrl", defaultValue = "") String baseUrl) {
        if (file == null || file.isEmpty()) {
            return error("未选择文件");
        }
        String original = file.getOriginalFilename();
        if (original == null || !original.toLowerCase().endsWith(".pdf")) {
            return error("仅支持 PDF 文件");
        }

        try {
            Path kbDir = ragService.getKbDir();
            // 清理文件名中的路径分隔符与非法字符，防止路径穿越
            String safeName = original.replaceAll("[\\\\/:*?\"<>|]", "_");
            Path target = kbDir.resolve(safeName);
            if (Files.exists(target)) {
                int dot = safeName.lastIndexOf('.');
                String stem = dot > 0 ? safeName.substring(0, dot) : safeName;
                target = kbDir.resolve(stem + "_" + System.currentTimeMillis() + ".pdf");
            }
            file.transferTo(target);

            if (stage == null || stage.isBlank()) {
                stage = detectStageFromPath(target);
            }

            int count = ragService.indexPdf(target, stage,
                    apiKey.isBlank() ? null : apiKey,
                    baseUrl.isBlank() ? null : baseUrl);
            return Map.of("success", true,
                    "fileName", target.getFileName().toString(),
                    "stage", stage,
                    "chunkCount", count,
                    "message", "上传并索引成功，共 " + count + " 个切片");
        } catch (Exception e) {
            return error("上传或索引失败: " + e.getMessage());
        }
    }

    /** 上传文件过大（multipart 解析在进入控制器前失败） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Map<String, Object> handleUploadTooLarge() {
        return error("文件过大，单个 PDF 最大 50MB");
    }

    /**
     * 批量索引目录下所有 PDF
     */
    @PostMapping("/index/directory")
    public Map<String, Object> indexDirectory(@RequestBody Map<String, String> body) {
        String dirPath = body.get("dirPath");
        if (dirPath == null || dirPath.isBlank()) {
            return error("缺少 dirPath 参数");
        }
        Path path = Path.of(dirPath);
        if (!Files.isDirectory(path)) {
            return error("目录不存在: " + dirPath);
        }
        // 学段留空 → null，由 RagService 按文件名逐文件自动识别
        String stage = body.get("stage");
        if (stage != null && stage.isBlank()) {
            stage = null;
        }

        try {
            Map<String, Integer> stats = ragService.indexDirectory(
                    path, stage, body.get("apiKey"), body.get("baseUrl"));
            long successCount = stats.values().stream().filter(v -> v > 0).count();
            long failCount = stats.values().stream().filter(v -> v < 0).count();
            return Map.of("success", true,
                    "directory", dirPath,
                    "autoStage", stage != null ? stage : "按文件名自动识别",
                    "totalFiles", stats.size(),
                    "successFiles", successCount,
                    "failFiles", failCount,
                    "details", stats);
        } catch (Exception e) {
            System.err.println("[RAG] 批量索引异常:");
            e.printStackTrace();
            return error("批量索引失败: " + (e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** 从路径自动检测学段（支持学段名 + 学科名） */
    @SuppressWarnings("all")
    private String detectStageFromPath(Path path) {
        String lower = path.toString().toLowerCase();

        // 大学学科名
        if (lower.contains("university") || lower.contains("大学")
                || lower.contains("高等") || lower.contains("微积分")
                || lower.contains("线性代数") || lower.contains("离散数学")
                || lower.contains("概率论") || lower.contains("数理统计")
                || lower.contains("数学建模") || lower.contains("数学竞赛")
                || lower.contains("数学分析") || lower.contains("考研数学")
                || lower.contains("组合数学") || lower.contains("图论")
                || lower.contains("数值分析") || lower.contains("应用数学")
                || lower.contains("复变函数") || lower.contains("常微分")
                || lower.contains("抽象代数") || lower.contains("泛函分析")) {
            return UnifiedChatRequest.STAGE_UNIVERSITY;
        }
        if (lower.contains("senior") || lower.contains("高中")
                || lower.contains("高考")) {
            return UnifiedChatRequest.STAGE_SENIOR;
        }
        if (lower.contains("primary") || lower.contains("小学")) {
            return UnifiedChatRequest.STAGE_PRIMARY;
        }
        if (lower.contains("junior") || lower.contains("初中")
                || lower.contains("中考")) {
            return UnifiedChatRequest.STAGE_JUNIOR;
        }
        // 默认：含"数学"但未匹配到学段 → 高中
        if (lower.contains("数学")) {
            return UnifiedChatRequest.STAGE_SENIOR;
        }
        return UnifiedChatRequest.STAGE_JUNIOR;
    }

    /**
     * 获取知识库目录下的 PDF 文件列表（用于前端选择文件）
     */
    @GetMapping("/pdf-list")
    public List<Map<String, Object>> listPdfFiles() {
        Path kbDir = ragService.getKbDir();
        try {
            if (!Files.isDirectory(kbDir)) {
                return List.of();
            }
            try (var files = Files.list(kbDir)) {
                return files
                        .filter(f -> f.toString().toLowerCase().endsWith(".pdf"))
                        .map(f -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("name", f.getFileName().toString());
                            m.put("path", f.toAbsolutePath().toString());
                            try {
                                m.put("size", Files.size(f));
                            } catch (Exception e) {
                                m.put("size", 0);
                            }
                            try {
                                m.put("modified",
                                        Files.getLastModifiedTime(f).toString());
                            } catch (Exception e) {
                                m.put("modified", "");
                            }
                            return m;
                        })
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 批量索引进度 */
    @GetMapping("/progress")
    public Map<String, Object> getProgress() {
        return ragService.getProgress();
    }

    /**
     * 获取知识库目录路径
     */
    @GetMapping("/dir")
    public Map<String, String> getKbDir() {
        return Map.of("directory", ragService.getKbDir().toAbsolutePath().toString());
    }

    /**
     * 获取失败文件清单
     */
    @GetMapping("/failed-files")
    public Map<String, Object> getFailedFiles() {
        Map<String, String> failed = ragService.getFailedFiles();
        return Map.of("count", failed.size(), "files",
                failed.entrySet().stream()
                        .map(e -> Map.of("fileName", e.getKey(), "reason", e.getValue()))
                        .toList());
    }

    /**
     * 清除单个失败记录（允许重试）
     */
    @PostMapping("/clear-failed")
    public Map<String, Object> clearFailedFile(@RequestBody Map<String, String> body) {
        String fileName = body.get("fileName");
        if (fileName == null || fileName.isBlank()) {
            return error("缺少 fileName 参数");
        }
        ragService.clearFailedFile(fileName);
        return Map.of("success", true, "message", "已清除失败记录: " + fileName);
    }

    /**
     * 重置断点缓存（强制全量重建）
     */
    @PostMapping("/reset-checkpoint")
    public Map<String, Object> resetCheckpoint() {
        ragService.resetCheckpoint();
        return Map.of("success", true, "message", "断点已重置，下次索引将全量重建");
    }

    /**
     * 增量索引（仅扫描新增/失败重试的文件）
     */
    @PostMapping("/index/incremental")
    public Map<String, Object> indexIncremental(@RequestBody Map<String, String> body) {
        String dirPath = body.get("dirPath");
        if (dirPath == null || dirPath.isBlank()) {
            return error("缺少 dirPath 参数");
        }
        Path path = Path.of(dirPath);
        if (!Files.isDirectory(path)) {
            return error("目录不存在: " + dirPath);
        }
        // 学段留空 → null，由 RagService 按文件名逐文件自动识别
        String stage = body.get("stage");
        if (stage != null && stage.isBlank()) {
            stage = null;
        }
        try {
            Map<String, Integer> stats = ragService.indexDirectory(
                    path, stage, body.get("apiKey"), body.get("baseUrl"));
            long newCount = stats.values().stream().filter(v -> v > 0).count();
            long skippedCount = stats.values().stream().filter(v -> v == 0).count();
            long failCount = stats.values().stream().filter(v -> v < 0).count();
            return Map.of("success", true, "directory", dirPath,
                    "autoStage", stage != null ? stage : "按文件名自动识别",
                    "newFiles", newCount,
                    "skippedFiles", skippedCount, "failFiles", failCount,
                    "details", stats);
        } catch (Exception e) {
            return error("增量索引失败: " + e.getMessage());
        }
    }

    /**
     * OCR 重试所有失败文件（需 Python OCR 服务运行中）
     */
    @PostMapping("/retry-ocr")
    public Map<String, Object> retryOcr(@RequestBody Map<String, String> body) {
        String dirPath = body.get("dirPath");
        if (dirPath == null || dirPath.isBlank()) {
            return error("缺少 dirPath 参数");
        }
        Path path = Path.of(dirPath);
        if (!Files.isDirectory(path)) {
            return error("目录不存在: " + dirPath);
        }
        // 学段留空 → null，由 RagService 按文件名逐文件自动识别
        String stage = body.get("stage");
        if (stage != null && stage.isBlank()) {
            stage = null;
        }

        // 先清空失败记录，让断点逻辑允许重试
        ragService.resetCheckpoint();

        try {
            Map<String, Integer> stats = ragService.indexDirectory(
                    path, stage, body.get("apiKey"), body.get("baseUrl"));
            long newCount = stats.values().stream().filter(v -> v > 0).count();
            long stillFailed = stats.values().stream().filter(v -> v < 0).count();
            return Map.of("success", true, "directory", dirPath,
                    "newFiles", newCount, "stillFailed", stillFailed,
                    "details", stats);
        } catch (Exception e) {
            return error("OCR 重试失败: " + e.getMessage());
        }
    }

    /**
     * 删除指定文档的索引
     */
    @DeleteMapping("/document")
    public Map<String, Object> removeDocument(@RequestBody Map<String, String> body) {
        String fileName = body.get("fileName");
        if (fileName == null || fileName.isBlank()) {
            return error("缺少 fileName 参数");
        }
        // 可选：同时删除知识库目录内的源 PDF（针对上传的文件）
        if ("true".equalsIgnoreCase(body.get("deleteSource"))) {
            try {
                Path kbDirAbs = ragService.getKbDir().toAbsolutePath().normalize();
                Path target = kbDirAbs.resolve(
                        Path.of(fileName).getFileName().toString()).normalize();
                // 仅允许删除知识库目录内的文件，防止路径穿越
                if (target.getParent() != null && target.getParent().equals(kbDirAbs)
                        && Files.exists(target)) {
                    Files.deleteIfExists(target);
                }
            } catch (IOException e) {
                // 源文件删除失败不影响索引删除
                System.err.println("[RAG] 删除源文件失败: " + e.getMessage());
            }
        }
        ragService.removeDocument(fileName);
        return Map.of("success", true, "message", "已删除索引: " + fileName);
    }

    /**
     * 批量重新标准化公式：检测所有文档中的裸 LaTeX 命令，
     * 包裹为 $$...$$，更新公式元数据。
     */
    @PostMapping("/restandardize")
    public Map<String, Object> restandardizeFormulas() {
        return ragService.restandardizeFormulas();
    }

    /**
     * 获取公式统计信息
     */
    @GetMapping("/formula-stats")
    public Map<String, Object> getFormulaStats() {
        return ragService.getFormulaStats();
    }

    /**
     * 清空全部知识库
     */
    @DeleteMapping("/clear")
    public Map<String, Object> clearAll() {
        ragService.clearAll();
        return Map.of("success", true, "message", "知识库已清空");
    }

    /**
     * RAG 增强聊天接口（复用现有 SSE 流式架构）
     * <p>
     * 与 /api/chat 接口兼容，额外添加 RAG 知识库上下文增强
     */
    @PostMapping(value = "/chat",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ragChat(@RequestBody UnifiedChatRequest req) {
        // RAG 上下文增强
        String userQuery = extractUserQuery(req);
        String ragContext = ragService.buildRagContext(userQuery, req, 4);

        if (!ragContext.isEmpty()) {
            // 将知识库上下文追加到系统提示词中
            // 这里通过添加一条 system 消息来传递上下文
            if (req.getMessages() != null && !req.getMessages().isEmpty()) {
                UnifiedChatRequest.Message sysMsg =
                        new UnifiedChatRequest.Message("system", ragContext);
                req.getMessages().add(0, sysMsg);
            }
        }

        // 委托给常规聊天流程（由 ChatController 处理）
        // 这里直接返回 null，实际由前端通过 /api/chat 调用时拼接
        return null;
    }

    private String extractUserQuery(UnifiedChatRequest req) {
        if (req.getMessages() != null && !req.getMessages().isEmpty()) {
            return req.getMessages().getLast().getContent();
        }
        return "";
    }

    private Map<String, Object> error(String msg) {
        return Map.of("success", false, "error", msg);
    }
}
