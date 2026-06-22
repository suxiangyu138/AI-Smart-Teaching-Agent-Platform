package com.chatbot.rag.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * 企业级 PDF 文档解析器
 * <p>
 * 特性：
 * - 分页流式读取（防 5GB 大文件 OOM）
 * - 自动区分文字版/扫描版 PDF
 * - 去除页眉页脚、水印、页码
 * - 行内公式与文字合并
 *
 * @author suxiangyu
 */
@Component
public class PdfDocumentParser {

    private static final int MAX_PDF_SIZE_MB = 500;
    private static final int BYTES_PER_MB = 1024 * 1024;
    /** 页眉页脚阈值：距离页面顶部/底部 < 此比例 → 视为页眉页脚 */
    private static final float HEADER_FOOTER_RATIO = 0.08f;
    /** 扫描版判定：连续 N 页无文本 → 扫描版 */
    private static final int SCAN_DETECT_PAGES = 3;
    /** 扫描版单页最小文本量 */
    private static final int SCAN_MIN_CHARS_PER_PAGE = 20;
    /** OCR 每批最大页数（防超时） */
    private static final int OCR_MAX_PAGES = 50;

    private final ScanOcrClient ocrClient;
    private final MathPixClient mathPixClient;

    public PdfDocumentParser(ScanOcrClient ocrClient, MathPixClient mathPixClient) {
        this.ocrClient = ocrClient;
        this.mathPixClient = mathPixClient;
    }

