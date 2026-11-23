package com.smitgate.connector.ads;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads customers from the company CRM (crm-dulieukhachhang on igam3) through a read-only database user
 * that can only SELECT its customer and order tables. Not configured → no CRM contacts.
 */
@Slf4j
@Component
public class CrmCustomerSource {

    /** Orders that never became a sale. */
    private static final String NOT_SOLD = "('HỦY ĐƠN', 'Hoàn hàng')";

    private final JdbcTemplate jdbc;

    public CrmCustomerSource(@Value("${app.crm.jdbc-url:}") String url,
                             @Value("${app.crm.username:}") String username,
                             @Value("${app.crm.password:}") String password) {
        if (url == null || url.isBlank()) {
            this.jdbc = null;
            return;
        }
        DriverManagerDataSource ds = new DriverManagerDataSource(url, username, password);
        ds.setDriverClassName("org.postgresql.Driver");
        this.jdbc = new JdbcTemplate(ds);
        this.jdbc.setQueryTimeout(120);
    }

    public boolean isConfigured() {
        return jdbc != null;
    }

    /** Everyone in the CRM with a phone: leads from the customer list plus every order. */
    public List<CustomerContact> allContacts() {
        return query("SELECT \"Sdt\", \"Khach_hang\", NULL FROM public.data_dulieukhach "
                + "UNION ALL SELECT sdt, khach_hang, dia_chi FROM public.don_hang");
    }

    /** Customers with an order that was not cancelled or returned; the order address gives the province. */
    public List<CustomerContact> buyers() {
        return query("SELECT sdt, khach_hang, dia_chi FROM public.don_hang "
                + "WHERE COALESCE(tinh_trang, '') NOT IN " + NOT_SOLD);
    }

