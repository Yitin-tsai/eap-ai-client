# 01 - 建置 (Build) — 建立與編譯 ai-service

本篇說明如何為 `eap-ai-client`（ai-service / ai-agent 的起點）準備專案結構、相依套件與本地建置流程。內容包含：模組結構說明、重要依賴、如何在本機啟動、以及快速驗證步驟。若你已熟悉 Java + Spring Boot 的開發流程，可以把本文當成快速上手指南。

目標

- 描述 `eap-ai-client` 的檔案與 package 結構
- 列出主要的 build 相依（Gradle）以及它們的用途
- 提供本地 build、啟動與簡單 smoke-test 的步驟

一、專案與目錄結構（概要）

`eap-ai-client` 是一個 Spring Boot 應用，負責與 LLM（ChatClient）互動、解析模型回覆、並以 MCP client 呼叫 `eap-mcp` 中的工具。目錄結構（已截斷）如下：

- `build.gradle` — Gradle 配置
- `src/main/java/com/eap/ai` — 應用程式碼
  - `config/` — ChatClient 與 MCP client 的 auto-config 與 properties
  - `controller/` — 提供 HTTP endpoint（例如 `AiChatController`）給外部系統或 CLI 呼叫
  - `service/` — 核心邏輯（`AiChatService`、`McpToolClient`、`McpConnectionService`）
- `src/main/resources/application.yml` — 本地/預設設定
- `docs/` — 專案內部文件（本系列文章會放在此）

二、重要相依（build.gradle）

`eap-ai-client/build.gradle` 內的關鍵依賴應包含（此段落以你現有專案為基礎）：

- Spring Boot Starter（web, validation, actuator）
- Spring AI / ChatClient（或專案使用的 LLM provider 的 starter）
- Feign（若 ai-client 直接呼叫下游 HTTP）或 WebClient（reactive 呼叫）
- Jackson（JSON 處理）
- Lombok（減少樣板程式碼）
- JUnit / Mockito（測試）

在 build.gradle 中注意：
- 把 `org.springframework.ai` (或你使用的 ChatClient) 的版本與 Spring Boot 的版本匹配
- 若要使用 Feign，確保 `@EnableFeignClients` 與正確的 `dependencyManagement` 設定

三、本地建置與啟動

1) 下載相依並 build

在專案根目錄或模組目錄執行：

```bash
./gradlew :eap-ai-client:build
```

這會編譯程式、執行測試並產生 jar 檔。

2) 執行（開發模式）

- 使用 `bootRun`：

```bash
./gradlew :eap-ai-client:bootRun
```

- 或執行已產生的 jar：

```bash
java -jar eap-ai-client/build/libs/eap-ai-client-0.0.1-SNAPSHOT.jar
```

3) 啟動前確認的環境變數與依賴

- LLM provider（例如 Ollama / OpenAI）連線設定：在 `src/main/resources/application.yml` 或環境變數中設定 API key、endpoint 等。
- MCP server endpoint（若要在本地測試與 `:eap-mcp` 串接）：在 `application.yml` 設定 `mcp.server.base-url` 指向 `http://localhost:8083`（或你部署的 MCP 地址）。

四、快速 smoke-test

啟動後（假設 `AiChatController` 有 HTTP endpoint），你可以用 curl 或 Postman 發一個簡單的 prompt 來驗證：

```bash
curl -X POST "http://localhost:8082/ai/chat" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"get orderbook for ELC"}'
```

預期行為：
- 系統會呼叫 ChatClient 並解析回覆。
- 若 model 回覆包含 action，AiChatService 會嘗試呼叫 `McpToolClient` 去執行對應的工具。


五、下一篇預告

下一篇會深入 `eap-ai-client` 的設定（application.yml、ChatClient auto-config、MCP client config），並示範如何把 LLM provider 與 MCP client 的配置抽成可測試、可切換的 bean。建議你在繼續前先用 `bootRun` 啟動一次並確認基本 endpoint 可以運作。