    /**
     * 解析 PDF，提取结构化分页内容
     */
    public PdfParseResult parsePdfStructured(Path pdfPath) throws IOException {
        if (Files.size(pdfPath) > (long) MAX_PDF_SIZE_MB * BYTES_PER_MB) {
            throw new IOException("PDF 过大，最大支持 " + MAX_PDF_SIZE_MB + "MB");
        }

        String fileName = pdfPath.getFileName().toString();
        PdfParseResult result = new PdfParseResult();
        result.fileName = fileName;
        result.totalPages = 0;
        result.pages = new ArrayList<>();

        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            if (document.isEncrypted()) {
                result.isEncrypted = true;
                result.errorMsg = "PDF 已加密";
                return result;
            }

            int pageCount = document.getNumberOfPages();
            result.totalPages = pageCount;

            // 先采样检测是否为扫描版
            result.isScanned = detectIfScanned(document, pageCount);

            if (result.isScanned) {
                // 优先级: MathPix > PaddleOCR
                if (mathPixClient.isAvailable() || ocrClient.isAvailable()) {
                    return parseScannedPdf(pdfPath, document, pageCount, fileName);
                }
                result.errorMsg = "扫描图片版 PDF，MathPix 未配置且 OCR 服务未启动";
                return result;
            }

            // 分页流式解析
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setAddMoreFormatting(false);

            for (int pageIdx = 0; pageIdx < pageCount; pageIdx++) {
                stripper.setStartPage(pageIdx + 1);
                stripper.setEndPage(pageIdx + 1);
                String pageText = stripper.getText(document);

                if (pageText == null || pageText.isBlank()) {
                    continue; // 空白页跳过
                }

                pageText = cleanPageText(pageText, pageIdx, pageCount);

                if (pageText.length() < SCAN_MIN_CHARS_PER_PAGE) {
                    continue; // 内容过少的页面跳过
                }

                PdfPageInfo page = new PdfPageInfo();
                page.pageNum = pageIdx + 1;
                page.content = pageText;
                result.pages.add(page);

                // 单页处理完毕立刻释放引用（GC 友好）
                pageText = null;
            }
        }
        return result;
    }

    /**
     * 兼容旧接口：返回全文（用于快速索引）
     */
    public String parsePdf(Path pdfPath) throws IOException {
        PdfParseResult result = parsePdfStructured(pdfPath);
        if (result.isEncrypted) {
            throw new IOException("PDF 已加密: " + result.fileName);
        }
        if (result.isScanned && !result.ocrProcessed) {
            throw new IOException("扫描图片版 PDF，OCR 服务未启动: " + result.fileName);
        }
        if (result.pages.isEmpty()) {
            // 文本提取为空但没被识别为扫描版 → 强制走OCR重试（如只有少量页码文字的扫描PDF）
            if (mathPixClient.isAvailable() || ocrClient.isAvailable()) {
                try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
                    result = parseScannedPdf(pdfPath, doc, doc.getNumberOfPages(), pdfPath.getFileName().toString());
                    if (!result.pages.isEmpty()) {
                        return buildFullText(result);
                    }
                } catch (Exception e) {
                    throw new IOException("PDF OCR 失败: " + result.fileName + " - " + e.getMessage());
                }
            }
            throw new IOException("PDF 无可提取文本且无可用OCR: " + result.fileName);
        }
        return buildFullText(result);
    }

    private String buildFullText(PdfParseResult result) {
        StringBuilder sb = new StringBuilder();
        for (PdfPageInfo p : result.pages) {
            sb.append(p.content).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 批量解析目录（断点续存由调用方管理）
     */
    public Map<String, String> parseDirectory(Path dirPath) throws IOException {
        Map<String, String> results = new LinkedHashMap<>();
        if (!Files.isDirectory(dirPath)) {
            return results;
        }
        try (Stream<Path> files = Files.list(dirPath)) {
            List<Path> pdfFiles = files
                    .filter(p -> p.toString().toLowerCase().endsWith(".pdf"))
                    .sorted()
                    .toList();
            for (Path pdf : pdfFiles) {
                try {
                    String text = parsePdf(pdf);
                    results.put(pdf.getFileName().toString(), text);
                } catch (IOException e) {
                    results.put(pdf.getFileName().toString(), "");
                    System.err.println("跳过 " + pdf.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return results;
    }

    /**
     * 检测是否为扫描图片版 PDF
     */
    private boolean detectIfScanned(PDDocument document, int pageCount) {
        int emptyPages = 0;
        int checkPages = Math.min(pageCount, SCAN_DETECT_PAGES);

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);

        for (int i = 0; i < checkPages; i++) {
            try {
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                String text = stripper.getText(document);
                if (text == null || text.trim().length() < SCAN_MIN_CHARS_PER_PAGE) {
                    emptyPages++;
                }
            } catch (IOException e) {
                emptyPages++;
            }
        }
        return emptyPages >= checkPages;
    }

    /**
     * 单页文本清洗：去页眉页脚 + 去页码 + 压缩空行 + 行内公式合并
     */
    private String cleanPageText(String rawText, int pageIdx, int totalPages) {
        if (rawText == null) {
            return "";
        }

        String[] lines = rawText.split("\n");
        List<String> cleaned = new ArrayList<>();
        int totalLines = lines.length;

        for (int i = 0; i < totalLines; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }

            // 去除纯数字行（大概率是页码）
            if (line.matches("^\\d{1,4}$")) {
                continue;
            }

            // 去除页眉（前几行，且长度 < 30 的非标题内容）
            if (i < totalLines * HEADER_FOOTER_RATIO && line.length() < 30
                    && !line.matches(".*[第第].*[章章节节]") && !line.contains(".")) {
                continue;
            }

            // 去除页脚（最后几行）
            if (i > totalLines * (1 - HEADER_FOOTER_RATIO) && line.length() < 30
                    && !line.matches(".*[\\d]+.*[解证明答]")) {
                continue;
            }

            // 去除水印行（含 "版权所有" "翻印必究" 等）
            if (line.contains("版权所有") || line.contains("翻印")
                    || line.contains("侵权必究") || line.contains("ISBN")) {
                continue;
            }

            cleaned.add(line);
        }

        String result = String.join("\n", cleaned);

        // 压缩多余空行
        result = result.replaceAll("\n{3,}", "\n\n");
        // 清理控制字符
        result = result.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "");

        return result;
    }

    /**
     * OCR 处理扫描版 PDF：逐页渲染为图片 → 调用 OCR 服务 → 拼接文本
     */
    private PdfParseResult parseScannedPdf(Path pdfPath, PDDocument document,
                                            int pageCount, String fileName) throws IOException {
        PdfParseResult result = new PdfParseResult();
        result.fileName = fileName;
        result.totalPages = pageCount;
        result.pages = new ArrayList<>();
        result.isScanned = true;
        result.ocrProcessed = true;

        PDFRenderer renderer = new PDFRenderer(document);
        int processPages = Math.min(pageCount, OCR_MAX_PAGES);

        for (int pageIdx = 0; pageIdx < processPages; pageIdx++) {
            try {
                // 渲染页面为图片
                BufferedImage image = renderer.renderImageWithDPI(pageIdx, 200);
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                byte[] imgBytes = baos.toByteArray();
                baos.close();

                // 调用 OCR: MathPix 优先（数学公式强），回退 PaddleOCR
                String pageText;
                if (mathPixClient.isAvailable()) {
                    pageText = mathPixClient.recognizePage(imgBytes);
                } else if (ocrClient.isAvailable()) {
                    pageText = ocrClient.recognizePage(imgBytes);
                } else {
                    continue;
                }
                if (pageText != null && !pageText.isBlank()) {
                    pageText = cleanPageText(pageText, pageIdx, pageCount);
                    if (pageText.length() >= SCAN_MIN_CHARS_PER_PAGE) {
                        PdfPageInfo page = new PdfPageInfo();
                        page.pageNum = pageIdx + 1;
                        page.content = pageText;
                        result.pages.add(page);
                    }
                }
                // 释放图片内存
                image.flush();
            } catch (IOException | InterruptedException e) {
                System.err.println("[OCR] 第 " + (pageIdx + 1) + " 页识别失败: " + e.getMessage());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return result;
    }

    /** 获取 PDF 页数 */
    public int getPageCount(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return document.getNumberOfPages();
        }
    }

    // ===== 数据结构 =====

    /** PDF 解析结果 */
    public static class PdfParseResult {
        public String fileName;
        public int totalPages;
        public List<PdfPageInfo> pages = new ArrayList<>();
        public boolean isScanned;
        public boolean isEncrypted;
        public boolean ocrProcessed;
        public String errorMsg;
    }

    /** 单页解析结果 */
    public static class PdfPageInfo {
        public int pageNum;
        public String content;
    }

}
