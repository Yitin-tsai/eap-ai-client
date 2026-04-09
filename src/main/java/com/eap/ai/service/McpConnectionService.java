package com.eap.ai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Service;

/**
 * 啟動時檢查 LLM 模型與 MCP 服務狀態。
 */
@Service
@Slf4j
public class McpConnectionService implements CommandLineRunner {

    private final ChatModel chatModel;
    private final ToolCallbackProvider toolCallbackProvider;

    public McpConnectionService(ChatModel chatModel,
                                @Autowired(required = false) ToolCallbackProvider toolCallbackProvider) {
        this.chatModel = chatModel;
        this.toolCallbackProvider = toolCallbackProvider;
    }

    @Override
    public void run(String... args) {
        log.info("=== EAP AI Client 啟動檢查 ===");
        checkLlmConnection();
        checkMcpTools();
        log.info("=== 啟動檢查完成 ===");
    }

    private void checkLlmConnection() {
        try {
            log.info("正在檢查 Ollama 連接...");
            String response = chatModel.call("Hello");

            if (response != null && !response.trim().isEmpty()) {
                log.info("✅ Ollama 連線成功 (回應長度 {} 字符)", response.length());
            } else {
                log.warn("⚠️ Ollama 回應為空");
            }

        } catch (Exception e) {
            log.error("❌ Ollama 連線失敗: {}", e.getMessage());
            log.warn("請確認: ollama serve 已啟動，且已執行 ollama pull llama3.1");
        }
    }

    private void checkMcpTools() {
        if (toolCallbackProvider == null) {
            log.warn("⚠️ MCP Client 已禁用，跳過工具檢查");
            return;
        }

        try {
            log.info("正在檢查 MCP 工具...");
            ToolCallback[] tools = toolCallbackProvider.getToolCallbacks();

            if (tools != null && tools.length > 0) {
                log.info("✅ MCP 連線成功，取得 {} 個工具", tools.length);
                for (ToolCallback tool : tools) {
                    log.info("  • {} - {}",
                        tool.getToolDefinition().name(),
                        tool.getToolDefinition().description());
                }
            } else {
                log.warn("⚠️ MCP 服務可達，但工具列表為空");
            }

        } catch (Exception e) {
            log.error("❌ MCP 連線失敗: {}", e.getMessage());
            log.warn("請確認 eap-mcp 服務運行於 http://localhost:8083");
        }
    }
}
