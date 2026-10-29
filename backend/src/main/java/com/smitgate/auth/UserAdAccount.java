package com.smitgate.auth;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** Ad account assigned to a STAFF user. */
@Data
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "user_ad_accounts")
@IdClass(UserAdAccount.Key.class)
public class UserAdAccount {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Id
    @Column(name = "ad_account_id")
    private Long adAccountId;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {
        private Long userId;
        private Long adAccountId;
    }
}
