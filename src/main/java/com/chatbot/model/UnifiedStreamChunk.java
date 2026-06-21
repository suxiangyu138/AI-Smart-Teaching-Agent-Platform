package com.chatbot.model;

/**
 * 后端 → 前端 统一SSE流式数据
 *
 * @author suxiangyu
 */
public class UnifiedStreamChunk {
    /** chunk=文本片段 / finish=结束 / error=异常 */
    private String type;
    /** 本次流式返回文本片段 */
    private String content;
    /** 当前累计完整回答文本 */
    private String fullContent;
    /** type=error 时的报错信息 */
    private String errMsg;

    public UnifiedStreamChunk() {}

    public UnifiedStreamChunk(String type, String content,
                              String fullContent) {
        this.type = type;
        this.content = content;
        this.fullContent = fullContent;
    }

    public static UnifiedStreamChunk chunk(String content,
                                           String full) {
        return new UnifiedStreamChunk("chunk", content, full);
    }

    public static UnifiedStreamChunk done(String full) {
        return new UnifiedStreamChunk("done", "", full);
    }

    public static UnifiedStreamChunk finish(String full) {
        return new UnifiedStreamChunk("finish", "", full);
    }

    public static UnifiedStreamChunk error(String msg) {
        UnifiedStreamChunk c = new UnifiedStreamChunk();
        c.type = "error";
        c.errMsg = msg;
        return c;
    }

    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public String getFullContent() { return fullContent; }
    public void setFullContent(String v) { fullContent = v; }
    public String getErrMsg() { return errMsg; }
    public void setErrMsg(String v) { errMsg = v; }
}
