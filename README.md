<h1 align="center">苏巷雨 · 智慧教学智能体</h1>

<p align="center"><strong>K12+大学 数学 RAG 智能辅导平台 — 多模型聚合 · 深空玻璃拟态 · 对话历史 · 思考可视化</strong></p>
<p align="center">6 家大模型 · 四层学段适配 · PDF 知识库 · LaTeX 渲染 · 流式平滑输出 · 思考过程折叠</p>

---

## 目录

- [核心特性](#核心特性)
- [学段体系](#学段体系)
- [RAG 知识库](#rag-知识库)
- [对话历史系统](#对话历史系统)
- [思考过程可视化](#思考过程可视化)
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
|------|------|
| 深空玻璃拟态 UI | 渐变星空背景 · 毛玻璃卡片 · 粒子动效 · 鼠标跟随柔光 · 流光边框 |
| 四层学段 | 小学→初中→高中→大学拓展，精准难度匹配 |
| 对话历史 | H2 持久化 · 会话列表 · 一键切换 · 自动标题 · 软删除 |
| 思考可视化 | 推理过程自动隐藏 · 点击展开/折叠 · 状态持久化 |
| 流式平滑输出 | 缓冲队列 + 匀速打字机 · 智能加速 · 不再蹦字 |
| RAG 知识库 | PDF→数学分块→向量检索→上下文增强 |
| 多厂商聚合 | DeepSeek / Kimi / Qwen / GLM / MiniMax / MiMo |
| 公式渲染 | KaTeX + amsmath · trust 模式 · 裸 LaTeX 自动包裹 · 破碎公式修复 |
| 网页爬虫 | BFS 爬虫 + Playwright 渲染 + FormulaNormalizer 公式标准化 |
| OCR 识别 | PaddleOCR + Pix2Tex + MathPix 三通道 · 扫描版 PDF 支持 |
| 错题本 | LocalStorage 持久化 · 按学段分类 |
| API Key 本地 | 存于浏览器 localStorage，随请求发给本机服务端并转发给模型厂商；当前界面不会把它写入服务端配置 |
| 限流保护 | 对话 30 次/分钟/IP，其余接口 300 次/分钟/IP |
| 默认只监听本机 | 服务绑定 127.0.0.1，局域网内其他设备无法访问 |

---

## 学段体系

| 学段 | Stage | 知识范围 | Temperature |
|------|-------|---------|-------------|
| 小学 | `primary` | 算术、四则运算、分数小数、几何图形、应用题 | 0.1 |
| 初中 | `junior` | 函数、几何、三角、不等式、概率统计 | 0.15 |
| 高中 | `senior` | 导数、圆锥曲线、数列、立体几何、排列组合 | 0.2 |
| 大学拓展 | `university` | 微积分进阶、线性代数、离散数学、竞赛 | 0.3 |

- 低学段屏蔽超纲内容 · 高中专属拓展开关 · 动态 Prompt · RAG 学段过滤

---

## RAG 知识库

```
用户提问 → 学段/题型识别 → 向量库检索 Top-K → 学段过滤 + 权重排序
         → 拼接上下文 → LLM 推理 → 流式返回 + 知识库溯源
```

| 组件 | 技术 |
|------|------|
| PDF 解析 | Apache PDFBox 3.0.4 + 扫描版 OCR |
| 数学分块 | 自研 MathChunkingStrategy（公式保护 + 章节切分 + 题型/公式元数据） |
| 向量嵌入 | 双模式：云端 Embedding API / 本地 n-gram TF-IDF 降级 |
| 向量存储 | 自研 InMemoryVectorStore（余弦相似度 + 多路召回 + JSON 持久化） |
| 公式标准化 | FormulaNormalizer：网页 MathJax/`\(`/`\[` → `$$`/`$`；裸 LaTeX 自动包裹 |
| OCR | PaddleOCR（中文）+ Pix2Tex（公式）+ MathPix（云端优先） |

知识库目录结构：

```
math-library/
├── primary/    ├── junior/    ├── senior/    └── university/
```

---

## 对话历史系统

### 数据持久化

- **H2 嵌入式数据库**：零配置，文件存储在 `data/chatbot-db`
- **会话表** (`chat_session`)：标题、学段、模型、时间戳、软删除
- **消息表** (`chat_message`)：角色、完整文本、思考内容 (`think_raw`)
- 标题自动取首条用户消息前 18 字

### 交互

- 侧边栏「历史对话」显示最近 50 条会话
- 新建对话自动创建数据库记录
- 点击历史会话加载全部消息（含思考过程）
- hover 显示删除按钮，确认后逻辑删除
- 刷新页面自动恢复上次会话 (LocalStorage)

---

## 思考过程可视化

推理模型（DeepSeek-R1 / Kimi K2.6 等）输出分为两层：

| 层 | 展示方式 | 内容 |
|----|---------|------|
| 思考草稿 | 默认折叠，虚线弱化样式 | 内部推理、试算、多思路 |
| 正式答案 | 正常展示，高亮清晰 | 标准 LaTeX 解题过程 |

- `reasoning_content` → 隐藏面板，点「查看AI推理过程」展开
- `content` → 正文区域，匀速平滑输出
- 展开状态记忆 (LocalStorage) · 首次展开时懒渲染公式
- 数据库 `think_raw` 字段单独存储

---

## 支持厂商与模型

| 厂商 | Provider | 推荐模型 | 推理 |
|------|----------|---------|------|
| 深度求索 | `deepseek` | deepseek-v4-pro, deepseek-r1, deepseek-v3.2 | R1 |
| Moonshot Kimi | `moonshot` | kimi-k2.6, kimi-k2.5, moonshot-v1-128k | K2.6 |
| 阿里通义千问 | `qwen` | qwen3.7-max, qwen-max, qwen-plus | Max |
| 智谱AI GLM | `zhipu` | glm-5.2, glm-5-turbo, glm-4.7-flash | 5.2 |
| MiniMax | `minimax` | MiniMax-M3, MiniMax-M2.7 | |
| 小米 MiMo | `mimo` | mimo-v2.5-pro, mimo-v2-pro, mimo-v2-flash | |

---

## 架构设计

```
【前端】index.html — 深空玻璃拟态 · SSE 流式 · KaTeX 渲染 · 思考面板 · 历史列表

【控制层】ChatController / KnowledgeBaseController / CrawlerController
    SSE 流式 + reasoning 事件 · RAG API · 爬虫 API · 历史 CRUD

【业务层】ChatService / RagService / ChatHistoryService
    多模型适配器 · 数学 Prompt 引擎 · RAG 检索 · 历史持久化 · 公式标准化

【数据层】H2 (JPA) + InMemoryVectorStore + InMemory Sessions + LocalStorage
```

```
BaseModelAdapter (streamChat, extractContent, extractReasoning)
├── DeepSeekAdapter     — buildOpenAiBody() 共享
├── MoonshotKimiAdapter — 温度兼容
├── QwenAdapter / ZhipuGlmAdapter / MiniMaxAdapter / MiMoAdapter
```

---

## 快速开始

### 环境要求

- **JDK 25+** · **Maven 3.9+**

```bash
git clone https://github.com/suxiangyu138/LlmChatBot_sxy.git
cd LlmChatBot_sxy
mvn clean compile
mvn spring-boot:run
```

浏览器访问 **http://localhost:8080**。Windows 用户双击 `启动聊天机器人.bat`。

### 基本使用

1. 选择厂商与模型 → 点击 **API Key** 填入密钥
2. 侧边栏选择学段
3. 输入数学题目，回车发送
4. AI 流式输出答案 · 推理过程在「查看AI推理过程」中

---

## API 参考

### 聊天

```
POST /api/chat          SSE 流式聊天（event:chunk / event:reasoning / event:finish）
GET  /api/history       获取内存会话历史
DELETE /api/chat        清空内存会话
GET  /api/session       创建内存会话
GET  /api/providers      厂商与模型列表
```

### 对话历史

```
POST   /api/history/session/create          创建数据库会话
GET    /api/history/sessions?page=0&size=50 会话列表
DELETE /api/history/session/{id}            逻辑删除
PUT    /api/history/session/rename          重命名
GET    /api/history/messages/{id}           查询全部消息（含 thinkRaw）
```

### 知识库

```
GET    /api/knowledge/stats               统计
POST   /api/knowledge/index/pdf           索引单个 PDF
POST   /api/knowledge/index/directory     批量索引
POST   /api/knowledge/restandardize       公式批量标准化
GET    /api/knowledge/formula-stats       公式统计
DELETE /api/knowledge/clear               清空
```

### 爬虫

```
POST /api/crawler/start                  BFS 爬虫
GET  /api/crawler/progress               进度
POST /api/crawler/quick                  单 URL 快速爬取
POST /api/crawler/playwright-index       Playwright 渲染索引
```

---

## 项目结构

```
src/main/java/com/chatbot/
├── Main.java / WebConfig.java / RateLimitFilter.java
├── ConfigManager.java / ConfigController.java
├── ChatController.java / ChatService.java / ChatMessage.java
├── adapter/            # 6 厂商适配器
├── model/              # UnifiedChatRequest, UnifiedStreamChunk
├── history/            # 对话历史持久化
│   ├── ChatSessionEntity.java / ChatMessageEntity.java
│   ├── ChatSessionRepository.java / ChatMessageRepository.java
│   └── ChatHistoryService.java
└── rag/                # RAG 知识库
    ├── RagService.java / KnowledgeBaseController.java
    ├── model/          # DocumentChunk, VectorDocument, SearchResult
    ├── document/       # PdfDocumentParser, MathChunkingStrategy, MathPixClient, ScanOcrClient
    ├── embedding/      # EmbeddingService
    ├── vector/         # InMemoryVectorStore
    └── crawler/        # WebCrawlerService, PlaywrightClient, FormulaNormalizer, CrawlRequest
```

---

## 更新日志

### v3.0.0 — 深空智能体 · 历史系统 · 思考可视化 (2026-06-22)

**新增**
- 深空玻璃拟态 UI：渐变星空背景、毛玻璃卡片、粒子动效、鼠标柔光、流光边框
- 对话历史系统：H2 持久化、会话列表、切换/删除/自动标题
- 思考过程可视化：reasoning_content 分离存储、折叠面板、展开状态记忆
- 流式平滑打字机：缓冲队列 + 25ms 匀速输出 + 智能加速
- LaTeX 渲染全面修复：trust/strict 模式、`\[`/`\(` 兼容、裸公式自动包裹、破碎公式修复
- 公式标准化管道：FormulaNormalizer + 批量重标准化接口
- 网页爬虫：BFS + Playwright + 公式自动标准化
- OCR 多通道：PaddleOCR + Pix2Tex + MathPix
- 苏巷雨品牌标识：侧边栏品牌头、欢迎卡片、顶部栏

**改进**
- SSE 新增 `reasoning` 事件类型
- LaTeX 清洗函数 cleanLatexText/cleanLeakedHtml
- 系统提示词强制 LaTeX 规范（禁止换行拆公式、下标空格）
- 新建对话自动清理内存会话（避免旧消息污染）
- 加载指示优化（推理阶段显示「深度思考中」）
- 侧边栏公式列表 KaTeX 渲染 + 悬停浮窗 + 复制/插入按钮

### v2.0.0 — K12+大学 RAG 智能体

- 四层学段体系 · RAG 知识库 · PDF 解析/分块/检索 · 双模式嵌入 · 错题本

### v1.0.0 — 多厂商统一对话平台

- 6 厂商适配器 · SSE 流式 · Markdown + KaTeX · 会话管理 · 深色模式

---

## License

MIT License. 详见 [LICENSE](./LICENSE) 文件。
