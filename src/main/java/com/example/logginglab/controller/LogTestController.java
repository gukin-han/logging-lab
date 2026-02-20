package com.example.logginglab.controller;

import com.example.logginglab.entity.DummyEntity;
import com.example.logginglab.service.DummyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/logs")
public class LogTestController {

    private final DummyService dummyService;

    public LogTestController(DummyService dummyService) {
        this.dummyService = dummyService;
    }

    @GetMapping("/test")
    public List<DummyEntity> test() {
        return dummyService.findAll();
    }
}
