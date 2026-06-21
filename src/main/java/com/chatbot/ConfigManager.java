package com.chatbot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置管理：API Key 持久化存储
 *
 * @author suxiangyu
 */
@Component
public class ConfigManager {

    private static final Path CONFIG_DIR = Paths.get(
            System.getProperty("user.dir"), "data", "config");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("config.json");

    private final ObjectMapper mapper;
    private Map<String, Object> config;

    public ConfigManager() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.config = new LinkedHashMap<>();
        loadConfig();
    }

    /**
     * 加载配置文件
     */
    private void loadConfig() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> loaded = mapper.readValue(
                        CONFIG_FILE.toFile(), Map.class);
                config = loaded;
            }
        } catch (IOException e) {
            System.err.println("加载配置失败: " + e.getMessage());
            config = new LinkedHashMap<>();
        }
    }

    /**
     * 保存配置
     */
    public void saveConfig() {
        try {
            Files.createDirectories(CONFIG_DIR);
            mapper.writeValue(CONFIG_FILE.toFile(), config);
        } catch (IOException e) {
            System.err.println("保存配置失败: " + e.getMessage());
        }
    }

    public String getApiKey() {
        Object key = config.get("apiKey");
        return key != null ? key.toString() : "";
    }

    public void setApiKey(String apiKey) {
        config.put("apiKey", apiKey);
        saveConfig();
    }

    public String getApiEndpoint() {
        Object endpoint = config.get("apiEndpoint");
        return endpoint != null ? endpoint.toString()
                : "https://api.deepseek.com/v1/chat/completions";
    }

    public void setApiEndpoint(String endpoint) {
        config.put("apiEndpoint", endpoint);
        saveConfig();
    }

    public String getModel() {
        Object model = config.get("model");
        return model != null ? model.toString() : "deepseek-chat";
    }

    public void setModel(String model) {
        config.put("model", model);
        saveConfig();
    }

    public String getSystemPrompt() {
        Object prompt = config.get("systemPrompt");
        return prompt != null ? prompt.toString()
                : "你是专业初高中数学专属辅导老师，只回答初中、高中数学相关内容，严格遵守以下规则：\n" +
                  "1. 服务范围：初一至高三课内数学、同步练习题、期中期末真题、高考/中考数学；\n" +
                  "2. 禁止输出任何无关闲聊、其他学科内容、娱乐内容；用户问非数学内容，礼貌引导：\"我只专注初高中数学辅导，请输入数学题目或知识点问题～\"；\n" +
                  "3. 解题强制规范：\n" +
                  "   - 先判定学段（初中/高中）、对应知识点；\n" +
                  "   - 分步拆解解题步骤，每一步标注公式、定理；\n" +
                  "   - 计算类题目给出验算过程，证明题完整推导逻辑；\n" +
                  "4. 输出分层结构：\n" +
                  "   ① 知识点定位 ② 已知条件梳理 ③ 分步解题过程 ④ 答案 ⑤ 易错点提醒 ⑥ 同类变式题1道；\n" +
                  "5. 学生提问方式适配：支持拍照转文字题目、纯文字题干、知识点提问、错题订正、公式查询、解题方法总结；\n" +
                  "6. 语言通俗，贴合中学生认知，不使用大学数学术语；\n" +
                  "7. 【强制LaTeX公式规则 - 最高优先级】\n" +
                  "   必须严格区分行内公式和块级公式，用错标记会导致排版错乱！\n" +
                  "   \n" +
                  "   【行内公式 - 用单个 $ 包裹】句子内部的小型数学表达式，必须和文字同行显示：\n" +
                  "   - 单个数字/系数：$a$ $b$ $c$ $-10$ $3$ $x$ $y$\n" +
                  "   - 短方程：$x^2+3x-10=0$  $y=2x+1$  $a^2+b^2=c^2$\n" +
                  "   - 导数/区间：$f'(x)$  $x\\in(-\\infty,-1)$  $\\sqrt{2}$\n" +
                  "   - 正确示例：方程 $x^2+3x-10=0$ 的常数项为 $-10$，一次项系数为 $3$\n" +
                  "   - 错误示例：方程 x^2+3x-10=0 的常数项为 -10（裸写公式，无法渲染）\n" +
                  "   \n" +
                  "   【块级公式 - 用双 $$ 包裹】需要独立居中展示的大公式才用 $$...$$：\n" +
                  "   - 多行推导、复杂分式、大型矩阵、重要结论公式\n" +
                  "   - 正确示例：$$f'(x)=\\lim_{h\\to 0}\\frac{f(x+h)-f(x)}{h}$$\n" +
                  "   \n" +
                  "   关键规则：句子中出现的任何数字、符号、短方程都必须用单个 $ 包裹并嵌入句子内部！\n" +
                  "   禁止将行内公式单独换行输出，禁止将公式与说明文字拆分到不同行！";
    }

    public void setSystemPrompt(String prompt) {
        config.put("systemPrompt", prompt);
        saveConfig();
    }

    /**
     * API Key 是否已配置
     */
    public boolean hasApiKey() {
        String key = getApiKey();
        return key != null && !key.isBlank();
    }
}
