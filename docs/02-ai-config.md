# 02 - 設定 (Configuration) — 將 LLM 與 MCP 連起來

本篇針對 `eap-ai-client` 的設定細節做說明，包含 `application.yml` 的重點、如何配置 MCP client、ChatClient（LLM provider）的 auto-configuration，以及常見的 environment / profile 策略。

目標

- 解釋 `eap-ai-client` 在設定層的主要參數
- 展示如何配置 MCP 的 endpoint 與通訊方式（SSE / HTTP）
- 說明如何用 Spring 的 bean 層級把 ChatClient 與 McpClient 抽成可替換的元件

一、application.yml 範例與說明

下面是一個簡化版的 `application.yml` 範例（請依專案實際值修改）：

```yaml
spring:
  application:
    name: eap-ai-client

ai:
  provider:
    name: "ollama" # or openai etc.
    endpoint: "http://localhost:11434" # sample for Ollama

mcp:
  server:
    base-url: "http://localhost:8083"

logging:
  level:
    com.eap.ai: DEBUG
```

說明：
- `ai.provider`：宣告 LLM provider 與 endpoint，用於 `ChatClientAutoConfig` 決定要建立哪個 ChatClient bean。
- `mcp.server.base-url`：MCP server 的 base URL，`McpClientConfig` 將基於此建立呼叫 MCP 的 client。


二、MCP client 配置（`McpClientConfig`）

下面以專案中實際使用的 `EapMcpProperties` 與 `McpClientConfig` 作為示例，說明如何把 `application.yml` 的設定注入到 MCP client 中：

`EapMcpProperties.java`（Properties 封裝）

```java
@Data
@Component
@ConfigurationProperties(prefix = "eap.mcp")
@Validated
public class EapMcpProperties {

  @NotBlank
  private String baseUrl;

  private String basePath;

  @NotBlank
  private String ssePath;

  @NotBlank
  private String messagePath;

  @Min(1)
  private int timeoutSeconds;

  public Duration getTimeoutDuration() {
    return Duration.ofSeconds(Math.max(1, timeoutSeconds));
  }

  public String getSseUrl() {
    return joinPaths(baseUrl, basePath, ssePath);
  }

  public String getMessageUrl() {
    return joinPaths(baseUrl, basePath, messagePath);
  }

  private String joinPaths(String... parts) {
    StringBuilder sb = new StringBuilder();
    for (String p : parts) {
      if (p == null || p.isEmpty()) continue;
      String normalized = p;
      if (sb.length() == 0) {
        sb.append(normalized.replaceAll("/+$", ""));
      } else {
        sb.append("/").append(normalized.replaceAll("^/+|/+$", ""));
      }
    }
    return sb.toString();
  }
}
```

`McpClientConfig.java`（把 properties 注入並建立 MCP client）

```java

@Configuration
public class McpClientConfig {

  private final EapMcpProperties props;

  public McpClientConfig(EapMcpProperties props) {
    this.props = props;
  }

  @Bean(destroyMethod = "close")
  public McpSyncClient mcpSyncClient(ObjectMapper objectMapper) {
    String sseUrl = props.getSseUrl();
    String msgUrl = props.getMessageUrl();

    System.out.println("=== MCP Client Configuration ===");
    System.out.println("SSE URL: " + sseUrl);
    System.out.println("Message URL: " + msgUrl);
    System.out.println("================================");

    var transport = HttpClientSseClientTransport.builder(props.getBaseUrl())
      .sseEndpoint(props.getSsePath())
      .connectTimeout(props.getTimeoutDuration())
      .build();

    return McpClient.sync(transport)
      .requestTimeout(props.getTimeoutDuration())
      .initializationTimeout(props.getTimeoutDuration())
      .clientInfo(new McpSchema.Implementation("EAP AI Client", "0.1.0"))
      .build();
  }
}
```

小提示 / Tips

- 若要在 `application.yml` 使用上述 properties，請對應鍵值為：

```yaml
eap:
  mcp:
  base-url: "http://localhost:8083"
  base-path: "/mcp"
  sse-path: "/sse"
  message-path: "/message"
  timeout-seconds: 30
```

- 把配置抽成 `EapMcpProperties` 的好處是可以在啟動階段做 validation（例如 `@NotBlank`、`@Min`），啟動時若設定錯誤會快速失敗，方便排查。
- 如果你的使用情境需要同時支援 SSE 與短連 HTTP，建議把 transport 的建立封裝在 `McpClientConfig` 中（如上示例），並在 `McpToolClient` 裡依場景選擇使用同步還是非同步的 client。
- 若其他團隊或使用者要替換 MCP endpoint，請告訴他們把設定放在 environment variable（例如 `EAP_MCP_BASE_URL`）或在 CI/CD 的 secret store 中管理，避免使用硬編碼來寫死URL。


三、如何串接我的ollama模型

本節以 Ollama 為範例，說明如何把一個 provider-specific 的低階 client（例如 `OllamaApi`）注入到 Spring Context，讓 Spring AI 的 provider 建立 `ChatModel`，再由 `ChatClientAutoConfig` 以該 `ChatModel` 建立 `ChatClient`。

下面我們先介紹 `OllamaConfig`（如何提供一個低階 client），再示範如何用 `ChatClientAutoConfig` 將 `ChatModel` 包裝為 `ChatClient`。


