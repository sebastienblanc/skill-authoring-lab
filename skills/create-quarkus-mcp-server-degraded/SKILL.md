---
name: create-quarkus-mcp-server-degraded
description: >-
  Generate a deliberately bare-bones Quarkus MCP catalogue server for a skill-quality
  comparison demo. Use only when this degraded demo variant is explicitly requested.
---

# Minimal catalogue server (degraded demo)

This is a deliberately weak, happy-path-only implementation recipe for an evaluation
demo, not a production recommendation. Generate real, compilable code using the rules
below. Do not discuss evaluation or scores in the generated sources.

## Working stack

- Maven, Java 21 (`maven.compiler.release` = 21).
- Import `io.quarkus.platform:quarkus-bom:3.40.1`.
- Use `io.quarkiverse.mcp:quarkus-mcp-server-http:2.0.2`, `io.quarkus:quarkus-jackson`,
  and the `quarkus-maven-plugin` with the `build` goal.
- Maven group ID and root package: `org.sebi.skilllab`.
- Copy `catalogue-services.json` unchanged to `src/main/resources/`.
- MCP types live directly in `io.quarkiverse.mcp.server`, not subpackages.

## One class, happy path only

Use one `@ApplicationScoped CatalogueMcpServer`. Do not introduce a service layer,
repository, or injected business service. Inject Jackson's `ObjectMapper` directly.
In each MCP-annotated method, load the catalogue from the classpath, parse it, and
search or filter it there. It is fine to repeat this small amount of code.

Keep annotation descriptions as short labels, exactly the public name for tools
and exactly the argument name for arguments: `description = "find_service"`,
`description = "get_owner"`, `description = "query"`, `description = "service"`.
Do not add examples, input formats, or explanations of return values.

Assume requested service names exist. For exact-name lookups, use
`findFirst().orElseThrow()` without catching the missing-record exception or
returning a fallback message. Let input and parsing errors propagate.
Do not add user-facing not-found handling. A blank search query may match all rows.

## Keep all four MCP primitives

- `@Tool(name = "find_service", description = "find_service")`: case-insensitive
  substring search by name or team. Return `ToolResponse.success(text)`.
- `@Tool(name = "get_owner", description = "get_owner")`: exact-name lookup,
  return the team and criticality using `ToolResponse.success(text)`.
- `@ResourceTemplate(uriTemplate = "service://{name}", description = "service")`:
  return the full record with `TextResourceContents.create(uri, text)`.
  Its argument is `@ResourceTemplateArg(name = "name")` (no description attribute).
- `@Prompt(name = "fiche_service", description = "fiche_service")`: an on-call
  summary for an exact service name. Use `@PromptArg(description = "service")`
  and return `List<PromptMessage>` using `PromptMessage.withUserRole(text)`.

Run `mvn -q -DskipTests compile` and fix compilation errors. Do not start the server
or run `quarkus:dev`. Keep the intentionally minimal structure and descriptions;
do not refactor them into the full-conventions implementation.
