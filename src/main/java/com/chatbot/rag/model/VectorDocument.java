package com.chatbot.rag.model;

/**
 * 向量化文档 — 文档切片 + 嵌入向量
 *
 * @author suxiangyu
 */
public class VectorDocument {

    private String id;
    private String sourceFile;
    private String content;
    private float[] embedding;
    private String grade;
    private String chapterTitle;
    private String knowledgePoint;
    private String questionType;
    private double weight = 1.0;
    private String sourceType;
    private String sourceName;
    private Integer pageNum;
    private boolean hasFormula;
    private int formulaCount;
    /** 切片在原文档中的字符区间（用于相邻分块上下文扩展） */
    private int startChar;
    private int endChar;

    public VectorDocument() {}

    public String getId() { return id; }
    public void setId(String v) { id = v; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String v) { sourceFile = v; }

    public String getContent() { return content; }
    public void setContent(String v) { content = v; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] v) { embedding = v; }

    public String getGrade() { return grade; }
    public void setGrade(String v) { grade = v; }

    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String v) { chapterTitle = v; }

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
    public boolean isHasFormula() { return hasFormula; }
    public void setHasFormula(boolean v) { hasFormula = v; }
    public int getFormulaCount() { return formulaCount; }
    public void setFormulaCount(int v) { formulaCount = v; }

    public int getStartChar() { return startChar; }
    public void setStartChar(int v) { startChar = v; }
    public int getEndChar() { return endChar; }
    public void setEndChar(int v) { endChar = v; }
}
