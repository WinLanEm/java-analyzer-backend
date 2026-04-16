# Project Context: Code Analyzer (Java Backend)

## Stack & Technologies
* **Language:** Java (Vanilla, No Frameworks like Spring/Quarkus)
* **HTTP Server:** Built-in `com.sun.net.httpserver.HttpServer`
* **JSON Processing:** Jackson (`com.fasterxml.jackson.databind`)
* **AST Parsing:** JavaParser (`com.github.javaparser:javaparser-core`)
* **Build Tool:** Maven / Gradle (assume standard dependency management)

## Architecture & Code Standards
Strict Layer Isolation is REQUIRED. Do not mix responsibilities.
1. **Controllers (HTTP Handlers):** Only handle HTTP requests/responses, extract payload, and pass to Services. No business logic.
2. **Services:** Orchestrate the flow between Actions.
3. **Actions:** Do the actual heavy lifting (e.g., `BuildCfgAction`, `SimulateExecutionAction`). One Action = One specific business behavior.
4. **DTOs:** Use Java `record` classes for all data transfer (Requests, Responses, Graph Nodes, Edges, execution steps).
5. **Validators:** Separate classes for validating incoming code strings or DTOs.

## Engineering Rules
* **No Magic:** Do not use annotations for Dependency Injection. Use pure Constructor Injection.
* **Immutability:** Favor immutable data structures and `final` fields wherever possible.
* **Error Handling:** Use a global exception handler logic within the HTTP context to return standardized JSON errors (e.g., 400 Bad Request for unparseable code).
* **Graph Logic:** The CFG (Control Flow Graph) must explicitly support branching (`if/else`), loops (`for/while`), and jumps (`break/continue/throw`).

## Agent Instructions
When generating code for this project, always adhere to the Vanilla Java constraints. Never import Spring, Jakarta EE, or other heavy framework libraries unless explicitly requested. Always provide clean, highly readable, and documented code.