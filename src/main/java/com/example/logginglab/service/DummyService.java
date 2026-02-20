package com.example.logginglab.service;

import com.example.logginglab.entity.DummyEntity;
import com.example.logginglab.repository.DummyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class DummyService {

    private final DummyRepository repository;

    public DummyService(DummyRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<DummyEntity> findAll() {
        return repository.findAll();
    }
}
