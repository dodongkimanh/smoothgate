package com.smitgate.campaign;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns the free-text "Vị trí (địa lý)" and "Vị trí quảng cáo" draft fields into Meta targeting pieces,
 * so a draft can be published without fixing placements/regions by hand in Ads Manager afterwards.
 *
 * <p>Both fields accept either an include list ("Hà Nội, Hải Phòng" / "Bảng feed, Reels") or an
 * exclusion written as "trừ: ..." ("VN, trừ: TP.HCM" / "trừ: In-stream, Tin").
 */
public final class DraftTargetingParser {

    private DraftTargetingParser() {}

    /** Province names as typed by the user; resolution to Meta region keys happens in the connector. */
    public record LocationSpec(List<String> includeRegions, List<String> excludeRegions) {
        public boolean wholeCountry() {
            return includeRegions.isEmpty();
        }
    }

    private static final String FB = "facebook";
    private static final String IG = "instagram";
    private static final String MS = "messenger";

    // Positions used when the user writes "trừ: ..." — the automatic set minus what they exclude.
    // Audience Network and the right column are left out: neither delivers Messenger-destination ads well.
    private static final Map<String, List<String>> ALL_POSITIONS = Map.of(
            FB, List.of("feed", "profile_feed", "marketplace", "video_feeds", "search", "story",
                    "facebook_reels", "facebook_reels_overlay", "instream_video", "notification"),
            IG, List.of("stream", "story", "explore", "reels"),
            MS, List.of("messenger_home"));

    // Normalized label → platform:position entries.
    private static final Map<String, List<String>> PLACEMENT_ALIASES = new LinkedHashMap<>();

    static {
        alias(List.of("feed", "bangfeed", "bangtin", "newsfeed", "facebookfeed"), "facebook:feed");
        alias(List.of("reels"), "facebook:facebook_reels", "instagram:reels");
        alias(List.of("facebookreels", "reelsfacebook"), "facebook:facebook_reels");
        alias(List.of("reelsoverlay", "overlay", "lopphureels", "quangcaotrenreels"), "facebook:facebook_reels_overlay");
        alias(List.of("instream", "instreamvideo", "videotrongluong"), "facebook:instream_video");
        alias(List.of("tin", "story", "stories"), "facebook:story", "instagram:story");
        alias(List.of("marketplace"), "facebook:marketplace");
        alias(List.of("video", "videofeeds", "bangfeedvideo"), "facebook:video_feeds");
        alias(List.of("timkiem", "search", "ketquatimkiem"), "facebook:search");
        alias(List.of("trangcanhan", "profile", "profilefeed", "feedtrangcanhan"), "facebook:profile_feed");
        alias(List.of("thongbao", "notification"), "facebook:notification");
        alias(List.of("messenger", "hopthumessenger"), "messenger:messenger_home");
        alias(List.of("instagram"), "instagram:stream", "instagram:story", "instagram:explore", "instagram:reels");
        alias(List.of("instagramfeed", "feedinstagram"), "instagram:stream");
        alias(List.of("khampha", "explore"), "instagram:explore");
    }

    private static void alias(List<String> labels, String... entries) {
        for (String label : labels) {
            PLACEMENT_ALIASES.put(label, List.of(entries));
        }
    }

    /**
     * Returns the Meta targeting keys for placements (publisher_platforms + *_positions),
     * or null for automatic (Advantage+) placements when the field is blank.
     */
    public static Map<String, Object> parsePlacements(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty() || "null".equals(raw) || isAutomatic(raw)) {
            return null;
        }
        String[] parts = splitExclusion(raw);
        boolean excludeMode = parts != null;

        Map<String, Set<String>> selected = new LinkedHashMap<>();
        if (excludeMode) {
            ALL_POSITIONS.forEach((platform, positions) -> selected.put(platform, new LinkedHashSet<>(positions)));
            for (String entry : resolvePlacementLabels(parts[1])) {
                String[] pp = entry.split(":");
                Set<String> set = selected.get(pp[0]);
                if (set != null) set.remove(pp[1]);
            }
        } else {
            for (String entry : resolvePlacementLabels(raw)) {
                String[] pp = entry.split(":");
                selected.computeIfAbsent(pp[0], k -> new LinkedHashSet<>()).add(pp[1]);
            }
        }

