package com.chatbot.rag.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * PDF 文档解析器 — 支持文字版 PDF 和简单扫描版识别
 *
 * @author suxiangyu
 */
@Component
public class PdfDocumentParser {

    /** PDF 文件大小上限（MB） */
    private static final int MAX_PDF_SIZE_MB = 50;
    /** 字节转 MB */
    private static final int BYTES_PER_MB = 1024 * 1024;

    /**
     * 解析 PDF 文件，提取纯文本
     */
    public String parsePdf(Path pdfPath) throws IOException {
        if (Files.size(pdfPath) > (long) MAX_PDF_SIZE_MB * BYTES_PER_MB) {
            throw new IOException("PDF 文件过大，最大支持 " + MAX_PDF_SIZE_MB + "MB");
        }
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            if (document.isEncrypted()) {
                throw new IOException("PDF 文件已加密，无法解析");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setParagraphStart("\n\n");
            String text = stripper.getText(document);
            if (text == null || text.isBlank()) {
                throw new IOException("PDF 无可提取文本，可能是扫描图片版，请使用 OCR 版本");
            }
            return cleanText(text);
        }
    }

    /**
     * 批量解析目录下所有 PDF
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
                    System.err.println("跳过 " + pdf.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return results;
    }

    /**
     * 清理提取文本
     */
    private String cleanText(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\r\n", "\n")
                .replace("\r", "\n")
                .replaceAll("(?m)^\\s*\\d+\\s*$", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("\n{3,}", "\n\n")
                .trim();
    }

    /**
     * 获取 PDF 页数
     */
    public int getPageCount(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            return document.getNumberOfPages();
        }
    }
}
