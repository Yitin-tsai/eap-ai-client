package com.eap.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * AI 聊天服務 - 使用 Spring AI 1.1.x 原生 Function Calling
 */
@Service
@Slf4j
public class AiChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final ToolCallbackProvider toolCallbackProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
        你是 EAP 電力交易平台助手。
        以繁體中文簡潔回應用戶的問題。
        當需要執行工具時，請使用正確的工具呼叫格式。
        """;

    public AiChatService(ChatClient.Builder chatClientBuilder,
                         @Autowired(required = false) ToolCallbackProvider toolCallbackProvider,
                         ChatMemory chatMemory) {
        this.chatMemory = chatMemory;
        this.toolCallbackProvider = toolCallbackProvider;

        var builder = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new SimpleLoggerAdvisor()
                );

        // 只有當 MCP 工具可用時才注入
        if (toolCallbackProvider != null) {
            builder.defaultToolCallbacks(toolCallbackProvider);
            log.info("AiChatService initialized WITH MCP tools");
        } else {
            log.warn("AiChatService initialized WITHOUT MCP tools (MCP Client disabled)");
        }

        this.chatClient = builder.build();
    }

    public String chat(String userMessage) {
        try {
            log.info("收到用戶訊息: {}", userMessage);

            String response = chatClient.prompt()
                    .user(userMessage)
                    .call()
                    .content();

            log.info("AI 回應: {}", response);

            // 檢查是否為文字格式的 tool call (llama3.1 常見問題)
            String toolCallResult = tryParseAndExecuteToolCall(response);
            if (toolCallResult != null) {
                return toolCallResult;
            }

            return response;

        } catch (Exception e) {
            log.error("處理聊天訊息時發生錯誤", e);
            return "處理請求時發生錯誤：" + e.getMessage();
        }
    }

    /**
     * 嘗試解析並執行文字格式的 tool call
     * 當 LLM 輸出 {"name": "toolName", "parameters": {...}} 格式時手動執行
     */
    private String tryParseAndExecuteToolCall(String response) {
        if (response == null || toolCallbackProvider == null) {
            return null;
        }

        // 檢查是否為 JSON 格式
        String trimmed = response.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return null;
        }

        try {
            JsonNode root = objectMapper.readTree(trimmed);

            // 檢查是否包含 name 和 parameters 欄位
            if (!root.has("name") || !root.has("parameters")) {
                return null;
            }

            String toolName = root.get("name").asText();
            JsonNode parameters = root.get("parameters");

            log.info("偵測到文字格式的 tool call: {}", toolName);

            // 找到對應的工具
            ToolCallback targetTool = null;
            for (ToolCallback tool : toolCallbackProvider.getToolCallbacks()) {
                if (tool.getToolDefinition().name().equals(toolName)) {
                    targetTool = tool;
                    break;
                }
            }

            if (targetTool == null) {
                log.warn("找不到工具: {}", toolName);
                return null;
            }

            // 準備參數並執行工具
            String toolInput = objectMapper.writeValueAsString(parameters);
            log.info("執行工具 {} 參數: {}", toolName, toolInput);

            String result = targetTool.call(toolInput);
            log.info("工具執行結果: {}", result.length() > 500 ? result.substring(0, 500) + "..." : result);

            // 格式化結果回傳
            return "已執行 " + toolName + " 工具。\n\n結果:\n" + result;

        } catch (Exception e) {
            log.debug("解析 tool call 失敗 (可能不是 tool call): {}", e.getMessage());
            return null;
        }
    }

    public String chat(String userMessage, String conversationId) {
        try {
            log.info("收到用戶訊息 [conversationId={}]: {}", conversationId, userMessage);

            String response = chatClient.prompt()
                    .user(userMessage)
                    .advisors(advisor -> advisor.param(
                            "chat_memory_conversation_id",
                            conversationId))
                    .call()
                    .content();

            log.info("AI 回應 [conversationId={}]: {}", conversationId, response);
            return response;

        } catch (Exception e) {
            log.error("處理聊天訊息時發生錯誤", e);
            return "處理請求時發生錯誤：" + e.getMessage();
        }
    }

    public void clearMemory(String conversationId) {
        chatMemory.clear(conversationId);
        log.info("已清除會話記憶: {}", conversationId);
    }
}
