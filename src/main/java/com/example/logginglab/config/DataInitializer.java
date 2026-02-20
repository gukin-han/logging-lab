package com.example.logginglab.config;

import com.example.logginglab.entity.DummyEntity;
import com.example.logginglab.repository.DummyRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DataInitializer implements ApplicationRunner {

    private final DummyRepository repository;

    public DataInitializer(DummyRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<DummyEntity> entities = new ArrayList<>();
        for (int i = 1; i <= 500; i++) {
            entities.add(new DummyEntity(
                    "User " + i,
                    "user" + i + "@example.com",
                    "Department " + (i % 10),
                    "Dummy description for user " + i + ". This text exists to increase log output volume when jdbc.resultset is enabled."
            ));
        }
        repository.saveAll(entities);
    }
}
