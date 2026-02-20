# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

- **Build:** `./gradlew build`
- **Run tests:** `./gradlew test`
- **Run a single test class:** `./gradlew test --tests com.example.logginglab.SomeTestClass`
- **Run a single test method:** `./gradlew test --tests com.example.logginglab.SomeTestClass.methodName`
- **Run the application:** `./gradlew bootRun`

## Tech Stack

- Java 21, Spring Boot 4.0.3, Gradle 9.3.0 (Kotlin DSL)
- JUnit 5 (JUnit Platform) for testing
- Base package: `com.example.logginglab`
