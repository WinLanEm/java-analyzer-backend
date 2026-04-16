# Project Context: Code Analyzer (Monorepo)

## 1. Backend Stack & Architecture (Java)
* **Language:** Java (Vanilla, No Frameworks like Spring/Quarkus)
* **HTTP Server:** Built-in `com.sun.net.httpserver.HttpServer`
* **JSON Processing:** Jackson (`com.fasterxml.jackson.databind`)
* **AST Parsing:** JavaParser (`com.github.javaparser:javaparser-core`)
* **Build Tool:** Maven (`pom.xml`)
* **Architecture:** Strict Layer Isolation (Controllers -> Services -> Actions). Use Java `record` for DTOs.
* **Rules:** No annotation-based Dependency Injection. Use constructor injection. Global error handling in the HTTP context.

## 2. Frontend Stack & Architecture
* **Stack:** Vue 3 (Composition API, `<script setup>`), TypeScript, Vite, TailwindCSS.
* **Graph Library:** Vue Flow + Dagre (for layout).
* **Editor:** Monaco Editor.
* **State Management:** Pinia.
* **Rules:** Strictly use Vue 3 Composition API. Keep styling within Tailwind utility classes. Do not alter backend logic when generating frontend code unless explicitly requested.

## 3. Infrastructure & DevOps
* **Containerization:** Docker & Docker Compose.
* **Backend Container:** Multi-stage Maven build -> JRE runner. Exposes port 8080.
* **Frontend Container:** Multi-stage Node build -> Nginx runner. Exposes port 80.
* **Network:** Both containers must run in the same internal Docker network. Frontend connects to Backend via exposed ports or reverse proxy.