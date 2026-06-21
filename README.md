<p align="center">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white" alt="Java 25"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.15-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5.15"/>
  <img src="https://img.shields.io/badge/Maven-3.9-C71A36?logo=apachemaven&logoColor=white" alt="Maven"/>
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License MIT"/>
  <img src="https://img.shields.io/badge/RAG-PDFBox-green?logo=apache&logoColor=white" alt="RAG"/>
  <img src="https://img.shields.io/badge/Math-KaTeX-5f5f5f?logo=katex&logoColor=white" alt="KaTeX"/>
</p>

<h1 align="center">📐 MathRAG Tutor</h1>

<p align="center"><strong>企业级 K12+大学 数学 RAG 智能辅导多模型平台</strong></p>
<p align="center">聚合 6 家大模型 · 四层学段适配 · PDF 知识库检索增强 · LaTeX 公式渲染 · 流式 SSE</p>

---

## 目录

- [核心特性](#核心特性)
- [学段体系](#学段体系)
- [RAG 知识库](#rag-知识库)
- [支持厂商与模型](#支持厂商与模型)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [知识库使用指南](#知识库使用指南)
- [API 参考](#api-参考)
- [项目结构](#项目结构)
- [开发指南](#开发指南)
- [更新日志](#更新日志)

---

## 核心特性

| 特性 | 说明 |
|---|---|
| 🎓 四层学段 | 小学→初中→高中→大学拓展，精准难度匹配 |
| 📚 RAG 知识库 | PDF 教材/题库 → 数学分块 → 向量检索 → 上下文增强 |
| 🔍 双模式检索 | 云端 Embedding API + 本地关键词降级检索 |
| 🤖 多厂商聚合 | DeepSeek / Kimi / Qwen / GLM / MiniMax / MiMo 一键切换 |
| ⚡ 流式 SSE | 实时打字机效果，event:chunk / event:finish |
| 📐 公式渲染 | KaTeX 渲染 $$LaTeX$$ + 公式快捷插入栏 |
| 📝 错题本 | 本地 localStorage 持久化，按学段分类 |
| 🎨 数学主题 UI | 蓝白学习风 · 侧边栏工具面板 · 深色模式 |
| 🔐 API Key 本地存储 | 浏览器 localStorage，不上传服务端 |
| 🛡 限流保护 | 30 次/分钟/IP |

---

## 学段体系

| 学段 | Stage | 知识范围 | Temperature |
|------|-------|---------|-------------|
| 🏫 小学 | `primary` | 算术、四则运算、分数小数、几何图形、应用题 | 0.1 |
| 🏫 初中 | `junior` | 函数、几何、三角、不等式、概率统计 | 0.15 |
| 🎓 高中 | `senior` | 导数、圆锥曲线、数列、立体几何、排列组合 | 0.2 |
| 📚 大学拓展 | `university` | 微积分进阶、线性代数、离散数学、竞赛 | 0.3 |

### 学段控制规则
- **默认屏蔽超纲**：低学段不输出高学段内容
- **高中专属拓展开关**：开启后 AI 分层输出"课内标准解法 + 大学拓展推导"
- **动态 Prompt**：每个学段独立角色约束、语言风格、公式规范
- **RAG 检索过滤**：只检索 ≤ 当前学段的知识库，大学文档需拓展开关

---

## RAG 知识库

### 检索增强生成流程

```
用户提问 → 学段/题型识别 → 向量库检索 Top-K → 学段过滤 + 权重排序
         → 拼接上下文 → LLM 推理 → 流式返回 + 知识库溯源引用
```

### 技术栈

| 组件 | 技术 |
|------|------|
| PDF 解析 | Apache PDFBox 3.0.4 |
| 数学分块 | 自研 MathChunkingStrategy（公式保护 + 章节切分 + 题型识别） |
| 向量嵌入 | 双模式：云端 Embedding API / 本地 n-gram 降级 |
| 向量存储 | 自研 InMemoryVectorStore（余弦相似度 + JSON 持久化） |
| 检索过滤 | 学段层级过滤 + 大学权重 0.6 + 课内权重 1.0 |

### 知识库目录结构

```
math-library/
├── primary/          # 小学知识库
├── junior/           # 初中知识库
├── senior/           # 高中知识库
└── university/       # 大学拓展知识库（隔离）
```

将对应学段的 PDF 教材放入目录，点击前端「批量索引」自动识别学段并构建向量库。

---

## 支持厂商与模型

| 厂商 | Provider Code | 推荐模型 | 推理优选 |
|------|-------------|---------|---------|
| 深度求索 DeepSeek | `deepseek` | deepseek-v4-pro, deepseek-r1, deepseek-v3.2 | ⭐ R1 |
| Moonshot Kimi | `moonshot` | kimi-k2.6, kimi-k2.5, moonshot-v1-128k | ⭐ K2.6 |
| 阿里通义千问 | `qwen` | qwen3.7-max, qwen-max, qwen-plus | ⭐ Max |
| 智谱AI GLM | `zhipu` | glm-5.2, glm-5-turbo, glm-4.7-flash | ⭐ 5.2 |
| MiniMax | `minimax` | MiniMax-M3, MiniMax-M2.7 | |
| 小米 MiMo | `mimo` | mimo-v2.5-pro, mimo-v2-pro, mimo-v2-flash | |

---

## 架构设计

### 四层架构

```
【前端应用层】index.html
    聊天界面 / 模型切换 / 学段选择 / 知识库管理 / 错题本 / 公式速查

【控制层】ChatController + KnowledgeBaseController + ConfigController
    统一异常处理 / SSE 流式推送 / RAG 知识库 API

【业务层】ChatService + RagService + MathChunkingStrategy + EmbeddingService
    多模型适配器工厂 / 数学 Prompt 引擎 / RAG 检索 / 文档解析分块

【数据层】InMemoryVectorStore + LocalStorage + ConfigManager
    向量库持久化 / 前端缓存 / 配置管理
```

### 适配器模式（6 厂商）

```
BaseModelAdapter (streamChat, extractContent)
├── DeepSeekAdapter     — buildOpenAiBody() 静态方法共享
├── MoonshotKimiAdapter — 自定义 buildNativeBody（温度兼容）
├── QwenAdapter         — 委托 buildOpenAiBody()
├── ZhipuGlmAdapter     — 委托 buildOpenAiBody()
├── MiniMaxAdapter      — 委托 buildOpenAiBody()
└── MiMoAdapter         — 委托 buildOpenAiBody()
```

---

## 快速开始

### 环境要求

- **JDK 25+**
- **Maven 3.9+**

```bash
# 克隆项目
git clone https://github.com/suxiangyu138/LlmChatBot_sxy.git
cd LlmChatBot_sxy

# 编译
mvn clean compile

# 启动（默认端口 8080）
mvn spring-boot:run
```

浏览器访问 **http://localhost:8080**。

### 基本使用

1. 选择厂商与模型 → 点击 **API Key** 填入密钥
2. 侧边栏选择学段（小学/初中/高中）
3. 输入数学题目，回车发送
4. AI 按分层结构输出：知识点定位 → 解题步骤 → 答案 → 易错点 → 变式题

---

## 知识库使用指南

### 第一步：准备 PDF 资料

将数学教材/题库 PDF 放入对应学段目录：

```bash
# macOS / Linux
mkdir -p ~/.deepseek-chatbot/math-library/{primary,junior,senior,university}
cp 小学教材.pdf ~/.deepseek-chatbot/math-library/primary/

# Windows
mkdir %USERPROFILE%\.deepseek-chatbot\math-library\junior
copy 初中题库.pdf %USERPROFILE%\.deepseek-chatbot\math-library\junior\
```

### 第二步：构建知识库

1. 打开前端 → 侧边栏「📂 管理知识库」
2. 刷新文件列表确认 PDF 已就绪
3. 选择目标学段 → 点击「🚀 批量索引全部 PDF」
4. 等待索引完成（日志面板显示每个文件的分块数）

### 第三步：启用 RAG

1. 侧边栏打开「RAG 增强」开关
2. 提问时自动检索知识库，拼接教材原文增强回答
3. 高中模式可额外开启「🔓 大学拓展」

---

## API 参考

### 聊天接口

```
POST /api/chat
Content-Type: application/json
Accept: text/event-stream
```

请求体：
```json
{
  "provider": "deepseek",
  "modelName": "deepseek-r1",
  "apiKey": "sk-xxx",
  "baseUrl": "https://api.deepseek.com/v1",
  "stage": "senior",
  "allowUniversityExtend": false,
  "ragEnabled": true,
  "ragTopK": 4,
  "messages": [{"role": "user", "content": "求 f(x)=x³-3x 的极值"}]
}
```

SSE 响应：
```
event:chunk
data:{"type":"chunk","content":"首先求导","fullContent":"首先求导"}

event:chunk
data:{"type":"chunk","content":"...","fullContent":"首先求导..."}

event:finish
data:{"type":"finish","content":"","fullContent":"首先求导 f'(x)=3x²-3..."}
```

### 知识库接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/knowledge/stats` | 知识库统计（文档数/切片数/磁盘占用） |
| `GET` | `/api/knowledge/dir` | 获取知识库目录路径 |
| `GET` | `/api/knowledge/pdf-list` | 列出知识库目录下 PDF 文件 |
| `POST` | `/api/knowledge/index/pdf` | 索引单个 PDF |
| `POST` | `/api/knowledge/index/directory` | 批量索引目录下所有 PDF |
| `DELETE` | `/api/knowledge/document` | 删除指定文档索引 |
| `DELETE` | `/api/knowledge/clear` | 清空全部知识库 |

### 其他接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/providers` | 厂商与模型列表 |
| `GET` | `/api/session` | 创建会话，返回 `sid` |
| `GET` | `/api/history` | 获取会话历史 |
| `DELETE` | `/api/chat` | 清空会话 |
| `GET/POST` | `/api/config` | 获取/更新服务端配置 |

---

## 项目结构

```
src/main/java/com/chatbot/
├── Main.java                      # Spring Boot 入口
├── WebConfig.java                 # CORS 配置
├── RateLimitFilter.java           # IP 限流（30次/分钟）
├── ConfigManager.java             # 配置管理 (~/.deepseek-chatbot/)
├── ConfigController.java          # 配置 API
├── ChatController.java            # 聊天 API + 厂商列表
├── ChatService.java               # 会话管理 + SSE + 动态 Prompt
├── ChatMessage.java               # 消息模型
├── DeepSeekClient.java            # [遗留] 独立 DeepSeek 客户端
├── adapter/                       # 多厂商适配器
│   ├── BaseModelAdapter.java      # 适配器基类
│   ├── ModelAdapterFactory.java   # Spring DI 自动路由
│   ├── DeepSeekAdapter.java       # DeepSeek + 共享 body builder
│   ├── MoonshotKimiAdapter.java   # Kimi（温度兼容）
│   ├── QwenAdapter.java
│   ├── ZhipuGlmAdapter.java
│   ├── MiniMaxAdapter.java
│   └── MiMoAdapter.java
├── model/
│   ├── UnifiedChatRequest.java    # 统一请求体（四层学段常量）
│   ├── UnifiedStreamChunk.java    # 统一 SSE 数据块
│   └── ModelProviderConfig.java   # [遗留] 厂商配置模型
└── rag/                           # ★ RAG 知识库模块
    ├── RagService.java            # RAG 核心编排
    ├── KnowledgeBaseController.java # 知识库 REST API
    ├── model/
    │   ├── DocumentChunk.java     # 文档切片模型
    │   ├── VectorDocument.java    # 向量化文档
    │   ├── SearchResult.java      # 检索结果
    │   └── KnowledgeBaseStats.java # 知识库统计
    ├── document/
    │   ├── PdfDocumentParser.java # PDF 解析（PDFBox）
    │   └── MathChunkingStrategy.java # 数学专用分块策略
    ├── embedding/
    │   └── EmbeddingService.java  # 向量嵌入（云端+本地双模式）
    └── vector/
        └── InMemoryVectorStore.java # 向量存储 + 分层检索
src/main/resources/
├── application.properties
└── static/
    └── index.html                 # 单页前端 (680+ 行)
```

---

## 开发指南

```bash
# 增量编译
mvn compile

# 全量重编译
mvn clean compile

# 启动
mvn spring-boot:run

# 调试 SSE 流
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"provider":"moonshot","modelName":"kimi-k2.6","apiKey":"sk-xxx","baseUrl":"https://api.moonshot.cn/v1","messages":[{"role":"user","content":"求 x²+3x-10=0 的解"}]}'
```

### 添加新厂商

```java
@Component
public class NewAdapter extends BaseModelAdapter {
    @Override public String getProviderCode() { return "newprovider"; }
    @Override protected String buildNativeBody(UnifiedChatRequest req) throws Exception {
        return DeepSeekAdapter.buildOpenAiBody(req, "https://api.example.com/v1");
    }
}
```

然后在 `ChatController.providers()` 中添加厂商信息即可。

### 注意事项

- **不提交 API Key**：`.gitignore` 已排除 `.deepseek-chatbot/`、`vector_store/`、`knowledge_base/`
- **运行时不可 `mvn clean`**：jar 文件被进程锁定
- **Kimi k2.x 温度限制**：`MoonshotKimiAdapter` 自动省略 temperature 字段
- **RAG 本地模式**：无 API Key 时自动降级为关键词搜索，可离线使用

---

## 更新日志

### v2.0.0 — K12+大学 RAG 智能体 (2026-06-22)

**新增**
- 🎓 四层学段体系：小学/初中/高中/大学拓展
- 📚 RAG 知识库：PDF 解析 → 数学分块 → 向量检索 → 上下文增强
- 🔍 双模式向量嵌入：云端 Embedding API + 本地 n-gram 降级
- 📂 知识库管理 API：索引/统计/清除/文件列表
- 🏫 学段目录自动识别（primary/junior/senior/university）
- 🔓 高中专属大学拓展开关
- 📊 分层向量检索（课内权重 1.0 / 大学权重 0.6）
- 🌡 学段温度映射（小学 0.1 → 高中 0.2 → 拓展 0.3）
- 📐 小学知识点目录 + 公式速查
- 🔄 动态快捷按钮（按学段切换文案）
- 📝 错题本按学段分类存储

**改进**
- System Prompt 重构为四层动态拼接
- 前端 UI 升级：学段下拉选择器 + 拓展开关 + 侧边栏工具面板
- SSE 结束事件 `done` → `finish`
- 公式插入栏（分数/根号/积分/极限等）
- KaTeX 渲染增强（`$$...$$` + `$...$`）
- 全代码 P3C 企业规范（0 High/Medium 警告）

**技术栈**
- Apache PDFBox 3.0.4
- 自研 InMemoryVectorStore（余弦相似度 + 分层过滤 + JSON 持久化）
- 自研 MathChunkingStrategy（公式保护 + 章节切分 + 题型识别）

### v1.0.0 — 多厂商大模型统一对话平台

- 6 厂商适配器（DeepSeek/Kimi/Qwen/GLM/MiniMax/MiMo）
- SSE 流式聊天
- Markdown + KaTeX 渲染
- 会话管理 + 历史记录
- 深色模式 + API Key 管理
- IP 限流保护

---

## License

MIT License. 详见 [LICENSE](./LICENSE) 文件。
