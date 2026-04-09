# 03 - 實作 (Implementation) — AiChatService、McpToolClient 與執行流程

本篇聚焦 `eap-ai-client` 的實作細節：說明 `AiChatService` 的 prompt 組裝與模型回覆解析、`McpToolClient` 的通用呼叫介面、以及在 ai-agent 中加入基本的 execution gate（驗證、dry-run、審計）。本文會用程式片段示範核心邏輯，並給出測試策略。

目標

- 介紹 `AiChatService` 的核心流程（prompt → model → parse → execute）
- 提供 `McpToolClient.callTool(...)` 的範例實作與回傳標準化
- 展示 execution gate 的簡單實作（userId 檢查、dry-run）
- 提供單元與整合測試策略

一、AiChatService 的核心流程

`AiChatService` 是 ai-client 的心臟，負責：

1. 組裝 prompt 並呼叫 ChatClient
2. 解析 model 回覆成 actions/plan
3. 針對每個 action 做驗證（execution gate）
4. 呼叫 `McpToolClient` 執行工具，並收集回傳給使用者

下面示範專案中實際使用的 `AiChatService`（：

```java
@Service
@Slf4j
@RequiredArgsConstructor
public class AiChatService {

  private final ChatClient chatClient;
  private final McpToolClient mcpToolClient;
  private final ObjectMapper objectMapper;

  private static final Set<String> READ_ONLY = Set.of(
    "getOrderBook", "getMarketMetrics", "getUserWallet", "getUserOrders", "checkUserExists", "exportReport");
  private static final Set<String> STATE_CHANGING = Set.of(
    "placeOrder", "cancelOrder", "registerUser", "runSimulation");

  /** 主提示：允許 JSON 規劃或純文字工具執行結果 */
  private static final String SYSTEM_PROMPT = """
      你是 EAP 電力交易平台的「工具執行規劃器」。首選輸出格式為可執行的 JSON 物件；但為了實務彈性，系統也接受直接以純文字（或簡短表格/清單）回傳工具執行結果。當你能產出結構化規劃時，請仍優先輸出 JSON（見下方格式）；若情境更適合直接回傳工具結果（例如查詢後的表格或文字說明），也可直接回傳純文字結果。

      【首選 - 結構化輸出格式 (機器可直接執行)】
      {
        "actions": [ { "action": "<toolName>", "arguments": {…} }, ... ],
        "final_answer": ""
      }

      【放寬規則 — 若回傳 JSON，請遵守下列要點】
      - 若輸出 JSON，請僅輸出單一 JSON 物件，勿夾帶額外文字或 ``` 標記。
      - 欄位大小寫固定：actions, action, arguments, final_answer。
      - 參數 price、qty 建議以**字串**回傳；嚴禁千分位（"7000" ✓ / "7,000" ✗）。
      - side 建議為 "BUY" 或 "SELL"（大寫）。
      - 若缺少必要參數：
        - 查詢類（唯讀）可使用安全預設值直接執行；
        - 會改變狀態的工具請直接提供完整參數，否則回傳錯誤。
      - 若無法產出合法規劃，輸出：{"final_answer":"錯誤: 規劃無效或缺參數"}

      【次選 - 純文字工具結果】
      - 當你直接回傳工具執行結果（非 JSON 規劃）時，請以簡潔清楚的文字、表格或列點呈現，並在可能處提供對應的工具名稱與主要參數，例：
        "getOrderBook(depth=20) -> 表格: ..." 或
        "getMarketMetrics -> price=7000, spread=5"
      - 系統會嘗試從文字中擷取足夠資訊以供紀錄與顯示，但不會自動將自然語言轉為下單指令。

      【可用工具與參數】
      - getOrderBook -> arguments: {} | {"depth": number}
      - getMarketMetrics -> arguments: {}
      - getUserWallet -> {"userId":"string"}
      - getUserOrders -> {"userId":"string"}
      - placeOrder -> {"userId":"string","side":"BUY|SELL","price":"string","qty":"string","symbol":"string"}
      - cancelOrder -> {"orderId":"string"}
      - registerUser -> {}
      - runSimulation -> {"strategy":"string","symbol":"string","steps":number,
        "userId":"string","threshold":number,"qty":number,"priceStrategy":"topBid|mid|topAsk",
        "sides":"BUY|SELL|BOTH","ordersPerStep":number}
      - exportReport -> {"id":"string"}  // returns most recent SimulationResult when id omitted

      【語義對應建議】
      - 「訂單簿/買賣單/order book/五檔/十檔/深度」→ getOrderBook（若文本含「前N檔」，則 depth=N）
      - 「市場/市況/行情/指標/metrics」→ getMarketMetrics
      - 「下單/成交/取消」→ placeOrder/cancelOrder

      【最小範例 (JSON 規劃)】
      {"actions":[{"action":"getOrderBook","arguments":{"depth":20}}],"final_answer":""}
      """;
      }
```

類別與主要欄位說明：
- `chatClient`：注入的 Spring AI `ChatClient`，負責向 LLM 發送 prompt 並取得回覆。
- `mcpToolClient`：自製的 MCP 呼叫 wrapper，用來統一呼叫各種 MCP 工具（HTTP/SSE/Feign 實作皆可）。
- `objectMapper`：Jackson，用於 JSON 解析與建立回傳結構。
- `SYSTEM_PROMPT`：我的主要prompt，在試過許多prompt後我發現需要明確要求 LLM 優先輸出結構化 JSON 計畫，並定義工具名稱與參數格式才能讓LLM model知道要如何正確呼叫我的mcp tool 若沒有明確的prompt LLM會"假裝"他有呼叫mcp tool然後給你一個假的的下單回傳，明確的規範他回傳一個整合好的`plan`然後後續在程式碼中會執行這個plan來呼叫mcp tools。

```java
// chat 方法片段
public String chat(String userMessage) {
  try {
    log.info("收到用戶訊息: {}", userMessage);

    String prompt = SYSTEM_PROMPT + "\n使用者提問：" + userMessage;
    String modelOut = chatClient.prompt(prompt).call().content();
    log.info("模型回應: {}", modelOut);
    Plan plan = parsePlanStrict(modelOut);
    if (plan == null || plan.actions().isEmpty()) {
      return modelOut == null ? "錯誤：未取得模型回應" : modelOut;
    }

    ObjectNode execRes = executePlan(plan);
    return execRes.toPrettyString();

  } catch (Exception e) {
    log.error("處理失敗", e);
    return "處理請求時發生錯誤：" + e.getMessage();
  }
}
```

chat 方法說明：
- 目的：AiChatService 的主要入口，將使用者輸入交給 LLM 生成執行計畫，解析後執行 MCP 工具並回傳聚合結果。
- 行為要點：
  1. 合成 `SYSTEM_PROMPT` 與使用者訊息並呼叫 `ChatClient`。
  2. 使用 `parsePlanStrict` 解析模型回覆；若解析失敗，直接回傳模型原始回覆（便於 prompt 調整）。
  3. 呼叫 `executePlan` 並回傳 pretty JSON。

```java
// parsePlanStrict 與 tryParseAsPlan 片段
private record Plan(List<ObjectNode> actions) {}

private Plan parsePlanStrict(String text) {
  if (text == null || text.isBlank())
    return null;

  Plan p = tryParseAsPlan(text);
  if (p != null)
    return p;

  var fence = Pattern.compile("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```",
      Pattern.CASE_INSENSITIVE);
  var m1 = fence.matcher(text);
  if (m1.find()) {
    p = tryParseAsPlan(m1.group(1));
    if (p != null)
      return p;
  }

  int s = text.indexOf('{');
  while (s >= 0) {
    int depth = 0;
    for (int i = s; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '{')
        depth++;
      else if (c == '}' && --depth == 0) {
        String cand = text.substring(s, i + 1);
        p = tryParseAsPlan(cand);
        if (p != null)
          return p;
        break;
      }
    }
    s = text.indexOf('{', s + 1);
  }
  return null;
}

private Plan tryParseAsPlan(String json) {
  try {
    JsonNode root = objectMapper.readTree(json);
    JsonNode arr = root.path("actions");
    if (!arr.isArray() || arr.size() == 0)
      return null;
    List<ObjectNode> acts = new ArrayList<>();
    for (JsonNode n : arr)
      if (n.isObject())
        acts.add((ObjectNode) n);
    return new Plan(acts);
  } catch (Exception ignore) {
    return null;
  }
}
```

parsePlanStrict / tryParseAsPlan 說明：
- 目的：容錯地從模型回覆中抽取可執行的 JSON 計畫（Plan），支援直接 JSON、被 ```json 包裹的 JSON，以及雜訊中夾帶的 JSON 片段。
- 行為要點：先嘗試 parse 整體，再尋找 code fence，最後做大括號配對逐一嘗試最內層 JSON。
- 測試要點：測試各類回覆格式（純 JSON、code fence、雜訊混雜）都能正確識別或在失敗時回傳 null。

```java
// executePlan 片段
private ObjectNode executePlan(Plan plan) {
  ObjectNode results = objectMapper.createObjectNode();

  for (ObjectNode a : plan.actions()) {
    String name = a.path("action").asText();
    ObjectNode args = a.has("arguments") && a.get("arguments").isObject()
        ? (ObjectNode) a.get("arguments")
        : objectMapper.createObjectNode();

    normalizeArgs(args);

    boolean isReadOnly = READ_ONLY.contains(name);
    boolean isStateChanging = STATE_CHANGING.contains(name);

    String missing = validateRequiredParams(name, args);
    if (missing != null) {
      ObjectNode err = objectMapper.createObjectNode();
      err.put("error", "missing parameter: " + missing);
      results.set(name, err);
      continue;
    }

    if (isReadOnly || isStateChanging) {
      log.info("Calling MCP tool {} with args {}", name, args.toString());
      results.set(name, safeCall(name, args));
      continue;
    }

    results.put(name, "ERROR: unsupported tool");
  }
  return results;
}
```

executePlan 說明：
- 目的：逐一執行 Plan 中的 action，並把每個工具的結果聚合為回傳物件。
- 行為要點：參數正規化 -> 必要參數檢查 -> 呼叫 MCP 工具（safeCall）-> 聚合結果。
- 錯誤策略：單一工具失敗或拋例外只會在該工具欄位回傳 error，不會中斷其他工具執行。

```java
// validateRequiredParams 片段
private String validateRequiredParams(String name, ObjectNode args) {
  if ("placeOrder".equals(name)) {
    if (!args.hasNonNull("userId"))
      return "userId";
    if (!args.hasNonNull("side"))
      return "side";
    if (!args.hasNonNull("price"))
      return "price";
    if (!args.hasNonNull("qty"))
      return "qty";
    if (!args.hasNonNull("symbol"))
      return "symbol";
  }
  if ("cancelOrder".equals(name)) {
    if (!args.hasNonNull("orderId"))
      return "orderId";
  }
  if ("getUserWallet".equals(name) || "getUserOrders".equals(name)) {
    if (!args.hasNonNull("userId"))
      return "userId";
  }
  if ("runSimulation".equals(name)) {
    if (!args.hasNonNull("userId") || args.get("userId").asText().isBlank())
      return "userId";

    try {
      ObjectNode check = objectMapper.createObjectNode();
      check.put("userId", args.get("userId").asText());
      JsonNode res = mcpToolClient.callTool("checkUserExists", check);

      boolean exists = false;
      if (res != null) {
        if (res.isBoolean())
          exists = res.asBoolean();
        else if (res.isObject() && res.has("exists"))
          exists = res.path("exists").asBoolean();
      }

      if (!exists) {
        try {
          log.info("userId {} not found — attempting to register new user", args.get("userId").asText());
          ObjectNode empty = objectMapper.createObjectNode();
          JsonNode reg = mcpToolClient.callTool("registerUser", empty);
          if (reg != null && reg.isObject() && reg.path("success").asBoolean(false)) {
            String newId = reg.path("userId").asText(null);
            if (newId != null && !newId.isBlank()) {
              args.put("userId", newId);
              log.info("auto-registered userId={}", newId);
            } else {
              return "userId (registration succeeded but missing id)";
            }
          } else {
            String msg = reg != null && reg.has("message") ? reg.path("message").asText() : "registration failed";
            return "userId (not found and registration failed: " + msg + ")";
          }
        } catch (Exception rex) {
          log.error("auto-register failed", rex);
          return "userId (not found and registration attempt failed: " + rex.getMessage() + ")";
        }
      }
    } catch (Exception e) {
      log.warn("checkUserExists failed for {}: {}", args.get("userId").asText(), e.getMessage());
    }
  }
  return null;
}
```

validateRequiredParams 說明：
- 目的：在呼叫 MCP 前先驗證工具所需參數，降低錯誤呼叫及不必要的 side-effect。
- 重要行為：對 runSimulation 嘗試進行 `checkUserExists`，若不存在則嘗試 `registerUser`（成功會更新 args 中的 userId）。

```java
// safeCall 與 normalizeArgs 片段
private JsonNode safeCall(String tool, ObjectNode args) {
  try {
    JsonNode res = mcpToolClient.callTool(tool, args);
    return res == null ? objectMapper.nullNode() : res;
  } catch (Exception e) {
    ObjectNode err = objectMapper.createObjectNode();
    err.put("error", e.getMessage());
    return err;
  }
}

private void normalizeArgs(ObjectNode args) {
  if (args == null)
    return;

  if (args.has("price")) {
    args.put("price", args.get("price").asText().replace(",", "").trim());
  }
  if (args.has("qty")) {
    args.put("qty", args.get("qty").asText().replace(",", "").trim());
  }
  if (args.has("userId") && !args.get("userId").isTextual()) {
    args.put("userId", args.get("userId").asText());
  }
  if (args.has("side") && args.get("side").isTextual()) {
    String s = args.get("side").asText();
    if ("buy".equalsIgnoreCase(s))
      args.put("side", "BUY");
    if ("sell".equalsIgnoreCase(s))
      args.put("side", "SELL");
  }
  if (args.has("symbol") && args.get("symbol").isTextual()) {
    args.put("symbol", args.get("symbol").asText().toUpperCase());
  }
}
```

safeCall / normalizeArgs 說明：
- `safeCall`：保護性呼叫 MCP，任何例外都會被捕捉並回傳含 `error` 欄位的物件，避免中斷整體 Plan。測試時可模擬例外來驗證錯誤轉換。
- `normalizeArgs`：針對常見格式問題進行轉換（移除千分位、轉大寫、確保 userId 為字串），可減少模型輸出格式差異導致的呼叫失敗。


二、McpToolClient — 通用呼叫介面範例

`McpToolClient` 提供一個統一的呼叫方式，把呼叫細節（HTTP、SSE、Feign）抽象化：

```java
public class McpToolClient {
  private final WebClient webClient;

  public ToolResponse callTool(String toolName, Map<String,Object> args) {
    // build request body
    // POST to {mcpBaseUrl}/tools/{toolName}/invoke
    // parse response into ToolResponse
  }
}
```

標準化 `ToolResponse` 可以包含：status, data, errorCode, errorMessage。

三、簡短小結

本文以 `AiChatService` 為核心，展示了：

- 如何組裝 prompt 與要求 LLM 回傳結構化的執行計畫（Plan）；
- 如何容錯解析模型回覆（支援純 JSON、code fence、以及雜訊中的 JSON 片段）；
- 如何正規化與驗證 action arguments，並以 `McpToolClient` 呼叫 MCP 工具把每個 action 的結果聚合回傳。


