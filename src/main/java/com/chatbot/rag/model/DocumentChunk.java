package com.chatbot.rag.model;

import java.util.Map;
import java.util.HashMap;

/**
 * 文档切片模型 — 数学专用分块后的最小检索单元
 *
 * @author suxiangyu
 */
public class DocumentChunk {

    /** 切片唯一ID */
    private String chunkId;
    /** 来源文档名 */
    private String sourceFile;
    /** 切片文本内容 */
    private String content;
    /** 切片在原文档中的字符起始位置 */
    private int startChar;
    /** 切片在原文档中的字符结束位置 */
    private int endChar;
    /** 章节标题（如有） */
    private String chapterTitle;
    /** 学段：primary/junior/senior/university */
    private String stage;
    /** 知识点标签 */
    private String knowledgePoint;
    /** 题型：calculation/proof/geometry/function */
    private String questionType;
    /** 检索权重（1.0=课本教材, 0.85=教辅, 0.6=爬虫, 0.4=大学拓展） */
    private double weight = 1.0;
    /** 来源类型：textbook/exam/lecture/crawl */
    private String sourceType;
    /** 来源名称（书名/网页标题） */
    private String sourceName;
    /** 页码 */
    private Integer pageNum;
    /** 元数据扩展 */
    private Map<String, String> metadata = new HashMap<>();
    /** 是否包含数学公式 */
    private boolean hasFormula;
    /** 公式块数量 */
    private int formulaCount;

    public DocumentChunk() {}

    public String getChunkId() { return chunkId; }
    public void setChunkId(String v) { chunkId = v; }
    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String v) { sourceFile = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public int getStartChar() { return startChar; }
    public void setStartChar(int v) { startChar = v; }
    public int getEndChar() { return endChar; }
    public void setEndChar(int v) { endChar = v; }
    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String v) { chapterTitle = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    /** @deprecated 使用 getStage() */
    @Deprecated
    public String getGrade() { return stage; }
    /** @deprecated 使用 setStage() */
    @Deprecated
    public void setGrade(String v) { stage = v; }
    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String v) { knowledgePoint = v; }
    public String getQuestionType() { return questionType; }
    public void setQuestionType(String v) { questionType = v; }
    public double getWeight() { return weight; }
    public void setWeight(double v) { weight = v; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String v) { sourceType = v; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String v) { sourceName = v; }
    public Integer getPageNum() { return pageNum; }
    public void setPageNum(Integer v) { pageNum = v; }
    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> v) { metadata = v; }
    public boolean isHasFormula() { return hasFormula; }
    public void setHasFormula(boolean v) { hasFormula = v; }
    public int getFormulaCount() { return formulaCount; }
    public void setFormulaCount(int v) { formulaCount = v; }
}