我使用 `OllamaConfig`作為負責建立 `OllamaApi` 的 bean，該 bean 是 Spring AI 的 Ollama provider 用來產生 `ChatModel` 的低層實作。簡要流程如下：

1. `OllamaConfig` 在啟動時建立 `OllamaApi`（以 `@Bean` 方法），例如完整實作如下(tips:這邊示範簡單使用`@Value`來取得`application.yml`中的預設url等參數，該方法方便但是如果有多個地方需要使用到這些參數的話建議還是使用上一小節提到的`@ConfigurationProperties`來做設定)：

```java
/**
 * 基本的 Ollama API 設定，交由 Spring AI 自動建立 OllamaChatModel。
 */
@Configuration
@Slf4j
public class OllamaConfig {

  @Bean
  @ConditionalOnMissingBean
  public OllamaApi ollamaApi(
    @Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
    @Value("${spring.ai.ollama.connect-timeout:PT30S}") Duration connectTimeout,
    @Value("${spring.ai.ollama.read-timeout:PT2M}") Duration readTimeout
  ) {
    log.info("配置 Ollama API，服務 URL: {}，connectTimeout: {}，readTimeout: {}", baseUrl, connectTimeout, readTimeout);

    // Blocking client設定 (RestClient)
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
    requestFactory.setReadTimeout((int) readTimeout.toMillis());

    RestClient.Builder restClientBuilder = RestClient.builder()
      .baseUrl(baseUrl)
      .requestFactory(requestFactory);

    // Reactive client設定 (WebClient)
    HttpClient httpClient = HttpClient.create()
      .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
      .responseTimeout(readTimeout)
      // 關閉 wiretap 以避免在日誌輸出大量的 hex/tcp chunk 資訊（可根據需要改回 true 以 debug）
      .wiretap(false)
      .doOnConnected(conn -> conn
        .addHandlerLast(new ReadTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS))
        .addHandlerLast(new WriteTimeoutHandler(readTimeout.toMillis(), TimeUnit.MILLISECONDS)));

    WebClient.Builder webClientBuilder = WebClient.builder()
      .baseUrl(baseUrl)
      .clientConnector(new ReactorClientHttpConnector(httpClient));

    return new OllamaApi(baseUrl, restClientBuilder, webClientBuilder);
  }
}
```

2. Spring AI 的 provider (在 classpath 中) 偵測到 `OllamaApi` 或其他必要的低層 client 後，會建立一個 `ChatModel`（或提供一個 `ChatModel` bean）。

3. `ChatClientAutoConfig` 使用 `ObjectProvider<ChatModel>` 取得可用的 `ChatModel`，並呼叫 `ChatClient.create(model)` 建立 `ChatClient`。

因此，只要 `OllamaConfig` 成功在 ApplicationContext 中註冊了 `OllamaApi`，且 spring-ai 的 provider 能使用該 `OllamaApi` 轉出 `ChatModel`，`ChatClientAutoConfig` 就會自動建立 `ChatClient`。若 auto-config 沒建立，通常是因為 `OllamaApi` 未註冊、provider 不在 classpath、或有其他 bean 條件未滿足（可參考上方的小提示做偵錯）。

下面介紹我的 `ChatClientAutoConfig` 實作，說明如何以 Spring Auto-Configuration 的方式建立一個可替換的 `ChatClient` bean：

```java


@Configuration
@Slf4j
public class ChatClientAutoConfig {

  @Bean
  @ConditionalOnMissingBean
  public ChatClient chatClient(ObjectProvider<ChatModel> chatModelProvider) {
    ChatModel model = chatModelProvider.getIfAvailable();
    if (model != null) {
      log.info("Creating ChatClient from available ChatModel: {}", model.getClass().getName());
      return ChatClient.create(model);
    }
    log.warn("No ChatModel available to create ChatClient. ChatClient bean will not be created.");
    return null;
  }
}
```

小提示 / Tips

- `@ConditionalOnMissingBean` 可以讓使用者在需要時以自己的 `ChatClient` bean 覆寫自動建立的 bean（例如在測試環境使用 mock）。
- 若你的專案需要支援多個 provider（Ollama、OpenAI 等），可以在這個自動配置中加入 `@ConditionalOnProperty(name = "ai.provider.name", havingValue = "ollama")` 類似的判斷，或把 provider-specific 的 factory 抽成不同的 `@Configuration` 類別。
- 如果發生 auto-config 被 shadow 的問題（例如你自己建立了某個`ChatClient`的bean），建議建立一個 adapter 層把自己建立的 client 包裝成 `ChatClient`，確保上層只依賴 `ChatClient` 介面。

四、properties 與環境切換

- 建議把敏感設定（API key、endpoint）放在 environment variables 或 secret manager，而非直接寫在 repo 的 `application.yml`。
- 使用 Spring Profile（例如 `dev`, `staging`, `prod`）來管理不同環境的配置差異（例如：在 `dev` profile 允許自動註冊 user；在 `prod` profile 關閉）。


五、下一篇預告（實作）

下一篇會用程式碼示範 `AiChatService` 的解析流程、`McpToolClient` 的呼叫 API，以及如何在 AiChatService 中加入 execution gate（簡單的 userId 檢查與 dry-run 支援）。

---
