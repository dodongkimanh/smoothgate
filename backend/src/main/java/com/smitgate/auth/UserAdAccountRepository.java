package com.smitgate.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserAdAccountRepository extends JpaRepository<UserAdAccount, UserAdAccount.Key> {

    List<UserAdAccount> findByUserId(Long userId);

    List<UserAdAccount> findByUserIdIn(List<Long> userIds);

    @Modifying
    @Transactional
    void deleteByUserId(Long userId);
}
