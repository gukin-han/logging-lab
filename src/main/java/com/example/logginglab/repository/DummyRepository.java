package com.example.logginglab.repository;

import com.example.logginglab.entity.DummyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DummyRepository extends JpaRepository<DummyEntity, Long> {
}
