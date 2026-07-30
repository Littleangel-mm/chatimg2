package com.example.chatseedance2.repository;

import com.example.chatseedance2.entity.Admin;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SeedanceAdminRepository extends JpaRepository<Admin, Integer> {
}
