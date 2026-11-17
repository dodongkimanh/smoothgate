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

    /** Page id → Page name as the CRM shows it ("Kim Ánh Đỉnh Đồng Nam Định"), from recent leads. */
    public Map<String, String> pageNames() {
        Map<String, String> out = new HashMap<>();
        if (jdbc == null) return out;
        jdbc.query("SELECT \"ID Trang\", max(\"Page\") FROM public.data_dulieukhach "
                        + "WHERE created_at >= now() - interval '90 days' AND COALESCE(\"ID Trang\", '') <> '' "
                        + "GROUP BY 1",
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
