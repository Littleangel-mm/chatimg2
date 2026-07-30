package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.UserAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SeedanceUserAccountRepository extends JpaRepository<UserAccount, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select account from SeedanceUserAccount account where account.id = :id")
    Optional<UserAccount> findByIdForUpdate(@Param("id") Integer id);
}
