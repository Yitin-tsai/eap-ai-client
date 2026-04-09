package com.eap.ai.cli;

import com.eap.ai.service.AiChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;

/**
 * 命令行聊天接口
 * 提供控制台交互方式測試 AI 功能
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ChatCommandLineInterface {

    private final AiChatService aiChatService;

    @EventListener(ApplicationReadyEvent.class)
    public void startCli() {
        // 異步啟動 CLI，避免阻塞主線程
        CompletableFuture.runAsync(this::runInteractiveCli);
    }

    private void runInteractiveCli() {
        try {
            Thread.sleep(2000); // 等待服務完全啟動
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        System.out.println("\n" + "=".repeat(60));
        System.out.println("🤖 EAP AI 助手命令行界面");
        System.out.println("輸入 'exit' 退出，'status' 查看狀態，'help' 查看幫助");
        System.out.println("=".repeat(60));

        try (Scanner scanner = new Scanner(System.in)) {
            while (true) {
                System.out.print("\n👤 您: ");
                String input;
                try {
                    input = scanner.nextLine().trim();
                } catch (NoSuchElementException e) {
                    log.warn("偵測到輸入流關閉，CLI 結束");
                    break;
                }

                if (input.isEmpty()) {
                    continue;
                }

                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input)) {
                    System.out.println("👋 再見！");
                    break;
                }


                if ("help".equalsIgnoreCase(input)) {
                    showHelp();
                    continue;
                }

                // 處理聊天請求
                System.out.println("🤖 正在思考...");
                try {
                    String response = aiChatService.chat(input);
                    System.out.println("\n🤖 AI 助手: " + response);
                } catch (Exception e) {
                    System.out.println("❌ 處理請求時發生錯誤: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("CLI 運行錯誤", e);
        }
    }

    private void showHelp() {
        System.out.println("""
            
            📖 使用說明：
            
            🔧 系統命令：
            • status  - 查看系統狀態
            • help    - 顯示幫助信息
            • exit    - 退出程序
            
            💬 聊天示例：
            • "註冊一個新用戶"
            • "查詢訂單簿前5檔數據"
            • "幫我下一個買單，價格100，數量50"
            • "查詢市場指標"
            • "檢查用戶是否存在"
            
            🌐 Web API：
            • POST http://localhost:8084/api/chat - 聊天接口
            • GET  http://localhost:8084/api/chat/status - 狀態查詢
            • GET  http://localhost:8084/api/chat/health - 健康檢查
            """);
    }
}
