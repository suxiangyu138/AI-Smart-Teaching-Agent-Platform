package com.chatbot.rag.document;

import com.chatbot.model.UnifiedChatRequest;
import com.chatbot.rag.model.DocumentChunk;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 数学专用文档分块策略
 * <p>
 * 核心原则：
 * 1. 不切碎公式（$$...$$ 块完整保留）
 * 2. 不切碎解题步骤（保持推导连贯性）
 * 3. 按章节标题优先切分
 * 4. 识别并标注学段、知识点、题型
 *
 * @author suxiangyu
 */
@Component
public class MathChunkingStrategy {

    /** 目标分块大小（字符数） */
    private static final int CHUNK_SIZE = 600;
    /** 重叠字符数 */
    private static final int CHUNK_OVERLAP = 100;
    /** 最小切片长度 */
    private static final int MIN_CHUNK_LENGTH = 20;
    /** 章节标题长度上限 */
    private static final int MAX_TITLE_LENGTH = 50;

    /** 题型常量 */
    private static final String TYPE_PROOF = "proof";
    private static final String TYPE_GEOMETRY = "geometry";
    private static final String TYPE_FUNCTION = "function";
    private static final String TYPE_CALCULATION = "calculation";
    private static final String TYPE_GENERAL = "general";

    /** 章节标题模式 */
    private static final Pattern CHAPTER_PATTERN =
            Pattern.compile("(?m)^(第[一二三四五六七八九十\\d]+章|第[一二三四五六七八九十\\d]+节"
                    + "|Chapter\\s*\\d+|§\\d+)\\s*.*$");

    /** 公式块边界 */
    private static final Pattern LATEX_BLOCK =
            Pattern.compile("\\$\\$[\\s\\S]*?\\$\\$");

    /** 小学关键词 */
    private static final Set<String> PRIMARY_KEYWORDS = Set.of(
            "口算", "四则运算", "乘法口诀", "分数", "小数",
            "面积", "体积", "应用题", "图形", "年月日",
            "克与千克", "米与厘米", "人民币", "钟表", "线段"
    );

    /** 初中关键词 */
    private static final Set<String> JUNIOR_KEYWORDS = Set.of(
            "一次函数", "二次函数", "三角形", "全等", "勾股定理",
            "圆", "方程", "不等式", "概率", "统计", "有理数",
            "实数", "平面直角坐标系", "平行四边形", "投影"
    );

    /** 高中关键词 */
    private static final Set<String> SENIOR_KEYWORDS = Set.of(
            "导数", "微分", "积分", "圆锥曲线", "数列", "向量",
            "复数", "立体几何", "排列组合", "二项式定理",
            "三角函数", "参数方程", "极坐标", "空间向量", "双曲线"
    );

    /** 大学关键词 */
    private static final Set<String> UNIVERSITY_KEYWORDS = Set.of(
            "微积分基本定理", "线性代数", "矩阵", "特征值", "离散数学",
            "数理统计", "正态分布", "极限", "ε-δ", "级数",
            "多元函数", "偏导数", "重积分", "微分方程", "群论"
    );

    /** 几何题型特征字符 */
    private static final Set<String> GEOMETRY_MARKERS = Set.of(
            "如图", "△", "∠", "几何", "图形"
    );

    /** 函数题型特征字符 */
    private static final Set<String> FUNCTION_MARKERS = Set.of(
            "f(", "函数", "定义域", "值域"
    );

    /** 计算题型特征字符 */
    private static final Set<String> CALCULATION_MARKERS = Set.of(
            "计算", "解", "求值", "="
    );

    /** 证明题型特征字符 */
    private static final Set<String> PROOF_MARKERS = Set.of(
            "证明", "求证"
    );

