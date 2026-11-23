package com.smitgate.connector.ads;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One customer as Meta's customer-list matching wants it: normalized (not yet hashed) phone, first and last name,
 * gender, province and birth year. Fields other than the phone may be null.
 *
 * <p>Normalization follows Meta's rules: lowercase, no punctuation; names keep their UTF-8 letters,
 * the province is folded to a-z with no spaces ("Tỉnh Hải Dương" → "haiduong").
 */
public record CustomerContact(String phone, String firstName, String lastName, String gender, String state,
                              String birthYear) {

    private static final Pattern GENDER_IN_JSON = Pattern.compile("\"gender\":\\s*\"(male|female)\"");

    /** Builds a contact from raw CRM/Pancake values; null when the phone is not a usable number. */
    public static CustomerContact of(String rawPhone, String rawName, String rawGender, String address) {
        String phone = MetaPurchaseEventService.normalizePhone(rawPhone);
        if (phone == null) return null;
        String[] names = splitName(rawName);
        return new CustomerContact(phone, names[0], names[1], normalizeGender(rawGender), provinceOf(address), null);
    }

    /** Gender and birth year a salesperson typed into the CRM for this phone; null when neither is usable. */
    public static CustomerContact profile(String rawPhone, String rawGender, Integer birthYear, int currentYear) {
        String phone = MetaPurchaseEventService.normalizePhone(rawPhone);
        if (phone == null) return null;
        String gender = normalizeGender(rawGender);
        String year = normalizeBirthYear(birthYear, currentYear);
        if (gender == null && year == null) return null;
        return new CustomerContact(phone, null, null, gender, null, year);
    }

    /** Meta's DOBY is four digits; anything outside 1900 .. (this year - 10) is a typo, not a customer. */
    static String normalizeBirthYear(Integer year, int currentYear) {
        if (year == null || year < 1900 || year > currentYear - 10) return null;
        return String.valueOf(year);
    }

    /** Fills this contact's missing fields from another record of the same phone. */
    public CustomerContact mergedWith(CustomerContact other) {
        if (other == null) return this;
        return new CustomerContact(phone,
                firstName != null ? firstName : other.firstName,
                lastName != null ? lastName : other.lastName,
                gender != null ? gender : other.gender,
                state != null ? state : other.state,
                birthYear != null ? birthYear : other.birthYear);
    }

    /** Pancake keeps the customer's gender in the order payload ("gender":"male"). */
    public static String genderFromJson(String rawJson) {
        if (rawJson == null) return null;
        Matcher m = GENDER_IN_JSON.matcher(rawJson);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Vietnamese names put the family name first: "Nguyễn Văn An" → first "an", last "nguyễn".
     * Returns {firstName, lastName}; a single word is the first name.
     */
    static String[] splitName(String raw) {
        if (raw == null) return new String[]{null, null};
        String cleaned = raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\s]", " ")
                .trim()
                .replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return new String[]{null, null};
        String[] words = cleaned.split(" ");
        if (words.length == 1) return new String[]{words[0], null};
        return new String[]{words[words.length - 1], words[0]};
    }

    static String normalizeGender(String raw) {
        if (raw == null) return null;
        String g = fold(raw);
        if (g.equals("male") || g.equals("m") || g.equals("nam")) return "m";
        if (g.equals("female") || g.equals("f") || g.equals("nu")) return "f";
        return null;
    }

    /** The province named last in a free-text Vietnamese address, as Meta's folded "st" value. */
    public static String provinceOf(String address) {
        if (address == null || address.isBlank()) return null;
        String text = fold(address);
        String best = null;
        int bestEnd = -1;
        int bestLength = 0;
        for (Map.Entry<String, List<String>> province : PROVINCES.entrySet()) {
            for (String alias : province.getValue()) {
                int at = text.lastIndexOf(alias);
                if (at < 0) continue;
                int end = at + alias.length();
                if (end > bestEnd || (end == bestEnd && alias.length() > bestLength)) {
                    best = province.getKey();
                    bestEnd = end;
                    bestLength = alias.length();
                }
            }
        }
        return best;
    }

    /** Lowercase a-z and digits only: diacritics removed, đ → d, spaces and punctuation dropped. */
    static String fold(String value) {
        String noMarks = Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd');
        return noMarks.replaceAll("[^a-z0-9]", "");
    }

    /** The 63 provinces (the 2025 merged provinces kept these names), with common spellings. */
    private static final Map<String, List<String>> PROVINCES = Map.ofEntries(
            Map.entry("angiang", List.of("angiang")),
            Map.entry("bariavungtau", List.of("bariavungtau", "brvt", "vungtau")),
            Map.entry("bacgiang", List.of("bacgiang")),
            Map.entry("backan", List.of("backan", "baccan")),
            Map.entry("baclieu", List.of("baclieu")),
            Map.entry("bacninh", List.of("bacninh")),
            Map.entry("bentre", List.of("bentre")),
            Map.entry("binhdinh", List.of("binhdinh")),
            Map.entry("binhduong", List.of("binhduong")),
            Map.entry("binhphuoc", List.of("binhphuoc")),
            Map.entry("binhthuan", List.of("binhthuan")),
            Map.entry("camau", List.of("camau")),
            Map.entry("cantho", List.of("cantho")),
            Map.entry("caobang", List.of("caobang")),
            Map.entry("danang", List.of("danang")),
            Map.entry("daklak", List.of("daklak", "daclac", "daclak", "daklac")),
            Map.entry("daknong", List.of("daknong", "dacnong")),
            Map.entry("dienbien", List.of("dienbien")),
            Map.entry("dongnai", List.of("dongnai")),
            Map.entry("dongthap", List.of("dongthap")),
            Map.entry("gialai", List.of("gialai")),
            Map.entry("hagiang", List.of("hagiang")),
            Map.entry("hanam", List.of("hanam")),
            Map.entry("hanoi", List.of("hanoi")),
            Map.entry("hatinh", List.of("hatinh")),
            Map.entry("haiduong", List.of("haiduong")),
            Map.entry("haiphong", List.of("haiphong")),
            Map.entry("haugiang", List.of("haugiang")),
            Map.entry("hoabinh", List.of("hoabinh")),
            Map.entry("hungyen", List.of("hungyen")),
            Map.entry("khanhhoa", List.of("khanhhoa")),
            Map.entry("kiengiang", List.of("kiengiang")),
            Map.entry("kontum", List.of("kontum")),
            Map.entry("laichau", List.of("laichau")),
            Map.entry("lamdong", List.of("lamdong")),
            Map.entry("langson", List.of("langson")),
            Map.entry("laocai", List.of("laocai")),
            Map.entry("longan", List.of("longan")),
            Map.entry("namdinh", List.of("namdinh")),
            Map.entry("nghean", List.of("nghean")),
            Map.entry("ninhbinh", List.of("ninhbinh")),
            Map.entry("ninhthuan", List.of("ninhthuan")),
            Map.entry("phutho", List.of("phutho")),
            Map.entry("phuyen", List.of("phuyen")),
            Map.entry("quangbinh", List.of("quangbinh")),
            Map.entry("quangnam", List.of("quangnam")),
            Map.entry("quangngai", List.of("quangngai")),
            Map.entry("quangninh", List.of("quangninh")),
            Map.entry("quangtri", List.of("quangtri")),
            Map.entry("soctrang", List.of("soctrang")),
            Map.entry("sonla", List.of("sonla")),
            Map.entry("tayninh", List.of("tayninh")),
            Map.entry("thaibinh", List.of("thaibinh")),
            Map.entry("thainguyen", List.of("thainguyen")),
            Map.entry("thanhhoa", List.of("thanhhoa")),
            Map.entry("thuathienhue", List.of("thuathienhue", "tphue", "thanhphohue")),
            Map.entry("tiengiang", List.of("tiengiang")),
            Map.entry("travinh", List.of("travinh")),
            Map.entry("tuyenquang", List.of("tuyenquang")),
            Map.entry("vinhlong", List.of("vinhlong")),
            Map.entry("vinhphuc", List.of("vinhphuc")),
            Map.entry("yenbai", List.of("yenbai")),
            Map.entry("hochiminh", List.of("hochiminh", "tphcm", "hcm", "saigon")));
}
