package com.smitgate.agent;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Service
public class TelegramService {

    private final WebClient webClient;
    private final String botToken;
    private final String chatId;

    public TelegramService(
            WebClient.Builder webClientBuilder,
            @Value("${app.agent.telegram-bot-token:}") String botToken,
            @Value("${app.agent.telegram-chat-id:}") String chatId) {
        this.webClient = webClientBuilder.baseUrl("https://api.telegram.org").build();
        this.botToken = botToken;
        this.chatId = chatId;
    }

    public void sendMessage(String text) {
        if (botToken.isBlank() || chatId.isBlank()) {
            log.warn("Telegram not configured — skipping message");
            return;
        }

        String truncated = text.length() > 4000 ? text.substring(0, 4000) + "\n\n... (truncated)" : text;

        try {
            webClient.post()
                    .uri("/bot{token}/sendMessage", botToken)
                    .bodyValue(java.util.Map.of(
                            "chat_id", chatId,
                            "text", truncated,
                            "parse_mode", "Markdown"
                    ))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            log.info("Telegram message sent to chat {}", chatId);
        } catch (Exception e) {
            log.error("Failed to send Telegram message: {}", e.getMessage());
        }
    }
}