    /**
     * 将文档文本切分为数学专用切片
     */
    public List<DocumentChunk> chunkDocument(String text, String sourceFile, String grade) {
        List<DocumentChunk> chunks = new ArrayList<>();

        List<String> sections = splitByChapters(text);

        int globalOffset = 0;
        for (String section : sections) {
            String chapterTitle = extractChapterTitle(section);
            String detectedGrade = grade;
            if (detectedGrade == null) {
                detectedGrade = detectGrade(section);
            }
            String knowledgePoint = detectKnowledgePoint(section);

            List<String> subChunks = splitWithFormulaProtection(section);
            int sectionOffset = 0;
            for (int i = 0; i < subChunks.size(); i++) {
                String content = subChunks.get(i).trim();
                if (content.length() < MIN_CHUNK_LENGTH) {
                    continue;
                }

                DocumentChunk chunk = new DocumentChunk();
                chunk.setChunkId(UUID.randomUUID().toString().substring(0, 8));
                chunk.setSourceFile(sourceFile);
                chunk.setContent(content);
                chunk.setStartChar(globalOffset + sectionOffset);
                chunk.setEndChar(globalOffset + sectionOffset + content.length());
                chunk.setChapterTitle(chapterTitle);
                chunk.setStage(detectedGrade);
                chunk.setKnowledgePoint(knowledgePoint);
                chunk.setQuestionType(detectQuestionType(content));
                // 自动设置权重 + 来源类型
                chunk.setSourceType(detectSourceType(sourceFile, content));
                chunk.setWeight(getDefaultWeight(chunk.getSourceType(), detectedGrade));
                chunk.setSourceName(sourceFile);
                // 公式元数据提取
                chunk.setHasFormula(
                        com.chatbot.rag.crawler.FormulaNormalizer.hasLatexCommands(content)
                        || com.chatbot.rag.crawler.FormulaNormalizer.countFormulaBlocks(content) > 0);
                chunk.setFormulaCount(
                        com.chatbot.rag.crawler.FormulaNormalizer.countFormulaBlocks(content));
                chunks.add(chunk);

                sectionOffset += Math.max(0, content.length() - CHUNK_OVERLAP);
                if (i < subChunks.size() - 1) {
                    sectionOffset = Math.min(sectionOffset, content.length());
                }
            }
            globalOffset += section.length() + 2;
        }

        return chunks;
    }

    /**
     * 按章节标题切分
     */
    List<String> splitByChapters(String text) {
        List<String> sections = new ArrayList<>();
        String[] parts = CHAPTER_PATTERN.split(text);

        var matcher = CHAPTER_PATTERN.matcher(text);
        List<String> titles = new ArrayList<>();
        while (matcher.find()) {
            titles.add(matcher.group());
        }

        StringBuilder current = new StringBuilder();
        int titleIdx = 0;
        for (int i = 0; i < parts.length; i++) {
            if (titleIdx > 0 || i > 0) {
                String title = titleIdx <= titles.size()
                        ? titles.get(Math.min(titleIdx, titles.size() - 1)) : "";
                current.append(title).append("\n");
                if (titleIdx < titles.size()) {
                    titleIdx++;
                }
            }
            current.append(parts[i]);
            boolean isLongEnough = current.length() >= CHUNK_SIZE * 2;
            boolean isLast = i == parts.length - 1;
            if (isLongEnough || isLast) {
                sections.add(current.toString().trim());
                current = new StringBuilder();
            }
        }
        if (!current.isEmpty()) {
            sections.add(current.toString().trim());
        }
        return sections.isEmpty() ? List.of(text) : sections;
    }

    /**
     * 公式保护的段落切分
     */
    List<String> splitWithFormulaProtection(String text) {
        List<String> formulas = new ArrayList<>();
        String protectedText = LATEX_BLOCK.matcher(text).replaceAll(mr -> {
            formulas.add(mr.group());
            return "%%FORMULA_" + (formulas.size() - 1) + "%%";
        });

        List<String> chunks = new ArrayList<>();
        String[] paragraphs = protectedText.split("\n\n");
        StringBuilder currentChunk = new StringBuilder();

        for (String para : paragraphs) {
            para = para.trim();
            if (para.isEmpty()) {
                continue;
            }

            boolean wouldExceed = currentChunk.length() + para.length() > CHUNK_SIZE;
            boolean hasContent = currentChunk.length() > 0;
            if (wouldExceed && hasContent) {
                chunks.add(restoreFormulas(currentChunk.toString(), formulas));
                currentChunk = new StringBuilder();
            }
            if (currentChunk.length() > 0) {
                currentChunk.append("\n\n");
            }
            currentChunk.append(para);
        }

        if (currentChunk.length() > 0) {
            chunks.add(restoreFormulas(currentChunk.toString(), formulas));
        }

        return chunks.isEmpty() ? List.of(text) : chunks;
    }

