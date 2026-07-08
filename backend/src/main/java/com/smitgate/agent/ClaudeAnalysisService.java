package com.smitgate.agent;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.Model;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class ClaudeAnalysisService {

    @Value("${app.agent.anthropic-api-key:}")
    private String apiKey;

    private AnthropicClient client;

    @PostConstruct
    public void init() {
        if (apiKey != null && !apiKey.isBlank()) {
            client = AnthropicOkHttpClient.builder()
                    .apiKey(apiKey)
                    .build();
            log.info("Claude API client initialized");
        } else {
            log.warn("ANTHROPIC_API_KEY not set — Claude analysis disabled");
        }
    }

    public String analyzeCampaigns(String campaignDataJson) {
        if (client == null) {
            return "Claude API not configured";
        }

        String systemPrompt = """
                Bạn là chuyên gia phân tích quảng cáo digital marketing cho thị trường Việt Nam.

                Nhiệm vụ: viết báo cáo ngắn gọn bằng tiếng Việt cho từng QUẢNG CÁO đang chạy (status ACTIVE),
                dựa trên dữ liệu đã được tính toán sẵn từ hệ thống CRM nội bộ (không phải số liệu thô từ Meta).

                Dữ liệu đầu vào gồm:
                - "thresholds": các ngưỡng cảnh báo do người dùng tự cấu hình (chi phí/tin nhắn, chi phí/SĐT, chi phí/đơn, mức lỗ tối đa sau QC)
                - "alerts": danh sách cảnh báo ĐÃ ĐƯỢC HỆ THỐNG TÍNH SẴN theo đúng các ngưỡng trên — đây là số liệu chính xác,
                  KHÔNG được tự tính toán lại hay suy diễn thêm cảnh báo khác ngoài danh sách này.
                - "ads": chi tiết từng quảng cáo (costPerMessage, costPerPhone, costPerOrder, profitAfterAds, roas...)

                Nhiệm vụ của bạn:
                1. Trình bày lại toàn bộ "alerts" một cách rõ ràng, dễ đọc (nếu rỗng thì ghi "Không có cảnh báo nào").
                2. Tổng hợp số liệu tổng quan (tổng chi phí/doanh thu/ROAS).
                3. Chỉ ra 1-3 quảng cáo hiệu quả nhất (roas cao, không nằm trong alerts).
                4. Đưa khuyến nghị hành động cụ thể cho từng cảnh báo (dừng/tối ưu/theo dõi thêm).

                Format báo cáo Telegram (dùng Markdown):
                *📊 BÁO CÁO PHÂN TÍCH QUẢNG CÁO*

                🔴 *CẢNH BÁO*
                - Tên quảng cáo: lý do (lấy nguyên văn từ "alerts")

                📈 *TỔNG QUAN*
                - Tổng chi phí / Tổng doanh thu / ROAS tổng

                🏆 *TOP QUẢNG CÁO TỐT NHẤT*
                - Quảng cáo nào hiệu quả nhất

                💡 *KHUYẾN NGHỊ*
                - Hành động cụ thể cần làm

                Giữ báo cáo ngắn gọn, dưới 2000 ký tự.
                """;

        String userMessage = "Viết báo cáo phân tích từ dữ liệu quảng cáo đang chạy (CRM, đã tính sẵn cảnh báo) sau:\n\n" + campaignDataJson;

        try {
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(Model.CLAUDE_SONNET_4_6)
                    .maxTokens(2048L)
                    .system(systemPrompt)
                    .addUserMessage(userMessage)
                    .build();

            Message response = client.messages().create(params);

            StringBuilder result = new StringBuilder();
            response.content().stream()
                    .flatMap(block -> block.text().stream())
                    .forEach(textBlock -> result.append(textBlock.text()));

            return result.toString();
        } catch (Exception e) {
            log.error("Claude API call failed: {}", e.getMessage());
            return "Lỗi khi gọi Claude API: " + e.getMessage();
        }
    }
}
