package com.eap.ai.controller;

import com.eap.ai.service.AiChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 聊天 REST API 控制器
 * 提供 HTTP 接口與 AI 助手交互
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AiChatController {

    private final AiChatService aiChatService;

    /**
     * 聊天端點
     * POST /api/chat
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> request) {
        try {
            String userMessage = request.get("message");
            if (userMessage == null || userMessage.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "訊息不能為空"
                ));
            }

            String response = aiChatService.chat(userMessage.trim());
            
            return ResponseEntity.ok(Map.of(
                "success", true,
                "response", response,
                "timestamp", LocalDateTime.now()
            ));

        } catch (Exception e) {
            log.error("聊天請求處理失敗", e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", "處理請求時發生錯誤：" + e.getMessage(),
                "timestamp", LocalDateTime.now()
            ));
        }
    }

}