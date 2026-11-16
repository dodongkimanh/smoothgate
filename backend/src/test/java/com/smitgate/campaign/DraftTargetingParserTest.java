package com.smitgate.campaign;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DraftTargetingParserTest {

    @Test
    void blankOrAutomaticPlacementsMeanAdvantagePlus() {
        assertNull(DraftTargetingParser.parsePlacements(""));
        assertNull(DraftTargetingParser.parsePlacements("Tự động"));
    }

    @Test
    void includeListMapsVietnameseLabels() {
        Map<String, Object> t = DraftTargetingParser.parsePlacements("Bảng feed, Reels");
        assertEquals(List.of("facebook", "instagram"), t.get("publisher_platforms"));
        assertEquals(List.of("feed", "facebook_reels"), t.get("facebook_positions"));
        assertEquals(List.of("reels"), t.get("instagram_positions"));
    }

    @Test
    void exclusionRemovesPositionsFromAutomaticSet() {
        Map<String, Object> t = DraftTargetingParser.parsePlacements("trừ: In-stream, Reels overlay, Tin");
        @SuppressWarnings("unchecked")
        List<String> fb = (List<String>) t.get("facebook_positions");
        assertFalse(fb.contains("instream_video"));
        assertFalse(fb.contains("facebook_reels_overlay"));
        assertFalse(fb.contains("story"));
        assertTrue(fb.contains("feed"));
        assertTrue(fb.contains("facebook_reels"));
        assertEquals(List.of("stream", "explore", "reels"), t.get("instagram_positions"));
    }

    @Test
    void unknownPlacementIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> DraftTargetingParser.parsePlacements("Bảng feed, abc"));
    }

    @Test
    void locationsWholeCountryWithExclusions() {
        DraftTargetingParser.LocationSpec spec = DraftTargetingParser.parseLocations("VN, trừ: TP.HCM, Lâm Đồng");
        assertTrue(spec.wholeCountry());
        assertEquals(List.of("TP.HCM", "Lâm Đồng"), spec.excludeRegions());
    }

    @Test
    void locationsIncludeList() {
        DraftTargetingParser.LocationSpec spec = DraftTargetingParser.parseLocations("Quảng Ninh Province,Hanoi");
        assertEquals(List.of("Quảng Ninh Province", "Hanoi"), spec.includeRegions());
        assertTrue(spec.excludeRegions().isEmpty());
    }

    @Test
    void regionNamesFromInsightsAndSearchMatch() {
        assertEquals(DraftTargetingParser.canonicalRegion("Thành phố Hồ Chí Minh"), DraftTargetingParser.canonicalRegion("TP.HCM"));
        assertEquals(DraftTargetingParser.canonicalRegion("Thành phố Hồ Chí Minh"), DraftTargetingParser.canonicalRegion("Ho Chi Minh City"));
        assertEquals(DraftTargetingParser.canonicalRegion("Hà Nội"), DraftTargetingParser.canonicalRegion("Hanoi"));
        assertEquals(DraftTargetingParser.canonicalRegion("Bà Rịa - Vũng Tàu"), DraftTargetingParser.canonicalRegion("Bà Rịa–Vũng Tàu Province"));
        assertEquals(DraftTargetingParser.canonicalRegion("Hải Dương"), DraftTargetingParser.canonicalRegion("Hải Dương Province"));
    }
}