    private String restoreFormulas(String text, List<String> formulas) {
        String result = text;
        for (int i = 0; i < formulas.size(); i++) {
            result = result.replace("%%FORMULA_" + i + "%%", formulas.get(i));
        }
        return result;
    }

    /**
     * 提取章节标题
     */
    String extractChapterTitle(String text) {
        var matcher = CHAPTER_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group().trim();
        }
        String firstLine = text.split("\n")[0].trim();
        if (firstLine.length() < MAX_TITLE_LENGTH) {
            return firstLine;
        }
        return "";
    }

    /**
     * 自动检测学段（四层体系）
     */
    String detectGrade(String text) {
        int primaryScore = countMatches(text, PRIMARY_KEYWORDS);
        int juniorScore = countMatches(text, JUNIOR_KEYWORDS);
        int seniorScore = countMatches(text, SENIOR_KEYWORDS);
        int uniScore = countMatches(text, UNIVERSITY_KEYWORDS);

        if (uniScore > seniorScore && uniScore > juniorScore) {
            return UnifiedChatRequest.STAGE_UNIVERSITY;
        }
        if (seniorScore > juniorScore && seniorScore > primaryScore) {
            return UnifiedChatRequest.STAGE_SENIOR;
        }
        if (juniorScore > primaryScore) {
            return UnifiedChatRequest.STAGE_JUNIOR;
        }
        if (primaryScore > 0) {
            return UnifiedChatRequest.STAGE_PRIMARY;
        }
        return UnifiedChatRequest.STAGE_JUNIOR;
    }

    private int countMatches(String text, Set<String> keywords) {
        int count = 0;
        for (String kw : keywords) {
            if (text.contains(kw)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 检测知识点
     */
    String detectKnowledgePoint(String text) {
        Set<String> allKeywords = new HashSet<>();
        allKeywords.addAll(JUNIOR_KEYWORDS);
        allKeywords.addAll(SENIOR_KEYWORDS);
        for (String kw : allKeywords) {
            if (text.contains(kw)) {
                return kw;
            }
        }
        return "综合";
    }

    /** 检测来源类型 */
    String detectSourceType(String fileName, String text) {
        String lower = (fileName + " " + text).toLowerCase();
        if (lower.contains("教材") || lower.contains("课本") || lower.contains("textbook")) {
            return "textbook";
        }
        if (lower.contains("试卷") || lower.contains("真题") || lower.contains("考试")
                || lower.contains("期中") || lower.contains("期末") || lower.contains("高考")
                || lower.contains("中考") || lower.contains("exam")) {
            return "exam";
        }
        if (lower.contains("讲义") || lower.contains("课件") || lower.contains("lecture")) {
            return "lecture";
        }
        if (lower.contains("竞赛") || lower.contains("奥数") || lower.contains("联赛")) {
            return "lecture";
        }
        return "textbook";
    }

    /** 按来源类型和学段获取默认权重 */
    double getDefaultWeight(String sourceType, String stage) {
        if ("textbook".equals(sourceType)) {
            return 1.0;
        }
        if ("exam".equals(sourceType)) {
            return 0.85;
        }
        if ("lecture".equals(sourceType)) {
            return 0.7;
        }
        if (UnifiedChatRequest.STAGE_UNIVERSITY.equals(stage)) {
            return 0.4;
        }
        return 0.6;
    }

    /**
     * 检测题型
     */
    String detectQuestionType(String text) {
        String lower = text.toLowerCase();

        for (String marker : PROOF_MARKERS) {
            if (lower.contains(marker)) {
                return TYPE_PROOF;
            }
        }
        for (String marker : GEOMETRY_MARKERS) {
            if (lower.contains(marker)) {
                return TYPE_GEOMETRY;
            }
        }

        boolean hasFunctionKeyword = false;
        for (String marker : FUNCTION_MARKERS) {
            if (lower.contains(marker)) {
                hasFunctionKeyword = true;
                break;
            }
        }
        boolean hasFunctionPrefix = lower.contains("求");
        if (hasFunctionPrefix && hasFunctionKeyword) {
            return TYPE_FUNCTION;
        }

        for (String marker : CALCULATION_MARKERS) {
            if (lower.contains(marker)) {
                return TYPE_CALCULATION;
            }
        }
        return TYPE_GENERAL;
    }
}