        Map<String, Object> targeting = new LinkedHashMap<>();
        List<String> platforms = new ArrayList<>();
        for (String platform : List.of(FB, IG, MS)) {
            Set<String> positions = selected.get(platform);
            if (positions != null && !positions.isEmpty()) {
                platforms.add(platform);
                targeting.put(platform + "_positions", new ArrayList<>(positions));
            }
        }
        if (platforms.isEmpty()) {
            throw new IllegalArgumentException("Vị trí quảng cáo đã loại trừ hết — không còn vị trí nào để chạy");
        }
        targeting.put("publisher_platforms", platforms);
        return targeting;
    }

    private static List<String> resolvePlacementLabels(String list) {
        List<String> entries = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        for (String label : splitList(list)) {
            List<String> mapped = PLACEMENT_ALIASES.get(normalize(label));
            if (mapped == null) {
                unknown.add(label);
            } else {
                entries.addAll(mapped);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Không hiểu vị trí quảng cáo: " + String.join(", ", unknown)
                    + ". Dùng: Bảng feed, Reels, Reels overlay, In-stream, Tin, Marketplace, Video, Tìm kiếm, "
                    + "Trang cá nhân, Thông báo, Messenger, Instagram — hoặc \"trừ: ...\" để loại trừ.");
        }
        return entries;
    }

    /** Parses "VN", "Hà Nội, Hải Phòng" or "VN, trừ: TP.HCM, Lâm Đồng". */
    public static LocationSpec parseLocations(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty() || "null".equals(raw)) {
            return new LocationSpec(List.of(), List.of());
        }
        String[] parts = splitExclusion(raw);
        String includePart = parts != null ? parts[0] : raw;
        List<String> excludes = parts != null ? splitList(parts[1]) : List.of();

        List<String> includes = new ArrayList<>();
        for (String name : splitList(includePart)) {
            if (!isWholeCountry(name)) {
                includes.add(name);
            }
        }
        if (!includes.isEmpty() && !excludes.isEmpty()) {
            throw new IllegalArgumentException("Vị trí địa lý: chỉ dùng \"trừ:\" khi nhắm toàn quốc (VD: \"VN, trừ: TP.HCM\")");
        }
        return new LocationSpec(includes, excludes);
    }

    private static boolean isWholeCountry(String name) {
        String n = normalize(name);
        return n.equals("vn") || n.equals("vietnam") || n.equals("toanquoc") || n.equals("country");
    }

    private static boolean isAutomatic(String raw) {
        String n = normalize(raw);
        return n.equals("tudong") || n.equals("advantage") || n.equals("advantageplus") || n.equals("automatic");
    }

    /** Splits "include, trừ: a, b" into [include, "a, b"]; null when there is no exclusion marker. */
    private static String[] splitExclusion(String raw) {
        String lower = raw.toLowerCase();
        for (String marker : List.of("loại trừ:", "loai tru:", "trừ:", "tru:", "bỏ:", "bo:")) {
            int idx = lower.indexOf(marker);
            if (idx >= 0) {
                return new String[]{raw.substring(0, idx), raw.substring(idx + marker.length())};
            }
        }
        return null;
    }

    private static List<String> splitList(String list) {
        List<String> items = new ArrayList<>();
        for (String part : list.split("[,;\\n]")) {
            String item = part.trim();
            if (!item.isEmpty()) items.add(item);
        }
        return items;
    }

    private static final Map<String, String> REGION_ALIASES = Map.of(
            "hcm", "hochiminh",
            "tphcm", "hochiminh",
            "saigon", "hochiminh",
            "hn", "hanoi",
            "hp", "haiphong",
            "brvt", "bariavungtau",
            "hue", "thuathienhue");

    /** Normalized province name with common short forms (TP.HCM, Sài Gòn, BRVT...) expanded. */
    public static String canonicalRegion(String value) {
        String n = normalize(value);
        return REGION_ALIASES.getOrDefault(n, n);
    }

    /**
     * Lowercase, no diacritics, letters/digits only, without generic words like "tỉnh"/"province"/"thành phố",
     * so "TP. Hồ Chí Minh", "Ho Chi Minh City" and "Thành phố Hồ Chí Minh" all compare equal.
     */
    public static String normalize(String value) {
        String s = value == null ? "" : value.toLowerCase().replace('đ', 'd');
        s = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        s = s.replaceAll("\\b(tinh|thanh pho|tp|province|city)\\b", " ");
        return s.replaceAll("[^a-z0-9]", "");
    }
}