    /**
     * Gender and birth year the sales team typed on CRM leads ("gioi_tinh", "nam_sinh"), keyed by normalized phone.
     * The newest row wins when one phone has several.
     */
    public Map<String, CustomerContact> profiles() {
        Map<String, CustomerContact> out = new HashMap<>();
        if (jdbc == null) return out;
        int currentYear = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).getYear();
        jdbc.query("SELECT \"Sdt\", gioi_tinh, nam_sinh FROM public.data_dulieukhach "
                        + "WHERE COALESCE(\"Sdt\", '') <> '' AND (nam_sinh IS NOT NULL OR COALESCE(gioi_tinh, '') <> '') "
                        + "ORDER BY created_at",
                rs -> {
                    int year = rs.getInt(3);
                    CustomerContact p = CustomerContact.profile(rs.getString(1), rs.getString(2),
                            rs.wasNull() ? null : year, currentYear);
                    if (p != null) out.merge(p.phone(), p, (older, newer) -> newer.mergedWith(older));
                });
        return out;
    }

    /** A customer's Messenger id on one Page, with the customer's normalized phone. */
    public record PagePsid(String phone, String pageId, String psid) {
    }

    /** Every CRM lead that left a phone and has a Page and a PSID. One customer can have one PSID per Page. */
    public List<PagePsid> pagePsids() {
        List<PagePsid> out = new ArrayList<>();
        if (jdbc == null) return out;
        jdbc.query("SELECT \"Sdt\", \"ID Trang\", \"PSID\" FROM public.data_dulieukhach "
                        + "WHERE COALESCE(\"Sdt\", '') <> '' AND COALESCE(\"PSID\", '') <> '' AND COALESCE(\"ID Trang\", '') <> ''",
                rs -> {
                    String phone = MetaPurchaseEventService.normalizePhone(rs.getString(1));
                    String page = rs.getString(2).trim();
                    String psid = rs.getString(3).trim();
                    if (phone != null && page.matches("\\d+") && psid.matches("\\d+")) {
                        out.add(new PagePsid(phone, page, psid));
                    }
                });
        return out;
    }

    /**
     * Messenger conversations the sales team tagged "Khách im lặng", "Hỏi giá xong im" or "Không nhu cầu" in the last
     * {@code days} days and that never left a phone; someone who later leaves a phone drops out on the next upload.
     * The phone field is empty for them, so {@link PagePsid#phone()} is null.
     */
    public List<PagePsid> silentPsids(int days) {
        List<PagePsid> out = new ArrayList<>();
        if (jdbc == null) return out;
        jdbc.query("SELECT \"ID Trang\", \"PSID\" FROM public.data_dulieukhach "
                        + "WHERE created_at >= now() - make_interval(days => ?) AND COALESCE(\"Sdt\", '') = '' "
                        + "AND COALESCE(\"PSID\", '') <> '' AND COALESCE(\"ID Trang\", '') <> '' "
                        + "AND (\"Tag_khach_tiem_nang\" ILIKE '%im lặng%' OR \"Tag_khach_tiem_nang\" ILIKE '%xong im%' "
                        + "OR \"Tag_khach_tiem_nang\" ILIKE '%không nhu cầu%')",
                rs -> {
                    String page = rs.getString(1).trim();
                    String psid = rs.getString(2).trim();
                    if (page.matches("\\d+") && psid.matches("\\d+")) out.add(new PagePsid(null, page, psid));
                },
                days);
        return out;
    }

    /** A CRM lead from a Messenger conversation that already has a phone number. */
    public record PhoneLead(long rowId, String pageId, String psid, String phone, String name, String adId,
                            Instant createdAt) {
    }

    /**
     * Messenger leads created in the last {@code days} days that have a phone, a Page and a PSID —
     * the customers Meta can tie back to an ad conversation. Oldest first.
     */
    public List<PhoneLead> phoneLeads(int days) {
        if (jdbc == null) return List.of();
        return jdbc.query("SELECT id, \"ID Trang\", \"PSID\", \"Sdt\", \"Khach_hang\", ad_id, created_at "
                        + "FROM public.data_dulieukhach "
                        + "WHERE created_at >= now() - make_interval(days => ?) "
                        + "AND COALESCE(\"Sdt\", '') <> '' AND COALESCE(\"PSID\", '') <> '' "
                        + "AND COALESCE(\"ID Trang\", '') <> '' ORDER BY created_at",
                (rs, i) -> new PhoneLead(rs.getLong(1), rs.getString(2).trim(), rs.getString(3).trim(),
                        rs.getString(4), rs.getString(5), rs.getString(6),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                days);
    }

    /** A Messenger lead with the tags the sales team put on it ("Khách im Lặng, Bình", "Tiềm Năng, Yến"...). */
    public record TaggedLead(String adId, String pageId, String tags, boolean hasPhone, Instant createdAt) {
    }

    /**
     * Leads of the last {@code days} days that came from one of {@code adIds}, plus leads on the same Pages whose
     * ad is unknown (ad_id empty or "EMPTY"), so the caller can report those separately.
     */
    public List<TaggedLead> taggedLeads(java.util.Collection<String> adIds, int days) {
        if (jdbc == null || adIds.isEmpty()) return List.of();
        String[] ids = adIds.toArray(new String[0]);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT ad_id, \"ID Trang\", \"Tag_khach_tiem_nang\", COALESCE(\"Sdt\", '') <> '', created_at "
                    + "FROM public.data_dulieukhach WHERE created_at >= now() - make_interval(days => ?) "
                    + "AND (ad_id = ANY(?) OR (COALESCE(ad_id, '') IN ('', 'EMPTY') AND \"ID Trang\" IN "
                    + "(SELECT DISTINCT \"ID Trang\" FROM public.data_dulieukhach WHERE ad_id = ANY(?) "
                    + "AND created_at >= now() - make_interval(days => ?))))");
            var arr = con.createArrayOf("text", ids);
            ps.setInt(1, days);
            ps.setArray(2, arr);
            ps.setArray(3, arr);
            ps.setInt(4, days);
            return ps;
        }, (rs, i) -> new TaggedLead(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4),
                rs.getTimestamp(5) == null ? null : rs.getTimestamp(5).toInstant()));
    }

    /** Page id → Page name as the CRM shows it ("Kim Ánh Đỉnh Đồng Nam Định"), from recent leads. */
    public Map<String, String> pageNames() {
        Map<String, String> out = new HashMap<>();
        if (jdbc == null) return out;
        // The newest name wins: Pages get renamed and older rows keep the old name.
        jdbc.query("SELECT \"ID Trang\", (array_agg(\"Page\" ORDER BY created_at DESC))[1] FROM public.data_dulieukhach "
                        + "WHERE created_at >= now() - interval '90 days' AND COALESCE(\"ID Trang\", '') <> '' "
                        + "AND \"Page\" IS NOT NULL GROUP BY 1",
                rs -> {
                    String name = rs.getString(2);
                    if (name == null) return;
                    name = name.replaceAll("\\s*\\(\\d+\\)\\s*$", "").trim();
                    if (!name.isEmpty()) out.put(rs.getString(1).trim(), name);
                });
        return out;
    }

    /** CRM row id → customer name, for showing who a lead event was about. */
    public Map<Long, String> customerNames(java.util.Collection<Long> rowIds) {
        Map<Long, String> out = new HashMap<>();
        if (jdbc == null || rowIds.isEmpty()) return out;
        Long[] ids = rowIds.toArray(new Long[0]);
        jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, \"Khach_hang\" FROM public.data_dulieukhach WHERE id = ANY(?)");
            ps.setArray(1, con.createArrayOf("bigint", ids));
            return ps;
        }, rs -> {
            out.put(rs.getLong(1), rs.getString(2));
        });
        return out;
    }

    private List<CustomerContact> query(String sql) {
        if (jdbc == null) return List.of();
        List<CustomerContact> out = new ArrayList<>();
        jdbc.query(sql, rs -> {
            CustomerContact c = CustomerContact.of(rs.getString(1), rs.getString(2), null, rs.getString(3));
            if (c != null) out.add(c);
        });
        return out;
    }
}
