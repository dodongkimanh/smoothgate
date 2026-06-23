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

                Nhiệm vụ: phân tích dữ liệu chiến dịch quảng cáo và đưa ra báo cáo ngắn gọn bằng tiếng Việt.

                Quy tắc phân tích:
                - ROAS < 1.0: Lỗ nặng, cần dừng ngay
                - ROAS 1.0-2.0: Hòa vốn hoặc lãi ít, cần tối ưu
                - ROAS > 3.0: Hiệu quả tốt
                - CPO (chi phí/đơn) quá cao so với giá trị trung bình đơn hàng: cảnh báo
                - Chiến dịch chi nhiều nhưng không có đơn: cảnh báo nghiêm trọng
                - So sánh hiệu suất giữa các chiến dịch trong cùng tài khoản

                Format báo cáo Telegram (dùng Markdown):
                *📊 BÁO CÁO PHÂN TÍCH QUẢNG CÁO*

                🔴 *CẢNH BÁO* (nếu có chiến dịch cần chú ý)
                - Tên chiến dịch: vấn đề gì

                📈 *TỔNG QUAN*
                - Tổng chi phí / Tổng doanh thu / ROAS tổng

                🏆 *TOP CHIẾN DỊCH TỐT NHẤT*
                - Chiến dịch nào hiệu quả nhất

                💡 *KHUYẾN NGHỊ*
                - Hành động cụ thể cần làm

                Giữ báo cáo ngắn gọn, dưới 2000 ký tự. Chỉ báo cáo khi có dữ liệu đáng chú ý.
                """;

        String userMessage = "Phân tích dữ liệu chiến dịch quảng cáo sau và đưa ra báo cáo:\n\n" + campaignDataJson;

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
