package com.smitgate.note;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface AdNoteRepository extends JpaRepository<AdNote, Long> {

    List<AdNote> findByTenantIdAndAdIdOrderByCreatedAtDesc(Long tenantId, String adId);

    @Query("SELECT n.adId AS adId, COUNT(n) AS cnt FROM AdNote n "
            + "WHERE n.tenantId = :tenantId AND n.adId IN :adIds GROUP BY n.adId")
    List<AdNoteCountProjection> countByTenantIdAndAdIdIn(
            @Param("tenantId") Long tenantId, @Param("adIds") List<String> adIds);

    interface AdNoteCountProjection {
        String getAdId();
        long getCnt();
    }
}
