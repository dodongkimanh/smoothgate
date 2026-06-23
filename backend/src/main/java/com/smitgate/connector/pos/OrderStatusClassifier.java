package com.smitgate.connector.pos;

import java.text.Normalizer;
import java.util.List;
import java.util.Set;

public final class OrderStatusClassifier {

    /**
     * Statuses that count as a confirmed/valid order for revenue aggregation.
     * Must include the canonical English slugs stored in DB by normalizeOrderStatus(),
     * plus any legacy values from older builds.
     */
    private static final Set<String> VALID_ORDER_STATUSES = Set.of(
            "confirmed", "submitted",
            "packaging", "waiting_shipping", "shipping", "shipped",
            "delivered", "received_money",
            // Legacy Vietnamese with diacritics (before normalization fix)
            "đã xác nhận",
            // Legacy Vietnamese without diacritics
            "da xac nhan"
    );

    private static final Set<String> LEAD_ORDER_STATUSES = Set.of(
            // new / pending
            "new", "pending",
            // confirmed
            "confirmed", "submitted",
            // packaging through delivery
            "packaging", "waiting_shipping", "shipping", "shipped",
            "delivered", "received_money",
            // Legacy Vietnamese with diacritics
            "mới", "đã xác nhận",
            // Legacy Vietnamese without diacritics
            "moi", "cho xu ly", "da xac nhan"
    );

    private OrderStatusClassifier() {
    }

    public static boolean isValidOrderStatus(String rawStatus) {
        return VALID_ORDER_STATUSES.contains(normalize(rawStatus));
    }

    public static boolean isLeadStatus(String rawStatus) {
        return LEAD_ORDER_STATUSES.contains(normalize(rawStatus));
    }

    public static List<String> validStatusesForAggregation() {
        return List.copyOf(VALID_ORDER_STATUSES);
    }

    public static List<String> leadStatusesForAggregation() {
        return List.copyOf(LEAD_ORDER_STATUSES);
    }

    public static String normalize(String rawStatus) {
        if (rawStatus == null) {
            return "";
        }

        String normalized = rawStatus.trim().toLowerCase()
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ");

        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");

        return normalized.trim();
    }
}
