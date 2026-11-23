package com.smitgate.agent;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the lead-quality tags the sales team puts on CRM leads. A lead can carry several tags plus the sale's name
 * ("Đã Gọi, Hân, Hỏi Giá Xong Im"); the best quality tag wins.
 */
final class LeadQuality {

    private LeadQuality() {
    }

    /** Best → worst. */
    enum Kind {
        CHOT("chốt đơn"), TIEM_NANG("tiềm năng"), CO_TUONG_TAC("có tương tác"), HOI_GIA("hỏi giá xong im"),
        IM_LANG("im lặng"), KHONG_NHU_CAU("không nhu cầu"), CHUA_GAN("chưa gắn thẻ");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    static Kind classify(String tags) {
        String t = fold(tags);
        if (t.contains("chot don")) return Kind.CHOT;
        if (t.contains("tiem nang")) return Kind.TIEM_NANG;
        if (t.contains("tuong tac")) return Kind.CO_TUONG_TAC;
        if (t.contains("hoi gia")) return Kind.HOI_GIA;
        if (t.contains("im lang")) return Kind.IM_LANG;
        if (t.contains("khong nhu cau")) return Kind.KHONG_NHU_CAU;
        return Kind.CHUA_GAN;
    }

    /** The sale names in a tag string: every part that is not a quality tag, "Đã Gọi" or a Pancake number. */
    static List<String> sales(String tags) {
        List<String> out = new ArrayList<>();
        if (tags == null) return out;
        for (String part : tags.split(",")) {
            String p = part.trim();
            String f = fold(p);
            if (p.isEmpty() || p.matches("-?\\d+") || f.contains("chot") || f.contains("tiem nang") || f.contains("tuong tac")
                    || f.contains("hoi gia") || f.contains("im lang") || f.contains("nhu cau") || f.contains("da goi")) {
                continue;
            }
            out.add(p);
        }
        return out;
    }

    private static String fold(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.replace('đ', 'd');
    }

    /** Lead counts for one group (an ad set, an ad, a sale, a day...). */
    static final class Count {
        int total;
        int phones;
        final int[] kinds = new int[Kind.values().length];

        void add(Kind k, boolean hasPhone) {
            total++;
            kinds[k.ordinal()]++;
            if (hasPhone) phones++;
            if (isQuality(k, hasPhone)) qualityCount++;
        }

        int of(Kind k) {
            return kinds[k.ordinal()];
        }

        /** Leads worth a sale's time: left a phone, or tagged potential / interacting / typed a price question / bought. */
        int qualityCount;

        int tagged() {
            return total - of(Kind.CHUA_GAN);
        }

        String describe() {
            if (total == 0) return "0 tin nhắn trong CRM";
            StringBuilder sb = new StringBuilder();
            sb.append(total).append(" tin nhắn trong CRM: ");
            boolean first = true;
            for (Kind k : Kind.values()) {
                int n = of(k);
                if (n == 0) continue;
                if (!first) sb.append(", ");
                first = false;
                sb.append(k.label).append(' ').append(n);
                if (k == Kind.IM_LANG || k == Kind.HOI_GIA) sb.append(" (").append(pct(n, total)).append(')');
            }
            sb.append(" · có SĐT ").append(phones).append(" · khách chất lượng ").append(qualityCount)
                    .append(" (").append(pct(qualityCount, total)).append(')');
            int tagged = tagged();
            if (tagged > 0) {
                sb.append(" · im lặng/đã gắn thẻ ").append(pct(of(Kind.IM_LANG), tagged));
            }
            return sb.toString();
        }

        static String pct(int n, int d) {
            return d == 0 ? "—" : Math.round(100.0 * n / d) + "%";
        }
    }

    static boolean isQuality(Kind k, boolean hasPhone) {
        return hasPhone || k == Kind.CHOT || k == Kind.TIEM_NANG || k == Kind.CO_TUONG_TAC || k == Kind.HOI_GIA;
    }
}
