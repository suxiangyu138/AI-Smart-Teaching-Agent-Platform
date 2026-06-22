package com.chatbot.rag.crawler;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数学公式标准化 — 将网页中各种非标 LaTeX 统一为 $$...$$ 或 $...$
 *
 * @author suxiangyu
 */
public class FormulaNormalizer {

    // 匹配 \(...\) → $...$
    private static final Pattern PAREN_INLINE = Pattern.compile("\\\\\\(([\\s\\S]*?)\\\\\\)");
    // 匹配 \[...\] → $$...$$
    private static final Pattern PAREN_DISPLAY = Pattern.compile("\\\\\\[([\\s\\S]*?)\\\\]");
    // 匹配 <math>...</math> 标签
    private static final Pattern MATH_TAG = Pattern.compile("<math[^>]*>([\\s\\S]*?)</math>");
    // 匹配 MathJax script 标签
    private static final Pattern MATHJAX_SCRIPT = Pattern.compile(
            "<script[^>]*type=\"math/tex[^>]*>([\\s\\S]*?)</script>");

    /** 统一标准化网页中的数学公式 */
    public static String normalize(String htmlText) {
        if (htmlText == null || htmlText.isEmpty()) {
            return htmlText;
        }

        String text = htmlText;

        // 1. MathJax script → $$...$$
        text = MATHJAX_SCRIPT.matcher(text).replaceAll(mr -> {
            String inner = mr.group(1).trim();
            return inner.startsWith("$$") || inner.startsWith("$") ? inner : "$$" + inner + "$$";
        });

        // 2. <math> 标签 → $$...$$
        text = MATH_TAG.matcher(text).replaceAll(mr -> "$$" + mr.group(1).trim() + "$$");

        // 3. \(...\) → $...$
        text = PAREN_INLINE.matcher(text).replaceAll(mr -> "$" + mr.group(1).trim() + "$");

        // 4. \[...\] → $$...$$
        text = PAREN_DISPLAY.matcher(text).replaceAll(mr -> "$$" + mr.group(1).trim() + "$$");

        // 5. 裸数学符号标准化（保守处理）
        text = normalizeBatch(text);

        return text;
    }

    // ==================== 裸 LaTeX 命令检测与包裹 ====================

    /** 匹配所有常用 LaTeX 命令（不包含在 $$ 或 $ 中） */
    private static final Pattern BARE_LATEX_COMMAND = Pattern.compile(
            "(?<!\\$)\\\\" +
            "(frac|sqrt|sum|int|lim|infty|pm|mp|times|div|cdot|" +
            "leq|geq|neq|approx|equiv|sim|partial|nabla|forall|exists|" +
            "in|notin|subset|subseteq|cup|cap|angle|triangle|" +
            "alpha|beta|gamma|delta|epsilon|theta|lambda|mu|pi|sigma|omega|" +
            "mathbf|mathbb|mathcal|bar|hat|vec|dot|widetilde|widehat|" +
            "overline|underline|operatorname|text|begin|end|left|right|" +
            "sin|cos|tan|cot|sec|csc|arcsin|arccos|arctan|" +
            "log|ln|lg|exp|det|dim|hom|ker|max|min|sup|inf|limsup|liminf|" +
            "Pr|E|Var|Cov|Corr" +
            ")\\b",
            Pattern.CASE_INSENSITIVE);

    /** 检查文本是否包含未包裹的 LaTeX 命令 */
    public static boolean hasLatexCommands(String text) {
        if (text == null || text.isEmpty()) return false;
        return BARE_LATEX_COMMAND.matcher(text).find();
    }

    /**
     * 批量标准化：将裸 LaTeX 命令安全包裹为 $$...$$ 块。
     * 已包裹在 $$...$$ 或 $...$ 中的内容保持不变。
     * 仅当匹配长度 >= 6 时包裹（避免单字符误报）。
     */
    public static String normalizeBatch(String text) {
        if (text == null || text.isEmpty()) return text;

        Pattern allPattern = Pattern.compile(
                "\\$\\$[\\s\\S]*?\\$\\$" +
                "|(?<!\\$)\\$[^\\$\\n<>]+?\\$(?!\\$)" +
                "|(" + BARE_LATEX_COMMAND.pattern() + "[^\\$<>]*)"
        );

        Matcher matcher = allPattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        int lastEnd = 0;

        while (matcher.find()) {
            sb.append(text, lastEnd, matcher.start());
            if (matcher.group(1) != null) {
                String bare = matcher.group(1).trim();
                if (bare.length() >= 6) {
                    sb.append("$$").append(bare).append("$$");
                } else {
                    sb.append(matcher.group());
                }
            } else {
                sb.append(matcher.group());
            }
            lastEnd = matcher.end();
        }
        sb.append(text.substring(lastEnd));
        return sb.toString();
    }

    /** 统计文本中的 $$...$$ 公式块数量 */
    public static int countFormulaBlocks(String text) {
        if (text == null) return 0;
        int count = 0;
        Matcher m = Pattern.compile("\\$\\$[\\s\\S]*?\\$\\$").matcher(text);
        while (m.find()) count++;
        return count;
    }
}
