<p align="center">
  <img src="https://img.shields.io/badge/Java-25-ED8B00?logo=openjdk&logoColor=white" alt="Java 25"/>
  <img src="https://img.shields.io/badge/Spring_Boot-3.5.15-6DB33F?logo=springboot&logoColor=white" alt="Spring Boot 3.5.15"/>
  <img src="https://img.shields.io/badge/Maven-3.9-C71A36?logo=apachemaven&logoColor=white" alt="Maven"/>
  <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License MIT"/>
</p>

<h1 align="center">LLMChatBot</h1>

<p align="center"><strong>多厂商大模型统一对话平台</strong> &mdash; 一个聚合 DeepSeek、Kimi、Qwen、GLM、MiniMax、MiMo 的企业级轻量聊天机器人</p>

---

## 目录

- [功能特性](#功能特性)
- [支持厂商与模型](#支持厂商与模型)
- [架构设计](#架构设计)
- [快速开始](#快速开始)
- [API 参考](#api-参考)
- [配置指南](#配置指南)
- [项目结构](#项目结构)
- [开发指南](#开发指南)

## 功能特性

| 特性 | 说明 |
|---|---|
| 多厂商聚合 | 统一界面一键切换 6 家国产大模型厂商 |
| 流式输出 | 基于 SSE (Server-Sent Events) 的实时打字机效果 |
| Markdown 渲染 | 支持代码高亮 (highlight.js)、数学公式 (KaTeX) |
| 会话管理 | 服务端会话持久化，支持历史记录回显与清空 |
| API Key 管理 | 浏览器端 localStroage 加密存储，支持自定义 Base URL |
| 深色模式 | 一键切换浅色/深色主题 |
| 限流保护 | 单 IP 每分钟 30 次请求上限，防滥用 |

## 支持厂商与模型

| 厂商 | Provider Code | 模型 |
|---|---|---|
| 深度求索 DeepSeek | `deepseek` | deepseek-v4-pro, deepseek-v4-flash, deepseek-v3.2, deepseek-r1 |
| Moonshot Kimi | `moonshot` | kimi-k2.6, kimi-k2.5, moonshot-v1-128k |
| 阿里通义千问 | `qwen` | qwen3.7-max, qwen-max, qwen-plus, qwen-long |
| 智谱AI GLM | `zhipu` | glm-5.2, glm-5-turbo, glm-4.7-flash, glm-4-flash |
| MiniMax 稀宇 | `minimax` | MiniMax-M3, MiniMax-M2.7, MiniMax-M2.7-highspeed |
| 小米 MiMo | `mimo` | mimo-v2.5-pro, mimo-v2-pro, mimo-v2-flash |

> 厂商与模型列表定义于 `ChatController.providers()`，可扩展。

## 架构设计

### 适配器模式

```
                   ┌──────────────────────┐
                   │   ChatController     │  REST API 层
                   └──────────┬───────────┘
                              │
                   ┌──────────▼───────────┐
                   │    ChatService       │  会话管理 + SSE 推送
                   └──────────┬───────────┘
                              │
                   ┌──────────▼───────────┐
                   │ ModelAdapterFactory  │  自动路由
                   └──────────┬───────────┘
                              │
         ┌────────────────────┼────────────────────┐
         │                    │                    │
  ┌──────▼──────┐    ┌───────▼───────┐    ┌───────▼──────┐
  │ DeepSeek    │    │ MoonshotKimi  │    │  Qwen/GLP/   │
  │ Adapter     │    │ Adapter       │    │  MiniMax/... │
  └─────────────┘    └───────────────┘    └──────────────┘

  所有适配器继承 BaseModelAdapter，实现:
  - getProviderCode()    → 厂商标识
  - buildNativeBody()    → 转换为 OpenAI 兼容 JSON
  - extractContent()     → 解析 SSE delta 文本片段
```

### 请求生命周期

```
Browser                    Controller              Service              Adapter              LLM API
  │                           │                       │                    │                    │
  │  POST /api/chat           │                       │                    │                    │
  │  {provider,model,apiKey}  │                       │                    │                    │
  │──────────────────────────►│                       │                    │                    │
  │                           │  UnifiedChatRequest   │                    │                    │
  │                           │──────────────────────►│                    │                    │
  │                           │                       │  getAdapter(code)  │                    │
  │                           │                       │───────────────────►│                    │
  │                           │                       │                    │  HTTP POST (SSE)  │
  │                           │                       │                    │───────────────────►│
  │                           │                       │                    │                    │
  │                           │                       │    SSE stream      │  ◄── data: chunk   │
  │                           │                       │◄───────────────────│     data: chunk    │
  │                           │                       │                    │     data: [DONE]   │
  │                           │   SseEmitter events   │                    │                    │
  │  ◄── event: chunk ────────│◄──────────────────────│                    │                    │
  │  ◄── event: done  ────────│                       │                    │                    │
```

### 厂商兼容性处理

- **Kimi (kimi-k2.6/kimi-k2.5)**：推理模型只接受 `temperature: 1`，适配器自动省略该字段。流式 delta 使用 `reasoning_content`，`extractContent()` 兼容处理。
- **所有厂商**：统一 OpenAI-compatible 协议，标准 `Bearer` 鉴权、`/chat/completions` 端点、SSE 流式格式。

## 快速开始

### 环境要求

- **JDK 25+**
- **Maven 3.9+**

### 构建与启动

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

### 使用

1. 在页面顶部下拉框选择厂商与模型。
2. 点击 **API Key** 按钮，填入对应厂商的 API Key 与 Base URL（可选）。
3. 在输入框输入消息，回车发送。
4. 点击顶部 `🌙 深色` 切换主题。

## API 参考

### 统一聊天接口

```
POST /api/chat
Content-Type: application/json
Accept: text/event-stream
```

**请求体：**

```json
{
  "provider": "moonshot",
  "modelName": "kimi-k2.6",
  "apiKey": "sk-xxx",
  "baseUrl": "https://api.moonshot.cn/v1",
  "temperature": 0.7,
  "maxTokens": 4096,
  "messages": [
    { "role": "user", "content": "解释什么是机器学习" }
  ]
}
```

**SSE 响应：**

```
event:chunk
data:{"type":"chunk","content":"机器","fullContent":"机器"}

event:chunk
data:{"type":"chunk","content":"学习是","fullContent":"机器学习是"}

event:done
data:{"type":"done","content":"","fullContent":"机器学习是..."}
```

**错误响应：**

```
event:error
data:{"type":"error","errMsg":"HTTP 401: Unauthorized"}
```

### 其他接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/providers` | 获取厂商与模型列表 |
| `GET` | `/api/session` | 创建新会话，返回 `sid` |
| `GET` | `/api/history` | 获取当前会话历史记录 |
| `DELETE` | `/api/chat` | 清空当前会话 |
| `GET` | `/api/config` | 获取服务端配置 |
| `POST` | `/api/config` | 更新服务端配置 |

### 限流

`/api/chat` 接口限制 **30 次/分钟/单 IP**。超限返回 HTTP 429：

```json
{ "error": "请求过于频繁，请稍后再试" }
```

## 配置指南

### 服务端配置

`application.properties`（端口与日志）：

```properties
server.port=8080
logging.level.com.chatbot=INFO
```

### 前端配置存储

API Key、Base URL、模型选择均存储在浏览器 `localStorage`，键名 `modelConfigs`。切换厂商时自动复用已保存的 API Key。

### 添加新厂商

1. 在 `adapter/` 下新建 Adapter 类，继承 `BaseModelAdapter`。
2. 注册为 Spring Bean（`@Component`）。
3. 在 `ChatController.providers()` 中添加厂商信息。

```java
@Component
public class NewProviderAdapter extends BaseModelAdapter {
    @Override
    public String getProviderCode() { return "newprovider"; }

    @Override
    protected String buildNativeBody(UnifiedChatRequest req) throws Exception {
        // 构建 OpenAI 兼容 JSON 请求体
    }
}
```

`ModelAdapterFactory` 通过 Spring DI 自动发现所有 `BaseModelAdapter` Bean。

## 项目结构

```
src/main/java/com/chatbot/
├── Main.java                  # Spring Boot 入口
├── WebConfig.java             # 全局 CORS 配置
├── RateLimitFilter.java       # IP 限流过滤器
├── ConfigManager.java         # 服务端配置文件管理 (~/.deepseek-chatbot/)
├── ConfigController.java      # 配置 REST API
├── ChatController.java        # 聊天 REST API + 厂商列表
├── ChatService.java           # 会话管理 + SSE 推送
├── ChatMessage.java           # 会话消息模型
├── adapter/
│   ├── BaseModelAdapter.java  # 适配器抽象基类（流式调用、SSE 解析）
│   ├── ModelAdapterFactory.java # 适配器自动路由
│   ├── DeepSeekAdapter.java   # DeepSeek 适配器
│   ├── MoonshotKimiAdapter.java # Kimi 适配器
│   ├── QwenAdapter.java       # 通义千问适配器
│   ├── ZhipuGlmAdapter.java   # GLM 适配器
│   ├── MiniMaxAdapter.java    # MiniMax 适配器
│   └── MiMoAdapter.java       # MiMo 适配器
├── model/
│   ├── UnifiedChatRequest.java  # 前端→后端统一请求体
│   └── UnifiedStreamChunk.java  # 后端→前端统一 SSE 数据块
└── ...
src/main/resources/
├── application.properties
└── static/
    └── index.html             # 单页前端（vanilla JS + marked.js + KaTeX + highlight.js）
```

## 开发指南

### 编译与运行

```bash
# 增量编译（应用运行中可用）
mvn compile

# 全量重编译（需先停应用）
mvn clean compile

# 启动
mvn spring-boot:run

# 快速重启（Ctrl+C 停 → 上箭头回车）
```

### 调试 SSE 流

```bash
# 直接调 Kimi API 测试
curl -N https://api.moonshot.cn/v1/chat/completions \
  -H "Authorization: Bearer sk-xxx" \
  -H "Content-Type: application/json" \
  -d '{"model":"kimi-k2.6","stream":true,"max_tokens":200,"messages":[{"role":"user","content":"你好"}]}'
```

### 注意事项

- **不要提交 API Key**：`.gitignore` 已排除 `.deepseek-chatbot/` 配置目录。
- **应用运行时不能 `mvn clean`**：jar 被进程锁定。先停应用再全量重编译。
- **Kimi 推理模型**：`kimi-k2.6`/`kimi-k2.5` 只接受 `temperature: 1`，`MoonshotKimiAdapter` 已自动处理。
- **限流过滤器**：`RateLimitFilter` 仅拦截 `/api/chat`，不影响静态资源和其他 API。

## License

MIT License. 详见 [LICENSE](./LICENSE) 文件。
