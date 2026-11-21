package com.smitgate.connector.ads;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smitgate.connector.pos.OrderRepository;
import com.smitgate.connector.pos.OrderStatusClassifier;
import com.smitgate.datasource.DataSource;
import com.smitgate.datasource.DataSourceRepository;
import com.smitgate.datasource.DataSourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Keeps two Meta customer-list audiences per ad account filled with the customers from the CRM and Pancake
 * (everyone who left a phone, and buyers only), and creates a lookalike of each once Meta accepts it.
 * The lists are replaced in full on every run, so new customers flow into the lookalikes automatically.
 *
 * <p>Each customer is sent as phone, first/last name, gender, province, birth year and country to raise the match
 * rate; every value is normalized and SHA-256 hashed before it leaves the server, so Meta never sees it in clear.
 *
 * <p>The upload runs once a day at 23:59 (Vietnam time) so the day's new leads and orders are all included, and
 * every run is logged in meta_audience_sync_log with how many people joined or left each list.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetaCustomerAudienceService {

    private static final String GRAPH_BASE = "https://graph.facebook.com";
    private static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    /** Meta accepts at most 10,000 entries per upload request. */
    static final int BATCH_SIZE = 10_000;
    static final List<String> SCHEMA = List.of("PHONE", "FN", "LN", "GEN", "ST", "DOBY", "COUNTRY");

    private static final Map<MetaCustomerAudience.Kind, String> NAMES = Map.ofEntries(
            Map.entry(MetaCustomerAudience.Kind.BUYERS, "Smoothgate | Khách đã mua (SĐT)"),
            Map.entry(MetaCustomerAudience.Kind.ALL_PHONES, "Smoothgate | Tất cả khách để lại SĐT"),
            Map.entry(MetaCustomerAudience.Kind.LAL_BUYERS, "Smoothgate | LAL 1% khách đã mua"),
            Map.entry(MetaCustomerAudience.Kind.LAL_ALL_PHONES, "Smoothgate | LAL 2% tất cả SĐT"),
            Map.entry(MetaCustomerAudience.Kind.PSID_BUYERS, "Smoothgate | Khách đã mua (PSID)"),
            Map.entry(MetaCustomerAudience.Kind.PSID_ALL_PHONES, "Smoothgate | Tất cả khách để lại SĐT (PSID)"),
            Map.entry(MetaCustomerAudience.Kind.LAL_PSID_BUYERS, "Smoothgate | LAL 1% khách đã mua (PSID)"),
            Map.entry(MetaCustomerAudience.Kind.PSID_SILENT, "Smoothgate | Khách im lặng + không nhu cầu (PSID)"),
            Map.entry(MetaCustomerAudience.Kind.LAL_PSID_ALL_PHONES, "Smoothgate | LAL 1% tất cả khách để lại SĐT (PSID)"),
            Map.entry(MetaCustomerAudience.Kind.LAL2_PSID_ALL_PHONES, "Smoothgate | LAL 2% tất cả khách để lại SĐT (PSID)"),
            Map.entry(MetaCustomerAudience.Kind.LAL2_PSID_BUYERS, "Smoothgate | LAL 2% khách đã mua (PSID)"));

    /** How far back a "silent" or "no need" tag still counts; older ones may have changed their mind. */
    static final int SILENT_DAYS = 60;

    /** Meta's customer-file key for Messenger page-scoped ids; each upload names the Page that issued them. */
    static final String PSID_SCHEMA = "PAGEUID";

    private final OrderRepository orderRepository;
    private final CrmCustomerSource crmCustomerSource;
    private final AdAccountRepository adAccountRepository;
    private final MetaCustomerAudienceRepository audienceRepository;
    private final DataSourceRepository dataSourceRepository;
    private final DataSourceService dataSourceService;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;

    @Value("${app.facebook.api-version}")
    private String apiVersion;

    @Value("${app.meta-audiences.enabled:true}")
    private boolean enabled;

    /** Comma-separated "act_..." ids to limit the sync to; blank = every Meta ad account. */
    @Value("${app.meta-audiences.account-ids:}")
    private String accountIdsFilter;

    /** Comma-separated "act_..." ids never to sync (e.g. a personal account Meta won't allow lists on). */
    @Value("${app.meta-audiences.exclude-account-ids:}")
    private String excludedAccountIds;

    /**
     * Ad accounts that also get the PSID lists (comma-separated act_ ids, or "all").
     */
    @Value("${app.meta-audiences.psid-account-ids:all}")
    private String psidAccountIds;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /** True while the 23:59 run is going; it then leaves alone lists already sent by hand earlier that day. */
    private volatile boolean nightlyRun = false;

    /** A list sent within this many hours (e.g. by "Gửi tệp ngay" that morning) is not sent again at 23:59. */
    static final long RECENT_SEND_HOURS = 20;

    public boolean isRunning() {
        return running.get();
    }

    /** Starts an upload now in the background (the "Gửi tệp ngay" button); false if one is already running. */
    public boolean runNow() {
        // Claim the run here so a second click while it starts does not queue another full upload.
        if (!running.compareAndSet(false, true)) return false;
        CompletableFuture.runAsync(() -> {
            try {
                runSync(false);
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    @Scheduled(cron = "${app.meta-audiences.cron:0 59 23 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void syncNightly() {
        syncAll(true);
    }

    public void syncAll(boolean nightly) {
        if (!running.compareAndSet(false, true)) {
            log.info("Meta customer audiences already being sent; this run skipped");
            return;
        }
        try {
            runSync(nightly);
        } finally {
            running.set(false);
        }
    }

    private synchronized void runSync(boolean nightly) {
        if (!enabled) {
            return;
        }
        nightlyRun = nightly;
        try {
            syncAllSources();
        } finally {
            nightlyRun = false;
        }
    }

    private void syncAllSources() {
        List<DataSource> sources;
        try {
            sources = dataSourceRepository.findByTypeAndStatus(DataSource.Type.META_ADS, DataSource.Status.ACTIVE);
        } catch (Exception e) {
            log.warn("Meta customer audiences skipped: {}", e.getMessage());
            return;
        }
        for (DataSource ds : sources) {
            try {
                syncDataSource(ds);
            } catch (Exception e) {
                log.error("Meta customer audiences failed for data source {}: {}", ds.getId(), e.getMessage());
            }
        }
    }

    /** Reads back from Meta how many people each audience matched and whether it is ready for ads. */
    @Scheduled(
            fixedDelayString = "${app.meta-audiences.status-delay-ms:3600000}",
            initialDelayString = "${app.meta-audiences.status-initial-delay-ms:120000}")
    public synchronized void refreshStatuses() {
        if (!enabled) {
            return;
        }
        try {
            for (DataSource ds : dataSourceRepository.findByTypeAndStatus(DataSource.Type.META_ADS, DataSource.Status.ACTIVE)) {
                String token = dataSourceService.decryptSecret(ds);
                ensurePsidLookalikes(ds, token);
                for (MetaCustomerAudience row : audienceRepository.findByTenantId(ds.getTenantId())) {
                    if (row.getExternalAudienceId() == null) continue;
                    refreshStatus(token, row);
                }
            }
        } catch (Exception e) {
            log.warn("Meta audience status check failed: {}", e.getMessage());
        }
    }

    /**
     * Creates PSID lookalikes whose source list is already on Meta, so a new lookalike kind (or one that
     * failed because its source was still matching) appears within the hour instead of at the next 23:59 run.
     */
    private void ensurePsidLookalikes(DataSource ds, String token) {
        Long tenantId = ds.getTenantId();
        Map<String, MetaCustomerAudience> rows = new LinkedHashMap<>();
        for (MetaCustomerAudience row : audienceRepository.findByTenantId(tenantId)) {
            rows.put(row.getAdAccountId() + ":" + row.getKind(), row);
        }
        for (AdAccount account : adAccountRepository.findByTenantIdAndDataSourceId(tenantId, ds.getId())) {
            if (account.getPlatform() != AdAccount.Platform.META || !isIncluded(account.getExternalAccountId())
                    || !isPsidIncluded(account.getExternalAccountId())) {
                continue;
            }
            ensurePsidLookalikes(token, tenantId, account, rows);
        }
    }

    /** The PSID lookalikes beyond LAL 1% of buyers, each made only once its source list is on Meta. */
    private void ensurePsidLookalikes(String token, Long tenantId, AdAccount account, Map<String, MetaCustomerAudience> rows) {
        MetaCustomerAudience all = rows.get(account.getId() + ":" + MetaCustomerAudience.Kind.PSID_ALL_PHONES);
        MetaCustomerAudience buyers = rows.get(account.getId() + ":" + MetaCustomerAudience.Kind.PSID_BUYERS);
        if (all != null) {
            ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL_PSID_ALL_PHONES), all, 0.01);
            ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL2_PSID_ALL_PHONES), all, 0.02);
        }
        if (buyers != null) {
            ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL2_PSID_BUYERS), buyers, 0.02);
        }
    }

    private void refreshStatus(String token, MetaCustomerAudience row) {
        try {
            JsonNode a = get(row.getExternalAudienceId(),
                    "approximate_count_lower_bound,approximate_count_upper_bound,operation_status,delivery_status", token);
            row.setMetaCountLower(a.path("approximate_count_lower_bound").asLong(-1));
            row.setMetaCountUpper(a.path("approximate_count_upper_bound").asLong(-1));
            row.setMetaStatus(truncate("operation " + a.path("operation_status").path("code").asText("?")
                    + " " + a.path("operation_status").path("description").asText("")
                    + " | delivery " + a.path("delivery_status").path("code").asText("?")
                    + " " + a.path("delivery_status").path("description").asText("")));
        } catch (Exception e) {
            row.setMetaStatus(truncate("Lỗi đọc trạng thái: " + e.getMessage()));
        }
        row.setMetaCheckedAt(LocalDateTime.now(VIETNAM_ZONE));
        audienceRepository.save(row);
    }

    void syncDataSource(DataSource ds) {
        Long tenantId = ds.getTenantId();
        List<AdAccount> accounts = adAccountRepository.findByTenantIdAndDataSourceId(tenantId, ds.getId()).stream()
                .filter(a -> a.getPlatform() == AdAccount.Platform.META)
                .filter(a -> isIncluded(a.getExternalAccountId()))
                .toList();
        if (accounts.isEmpty()) {
            return;
        }

        List<CustomerContact> crmBuyers = crmCustomerSource.buyers();
        List<CustomerContact> crmAll = crmCustomerSource.allContacts();
        List<CustomerContact> pancakeBuyers = pancakeContacts(tenantId, OrderStatusClassifier.validStatusesForAggregation(), true);
        List<CustomerContact> pancakeAll = pancakeContacts(tenantId, OrderStatusClassifier.leadStatusesForAggregation(), false);
        Map<String, CustomerContact> profiles = crmCustomerSource.profiles();
        // Buyers' records first so their order address and name win over a bare lead record.
        Collection<CustomerContact> buyerContacts = enrich(merge(crmBuyers, pancakeBuyers), profiles);
        Collection<CustomerContact> allContacts = enrich(merge(crmBuyers, pancakeBuyers, crmAll, pancakeAll), profiles);
        List<List<String>> buyers = hashRows(buyerContacts);
        List<List<String>> allPhones = hashRows(allContacts);
        boolean anyPsidAccount = accounts.stream().anyMatch(a -> isPsidIncluded(a.getExternalAccountId()));
        List<CrmCustomerSource.PagePsid> psids = anyPsidAccount ? crmCustomerSource.pagePsids() : List.of();
        Set<String> buyerPhones = new HashSet<>();
        for (CustomerContact c : buyerContacts) buyerPhones.add(c.phone());
        Map<String, List<String>> psidAll = psidsByPage(psids, null);
        Map<String, List<String>> psidBuyers = psidsByPage(psids, buyerPhones);
        Map<String, List<String>> psidSilent = anyPsidAccount
                ? psidsByPage(crmCustomerSource.silentPsids(SILENT_DAYS), null) : Map.of();
        String token = dataSourceService.decryptSecret(ds);

        Map<String, MetaCustomerAudience> rows = new LinkedHashMap<>();
        for (MetaCustomerAudience row : audienceRepository.findByTenantId(tenantId)) {
            rows.put(row.getAdAccountId() + ":" + row.getKind(), row);
        }

        int accountsOk = 0;
        for (AdAccount account : accounts) {
            MetaCustomerAudience buyerList = row(rows, tenantId, account, MetaCustomerAudience.Kind.BUYERS);
            MetaCustomerAudience allList = row(rows, tenantId, account, MetaCustomerAudience.Kind.ALL_PHONES);
            boolean buyersSent = syncList(token, account, buyerList, buyers);
            boolean allSent = syncList(token, account, allList, allPhones);
            if (buyersSent && allSent) accountsOk++;
            if (isPsidIncluded(account.getExternalAccountId()) && !psidAll.isEmpty()) {
                MetaCustomerAudience psidBuyerList = row(rows, tenantId, account, MetaCustomerAudience.Kind.PSID_BUYERS);
                syncPsidList(token, account, psidBuyerList, psidBuyers);
                syncPsidList(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.PSID_ALL_PHONES), psidAll);
                ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL_PSID_BUYERS),
                        psidBuyerList, 0.01);
                ensurePsidLookalikes(token, tenantId, account, rows);
            }
            if (isPsidIncluded(account.getExternalAccountId()) && !psidSilent.isEmpty()) {
                // For excluding from campaigns: people the sales team tagged silent or not interested.
                syncPsidList(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.PSID_SILENT), psidSilent);
            }
            ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL_BUYERS), buyerList, 0.01);
            ensureLookalike(token, account, row(rows, tenantId, account, MetaCustomerAudience.Kind.LAL_ALL_PHONES), allList, 0.02);
        }
        log.info("Meta customer audiences: tenant {} synced {} accounts ({} buyers, {} people; CRM {} buyers / {} rows)",
                tenantId, accounts.size(), buyers.size(), allPhones.size(), crmBuyers.size(), crmAll.size());
        try {
            logRun(tenantId, buyerContacts, allContacts, accountsOk, accounts.size() - accountsOk);
        } catch (Exception e) {
            log.warn("Meta customer audiences: could not log the run: {}", e.getMessage());
        }
    }

    /**
     * Records this upload and how many phones joined or left each list since the previous one. The first run has
     * nothing to compare with, so its joined/left counts stay empty.
     */
    private void logRun(Long tenantId, Collection<CustomerContact> buyerContacts, Collection<CustomerContact> allContacts,
                        int accountsOk, int accountsFailed) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            int[] buyerDiff = replaceSentPhones(tenantId, MetaCustomerAudience.Kind.BUYERS, buyerContacts);
            int[] allDiff = replaceSentPhones(tenantId, MetaCustomerAudience.Kind.ALL_PHONES, allContacts);
            jdbcTemplate.update("INSERT INTO meta_audience_sync_log (tenant_id, synced_at, buyers, all_phones, "
                            + "added_buyers, removed_buyers, added_phones, removed_phones, with_birth_year, "
                            + "buyers_with_birth_year, with_gender, with_province, accounts_ok, accounts_failed) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    tenantId, LocalDateTime.now(VIETNAM_ZONE), buyerContacts.size(), allContacts.size(),
                    buyerDiff == null ? null : buyerDiff[0], buyerDiff == null ? null : buyerDiff[1],
                    allDiff == null ? null : allDiff[0], allDiff == null ? null : allDiff[1],
                    count(allContacts, c -> c.birthYear() != null), count(buyerContacts, c -> c.birthYear() != null),
                    count(allContacts, c -> c.gender() != null), count(allContacts, c -> c.state() != null),
                    accountsOk, accountsFailed);
        });
    }

    /** Swaps the stored phone hashes of one list for today's; returns {joined, left}, or null on the first run. */
    private int[] replaceSentPhones(Long tenantId, MetaCustomerAudience.Kind kind, Collection<CustomerContact> contacts) {
        Set<String> previous = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT phone_hash FROM meta_audience_sent_phones WHERE tenant_id = ? AND kind = ?",
                String.class, tenantId, kind.name()));
        Set<String> current = new LinkedHashSet<>();
        for (CustomerContact c : contacts) current.add(MetaPurchaseEventService.sha256(c.phone()));
        int[] diff = previous.isEmpty() ? null : diff(previous, current);

        jdbcTemplate.update("DELETE FROM meta_audience_sent_phones WHERE tenant_id = ? AND kind = ?", tenantId, kind.name());
        List<Object[]> args = new ArrayList<>(current.size());
        for (String h : current) args.add(new Object[]{tenantId, kind.name(), h});
        jdbcTemplate.batchUpdate("INSERT INTO meta_audience_sent_phones (tenant_id, kind, phone_hash) VALUES (?, ?, ?)", args);
        return diff;
    }

    /** {how many in current are not in previous, how many in previous are not in current}. */
    static int[] diff(Set<String> previous, Set<String> current) {
        int added = 0;
        for (String h : current) if (!previous.contains(h)) added++;
        int removed = 0;
        for (String h : previous) if (!current.contains(h)) removed++;
        return new int[]{added, removed};
    }

    /** Fills gender and birth year from what sales typed in the CRM; fields already known are kept. */
    static Collection<CustomerContact> enrich(Collection<CustomerContact> contacts, Map<String, CustomerContact> profiles) {
        if (profiles.isEmpty()) return contacts;
        List<CustomerContact> out = new ArrayList<>(contacts.size());
        for (CustomerContact c : contacts) out.add(c.mergedWith(profiles.get(c.phone())));
        return out;
    }

    private static int count(Collection<CustomerContact> contacts, java.util.function.Predicate<CustomerContact> test) {
        int n = 0;
        for (CustomerContact c : contacts) if (test.test(c)) n++;
        return n;
    }

    /** Page id → that Page's distinct PSIDs, for every customer or only those whose phone is in {@code onlyPhones}. */
    static Map<String, List<String>> psidsByPage(List<CrmCustomerSource.PagePsid> psids, Set<String> onlyPhones) {
        Map<String, Set<String>> byPage = new LinkedHashMap<>();
        for (CrmCustomerSource.PagePsid p : psids) {
            if (onlyPhones != null && !onlyPhones.contains(p.phone())) continue;
            byPage.computeIfAbsent(p.pageId(), k -> new LinkedHashSet<>()).add(p.psid());
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        byPage.forEach((page, ids) -> out.put(page, new ArrayList<>(ids)));
        return out;
    }

    /** Whether this ad account also gets the PSID lists. */
    boolean isPsidIncluded(String externalAccountId) {
        if (psidAccountIds == null || psidAccountIds.isBlank()) return false;
        if (psidAccountIds.trim().equalsIgnoreCase("all")) return true;
        return idSet(psidAccountIds).contains(externalAccountId);
    }

    /** Uploads one list; true when Meta accepted it. */
    private boolean syncList(String token, AdAccount account, MetaCustomerAudience row, List<List<String>> hashes) {
        if (hashes.isEmpty()) {
            return false;
        }
        return syncAudience(token, account, row, hashes.size(),
                (audienceId, edge) -> upload(token, audienceId, edge, hashes));
    }

    /** Uploads one PSID list, one request per Page (Meta needs the Page that issued each id); true when accepted. */
    private boolean syncPsidList(String token, AdAccount account, MetaCustomerAudience row, Map<String, List<String>> byPage) {
        int total = byPage.values().stream().mapToInt(List::size).sum();
        if (total == 0) {
            return false;
        }
        return syncAudience(token, account, row, total,
                (audienceId, edge) -> uploadPsids(token, audienceId, edge, byPage, total));
    }

    @FunctionalInterface
    private interface Uploader {
        void upload(String audienceId, String edge) throws Exception;
    }

    private boolean syncAudience(String token, AdAccount account, MetaCustomerAudience row, int count, Uploader uploader) {
        boolean sent = false;
        try {
            if (row.getExternalAudienceId() == null) {
                MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                form.add("name", NAMES.get(row.getKind()));
                form.add("subtype", "CUSTOM");
                form.add("description", "Tự động đồng bộ từ Smoothgate (khách trên CRM và Pancake), cập nhật mỗi ngày");
                form.add("customer_file_source", "USER_PROVIDED_ONLY");
                row.setExternalAudienceId(requireId(post(account.getExternalAccountId() + "/customaudiences", form, token)));
                row.setLastSyncedAt(null);
                audienceRepository.save(row);
            }
            if (isStillReplacing(row, LocalDateTime.now(VIETNAM_ZONE))) {
                // A new replace would restart Meta's matching from scratch; let the running one finish first.
                log.info("Meta customer audience {} for {} is still being replaced by Meta; skipped tonight",
                        row.getKind(), account.getExternalAccountId());
                return true;
            }
            if (nightlyRun && wasSentRecently(row, LocalDateTime.now(VIETNAM_ZONE))) {
                log.info("Meta customer audience {} for {} was already sent at {}; skipped tonight",
                        row.getKind(), account.getExternalAccountId(), row.getLastSyncedAt());
                return true;
            }
            // The first upload adds; later ones replace the whole list so removed or changed phones drop out.
            String edge = row.getLastSyncedAt() == null ? "users" : "usersreplace";
            uploader.upload(row.getExternalAudienceId(), edge);
            row.setLastSyncedAt(LocalDateTime.now(VIETNAM_ZONE));
            row.setLastCount(count);
            row.setLastError(null);
            sent = true;
        } catch (Exception e) {
            String message = explain(e.getMessage(), account);
            if (isMissingObject(message)) {
                row.setExternalAudienceId(null);
                row.setLastSyncedAt(null);
            }
            row.setLastError(truncate(message));
            log.warn("Meta customer audience {} for {} failed: {}", row.getKind(), account.getExternalAccountId(), message);
        }
        audienceRepository.save(row);
        return sent;
    }

    /**
     * Meta still reports the previous replace in progress (operation 414). Waits at most 3 days, then uploads anyway
     * in case Meta's status is stuck.
     */
    static boolean isStillReplacing(MetaCustomerAudience row, LocalDateTime now) {
        return row.getLastSyncedAt() != null
                && row.getMetaStatus() != null && row.getMetaStatus().startsWith("operation 414")
                && row.getLastSyncedAt().isAfter(now.minusDays(3));
    }

    static boolean wasSentRecently(MetaCustomerAudience row, LocalDateTime now) {
        return row.getLastSyncedAt() != null && row.getLastSyncedAt().isAfter(now.minusHours(RECENT_SEND_HOURS));
    }

    /** Created once from a synced list; Meta refreshes a lookalike on its own as its source list changes. */
    private void ensureLookalike(String token, AdAccount account, MetaCustomerAudience row,
                                 MetaCustomerAudience source, double ratio) {
        if (row.getExternalAudienceId() != null || source.getExternalAudienceId() == null || source.getLastSyncedAt() == null) {
            return;
        }
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("name", NAMES.get(row.getKind()));
            form.add("subtype", "LOOKALIKE");
            form.add("origin_audience_id", source.getExternalAudienceId());
            form.add("lookalike_spec", objectMapper.writeValueAsString(Map.of("ratio", ratio, "country", "VN")));
            row.setExternalAudienceId(requireId(post(account.getExternalAccountId() + "/customaudiences", form, token)));
            row.setLastSyncedAt(LocalDateTime.now(VIETNAM_ZONE));
            row.setLastError(null);
        } catch (Exception e) {
            // Usually the source is still being matched or has fewer than 100 people; retried next run.
            String message = explain(e.getMessage(), account);
            row.setLastError(truncate(message));
            log.warn("Meta lookalike {} for {} not created: {}", row.getKind(), account.getExternalAccountId(), message);
        }
        audienceRepository.save(row);
    }

    private void upload(String token, String audienceId, String edge, List<List<String>> hashes) throws Exception {
        long sessionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        List<List<List<String>>> batches = batches(hashes);
        for (int i = 0; i < batches.size(); i++) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("session_id", sessionId);
            session.put("batch_seq", i + 1);
            session.put("last_batch_flag", i == batches.size() - 1);
            session.put("estimated_num_total", hashes.size());

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("session", objectMapper.writeValueAsString(session));
            form.add("payload", objectMapper.writeValueAsString(Map.of("schema", SCHEMA, "data", batches.get(i))));
            JsonNode res = post(audienceId + "/" + edge, form, token);
            if (!res.has("num_received")) {
                throw new IllegalStateException("Meta không xác nhận danh sách: " + res);
            }
        }
    }

    /** One upload session for the whole list; each request carries one Page's ids (raw, Meta does not take them hashed). */
    private void uploadPsids(String token, String audienceId, String edge, Map<String, List<String>> byPage, int total)
            throws Exception {
        List<Map.Entry<String, List<String>>> requests = new ArrayList<>();
        byPage.forEach((page, ids) -> {
            for (List<String> batch : batches(ids)) requests.add(Map.entry(page, batch));
        });
        long sessionId = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
        for (int i = 0; i < requests.size(); i++) {
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("session_id", sessionId);
            session.put("batch_seq", i + 1);
            session.put("last_batch_flag", i == requests.size() - 1);
            session.put("estimated_num_total", total);

            Map<String, Object> payload = new LinkedHashMap<>();
            // Multi-key form: schema is a list and each row a list, as Meta's PAGEUID example shows.
            payload.put("schema", List.of(PSID_SCHEMA));
            payload.put("is_raw", true);
            payload.put("page_ids", List.of(requests.get(i).getKey()));
            payload.put("data", requests.get(i).getValue().stream().map(List::of).toList());

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("session", objectMapper.writeValueAsString(session));
            form.add("payload", objectMapper.writeValueAsString(payload));
            JsonNode res = post(audienceId + "/" + edge, form, token);
            if (!res.has("num_received")) {
                throw new IllegalStateException("Meta không xác nhận danh sách PSID (page " + requests.get(i).getKey() + "): " + res);
            }
            log.info("PSID upload {} page {}: sent {}, received {}, invalid {}", audienceId, requests.get(i).getKey(),
                    requests.get(i).getValue().size(), res.path("num_received").asInt(), res.path("num_invalid_entries").asInt());
            if (res.path("num_invalid_entries").asInt() > 0 && res.has("invalid_entry_samples")) {
                log.warn("PSID upload {} page {}: invalid samples {}", audienceId, requests.get(i).getKey(),
                        truncate(res.get("invalid_entry_samples").toString()));
            }
        }
    }

    private List<CustomerContact> pancakeContacts(Long tenantId, List<String> statuses, boolean buyersOnly) {
        List<CustomerContact> out = new ArrayList<>();
        for (Object[] r : orderRepository.findAudienceContacts(tenantId, statuses, buyersOnly)) {
            CustomerContact c = CustomerContact.of((String) r[0], (String) r[1], (String) r[2], null);
            if (c != null) out.add(c);
        }
        return out;
    }

    /** One contact per phone; a later source only fills fields the earlier ones left empty. */
    @SafeVarargs
    static Collection<CustomerContact> merge(List<CustomerContact>... sources) {
        Map<String, CustomerContact> byPhone = new LinkedHashMap<>();
        for (List<CustomerContact> source : sources) {
            for (CustomerContact c : source) {
                byPhone.merge(c.phone(), c, CustomerContact::mergedWith);
            }
        }
        return byPhone.values();
    }

    /** Rows in {@link #SCHEMA} order, each value SHA-256 hashed; a missing value is sent empty. */
    static List<List<String>> hashRows(Collection<CustomerContact> contacts) {
        List<List<String>> rows = new ArrayList<>(contacts.size());
        for (CustomerContact c : contacts) {
            rows.add(List.of(hash(c.phone()), hash(c.firstName()), hash(c.lastName()),
                    hash(c.gender()), hash(c.state()), hash(c.birthYear()), hash("vn")));
        }
        return rows;
    }

    private static String hash(String value) {
        return value == null || value.isEmpty() ? "" : MetaPurchaseEventService.sha256(value);
    }

    static <T> List<List<T>> batches(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += BATCH_SIZE) {
            out.add(items.subList(i, Math.min(items.size(), i + BATCH_SIZE)));
        }
        return out;
    }

    private MetaCustomerAudience row(Map<String, MetaCustomerAudience> rows, Long tenantId, AdAccount account,
                                     MetaCustomerAudience.Kind kind) {
        return rows.computeIfAbsent(account.getId() + ":" + kind, k -> {
            MetaCustomerAudience row = new MetaCustomerAudience();
            row.setTenantId(tenantId);
            row.setAdAccountId(account.getId());
            row.setKind(kind);
            return row;
        });
    }

    /** Whether this ad account gets Smoothgate's audiences (and is shown on the Meta sync page). */
    public boolean isIncluded(String externalAccountId) {
        if (idSet(excludedAccountIds).contains(externalAccountId)) return false;
        if (accountIdsFilter == null || accountIdsFilter.isBlank()) return true;
        return idSet(accountIdsFilter).contains(externalAccountId);
    }

    private static Set<String> idSet(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static String requireId(JsonNode res) {
        String id = res.path("id").asText("");
        if (id.isBlank()) throw new IllegalStateException("Meta không trả về id đối tượng: " + res);
        return id;
    }

    /** Adds where to fix the most common setup problem: custom audience terms not accepted on the account. */
    private static String explain(String message, AdAccount account) {
        String m = message == null ? "Lỗi không rõ" : message;
        String lower = m.toLowerCase();
        if (lower.contains("terms") || lower.contains("điều khoản")) {
            String act = account.getExternalAccountId().replace("act_", "");
            return m + " — chấp nhận điều khoản đối tượng tùy chỉnh tại "
                    + "https://business.facebook.com/ads/manage/customaudiences/tos/?act=" + act;
        }
        return m;
    }

    private static boolean isMissingObject(String message) {
        String lower = message.toLowerCase();
        return lower.contains("does not exist") || lower.contains("không tồn tại");
    }

    private static String truncate(String value) {
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private JsonNode get(String path, String fields, String token) {
        return call(() -> webClientBuilder.build().get()
                .uri(uri -> uri.scheme("https").host("graph.facebook.com").path("/" + apiVersion + "/" + path)
                        .queryParam("fields", fields).queryParam("access_token", token).build())
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(60)));
    }

    private JsonNode post(String path, MultiValueMap<String, String> form, String token) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>(form);
        body.add("access_token", token);
        return call(() -> webClientBuilder.build().post()
                .uri(GRAPH_BASE + "/" + apiVersion + "/" + path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(body))
                .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(60)));
    }

    private JsonNode call(java.util.function.Supplier<JsonNode> request) {
        try {
            JsonNode res = request.get();
            if (res == null) throw new IllegalStateException("Meta không phản hồi");
            return res;
        } catch (WebClientResponseException e) {
            // Meta's own message, never the request (which carries the token).
            String message = e.getStatusCode().toString();
            try {
                JsonNode err = objectMapper.readTree(e.getResponseBodyAsString()).path("error");
                message = err.path("error_user_msg").asText(err.path("message").asText(message));
            } catch (Exception ignored) {
                // keep the status code
            }
            throw new IllegalStateException("Meta: " + message);
        }
    }
}
