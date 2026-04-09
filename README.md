# EAP AI Client - Spring AI MCP Local Client

本地 AI 聊天客戶端，整合 EAP MCP 服務和 Ollama 本地 LLM。

## 🚀 快速啟動

### 1. 確保依賴服務運行
```bash
# 啟動 PostgreSQL, RabbitMQ, Redis
docker-compose up -d

# 啟動 EAP 核心服務
cd /Users/cfh00909120/eap
./gradlew :eap-matchEngine:bootRun &
./gradlew :eap-order:bootRun &
./gradlew :eap-wallet:bootRun &
./gradlew :eap-mcp:bootRun &
```

### 2. 確保 Ollama 和模型
```bash
# 啟動 Ollama（如果未啟動）
ollama serve

# 下載模型（如果未下載）
ollama pull llama3.1

# 驗證模型
ollama list
```

### 3. 啟動 AI 客戶端
```bash
cd /Users/cfh00909120/eap
./gradlew :eap-ai-client:bootRun
```

## 🎯 功能特性

### 命令行聊天界面
啟動後自動進入交互模式。模型會先依照系統提示輸出 JSON 指令（指定要呼叫的 MCP 工具），程式收到後實際打 MCP API，再把工具結果回饋給模型產出最終回答。

### 對話格式
- 第一步：模型回傳 `{"action":"toolName","arguments":{...}}`
- 第二步：程式呼叫 MCP `/mcp/tools/{toolName}/call`
- 第三步：模型收到結果後回傳 `{"final_answer":"..."}` 供使用者閱讀
若模型未請求工具，會直接回傳 `final_answer`。

### LLM 互動指引（已更新）

目前 AI client 不再強制模型輸出嚴格格式的 JSON 計畫；建議做法：

- 直接請模型執行任務（例如「幫我執行模擬」）。模型可以呼叫 MCP 工具並直接在回覆中返回該工具的輸出（文字或結構化資料）。
- 如果模型描述如何呼叫工具，最終應該以 MCP `/mcp/tools/{toolName}/call` 發起請求，並把工具結果回傳給使用者。
- 注意：live 下單（例如 `placeOrder` 並且 `executeReal=true`）會改變系統狀態，請僅在受控環境或啟用安全閘時使用。

範例：呼叫 `runSimulation`（curl）
```bash
curl -X POST http://localhost:8083/mcp/tools/runSimulation/call \
  -H 'Content-Type: application/json' \
  -d '{"arguments": {"symbol":"ELC","steps":10,"threshold":0.02,"qty":10,"priceStrategy":"topBid","sides":"BOTH","ordersPerStep":2,"executeReal":false,"userId":"test-user"}}'
```

範例：匯出最近的模擬報表（MVP）
```bash
curl -X POST http://localhost:8083/mcp/tools/exportReport/call \
  -H 'Content-Type: application/json' \
  -d '{"arguments": {"id":"latest"}}'
```

備註：詳細的系統提示（SYSTEM_PROMPT）與交互行為，請檢查 `AiChatService.SYSTEM_PROMPT` 的實作以取得最新文檔或自定義提示。

### REST API 接口
- **聊天**: `POST http://localhost:8084/api/chat`
  ```json
  {
    "message": "查詢市場指標"
  }
  ```

- **狀態查詢**: `GET http://localhost:8084/api/chat/status`
- **健康檢查**: `GET http://localhost:8084/api/chat/health`

## 🛠️ 可用工具

AI 助手可以使用以下 MCP 工具：

### 用戶管理
- `registerUser` - 註冊新用戶
- `getUserWallet` - 查詢錢包狀態
- `checkUserExists` - 檢查用戶存在性

### 交易操作
- `placeOrder` - 下單交易
- `getUserOrders` - 查詢用戶訂單
- `cancelOrder` - 取消訂單

### 市場數據
- `getOrderBook` - 獲取訂單簿
- `getMarketMetrics` - 獲取市場指標

## 🔧 配置說明

### application.yml 主要配置
```yaml
spring:
  ai:
    ollama:
      base-url: http://localhost:11434
      connect-timeout: PT30S
      read-timeout: PT2M
      chat:
        options:
          model: llama3.1
          temperature: 0.7
          top-p: 0.9
          max-tokens: 1000

eap:
  mcp:
    base-url: http://localhost:8083
    base-path: /mcp
    sse-path: /sse
    timeout-seconds: 60
```

### 自定義配置
可以修改以下參數：
- `model`: 更換 Ollama 模型
- `temperature` / `top-p` / `max-tokens`: 調整模型回應行為
- `eap.mcp.base-url`: MCP 伺服器基底網址（預設 http://localhost:8083）
- `eap.mcp.base-path`、`eap.mcp.sse-path`: MCP 協議路徑（預設 `/mcp` + `/sse`）
- `eap.mcp.timeout-seconds`: MCP 呼叫逾時時間
- `connect-timeout`、`read-timeout`: 模型端連線與回應等待時間（ISO-8601 Duration 格式）

## 🧪 測試示例

### 命令行測試
```bash
# 系統狀態
status

# 用戶管理
註冊一個新用戶
檢查用戶 123e4567-e89b-12d3-a456-426614174000 是否存在

# 交易操作
幫我下一個買單，價格100，數量50，用戶ID是 xxx
查詢用戶 xxx 的所有訂單

# 市場查詢
顯示當前訂單簿前5檔數據
獲取最新的市場指標
```

### API 測試
```bash
# 聊天測試
curl -X POST http://localhost:8084/api/chat \
  -H "Content-Type: application/json" \
  -d '{"message": "註冊一個新用戶"}'

# 狀態檢查
curl http://localhost:8084/api/chat/status
```

## 🐛 故障排除

### 常見問題

1. **MCP 連接失敗**
   - 確認 eap-mcp 服務在 8083 端口運行
   - 檢查 MCP 健康狀態：`curl http://localhost:8083/mcp/health`
   - 檢視工具列表：`curl http://localhost:8083/mcp/tools`

2. **Ollama 連接失敗**
   - 確認 Ollama 服務運行：`curl http://localhost:11434/api/tags`
   - 檢查模型是否下載：`ollama list`

3. **工具調用失敗**
   - 檢查後端服務是否全部啟動
   - 確認資料庫連線正常並具備測試資料
   - 檢查模型輸出的 JSON 是否包含正確的工具名稱與參數

### 日誌級別
```yaml
logging:
  level:
    com.eap.ai: DEBUG
    org.springframework.ai: DEBUG
```

## 📈 性能優化

### Ollama 優化
```bash
# 預加載模型（避免首次調用延遲）
ollama run llama3.1 "hello"

# GPU 加速（如果支持）
OLLAMA_GPU=nvidia ollama serve
```

### 內存配置
```bash
# JVM 內存設置
export JAVA_OPTS="-Xms512m -Xmax2g"
./gradlew :eap-ai-client:bootRun
```
