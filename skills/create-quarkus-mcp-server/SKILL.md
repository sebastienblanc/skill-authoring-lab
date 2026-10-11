---
name: create-quarkus-mcp-server
description: >-
  Generate or extend an MCP server in Java with Quarkus that follows the SCIAM house conventions:
  HTTP transport, strict split between business logic and the MCP adapter, snake_case tool names,
  agent-oriented descriptions on every annotation, handled not-found cases. Use whenever asked to
  create, scaffold or extend an MCP server in Java or Quarkus, even if the request does not mention
  any convention.
---

# create-quarkus-mcp-server

Read `references/conventions-quarkus-mcp.md` before writing code: it is the compliance reference.
This file only holds the rules the base model does not apply on its own.

## Stack and transport

- Maven, Java 21 (`maven.compiler.release` = 21), Quarkus BOM `io.quarkus.platform:quarkus-bom`
  version 3.40.1 imported in `dependencyManagement`.
- Extension `io.quarkiverse.mcp:quarkus-mcp-server-http` version 2.0.2 (HTTP transport). Never add the
  `-stdio` artifact unless the request asks for stdio explicitly.
- `io.quarkus:quarkus-jackson` for parsing the catalogue; `io.quarkus:quarkus-junit5` in test scope.
- `quarkus-maven-plugin` with the `build` goal, as in any Quarkus project.

## Imports: one flat package

Every MCP type lives directly in `io.quarkiverse.mcp.server`: `Tool`, `ToolArg`, `ToolResponse`,
`ResourceTemplate`, `ResourceTemplateArg`, `TextResourceContents`, `Prompt`, `PromptArg`, `PromptMessage`.
There is no `.annotation`, `.prompt` or `.resources` sub-package: do not invent one, and do not inspect
jars to find out.

## Structure (non-negotiable)

- Maven group ID and root package: `org.sebi.skilllab`.
- `CatalogueService` (`@ApplicationScoped`): loads `catalogue-services.json` from the classpath once,
  with Jackson, and holds every loop, filter and parsing. It exposes at least
  `List<Service> findService(String query)` and `Optional<Service> getByName(String name)`.
- `CatalogueMcpServer` (`@ApplicationScoped`): the only class with MCP annotations. Each annotated method
  calls the injected `CatalogueService` and formats its result as text. This class contains no
  `ObjectMapper`, no file or resource access, no stream or loop over the catalogue data.
- The catalogue is read from the classpath (`src/main/resources/catalogue-services.json`), never from an
  absolute path or the working directory. Copy the JSON file there unchanged.

## Primitives: exact public names

| Kind | Public name | Argument(s) | Returns |
| --- | --- | --- | --- |
| tool | `find_service` | `query`: case-insensitive substring of a service name or team | the matching services, one per line: name, team, criticality |
| tool | `get_owner` | `service`: exact service name | owning team and criticality of that service |
| resource template | `service://{name}` | `name`: exact service name | full record: name, team, criticality, dependencies |
| prompt | `fiche_service` | `service`: exact service name | an on-call summary template for that service |

- Tools are verbs in snake_case. Set the public name explicitly: `@Tool(name = "find_service", ...)`.
  The Java method name does not matter to the client; the `name` attribute does.
- Tools return `ToolResponse` (`ToolResponse.success(text)`).
- Resource template: `@ResourceTemplate(uriTemplate = "service://{name}", description = ...)` with a
  `@ResourceTemplateArg(name = "name")` parameter. `ResourceTemplateArg` has only a `name` attribute, so the
  argument is described in the template's description. Return `TextResourceContents.create(uri, text)`.
- Prompt: `@Prompt(name = "fiche_service", description = ...)` with a `@PromptArg(...)` parameter, returning
  `List<PromptMessage>` built with `PromptMessage.withUserRole(...)` / `withAssistantRole(...)`.

## Descriptions: an agent reads them, not a human

Every `@Tool`, `@ToolArg`, `@ResourceTemplate`, `@Prompt` and `@PromptArg` carries a `description`
that states, in one or two sentences:

1. what the primitive does;
2. what each argument expects, with the format or an example (`"exact service name, e.g. 'facturation'"`);
3. what is returned, including what the caller gets when nothing is found.

Never use the name as the description. "The query" or "Service name" are not descriptions.

## Not-found and invalid input

Annotated methods never throw and never return `null` to the client. An unknown service yields a
readable message, for example `ToolResponse.success("Service 'xyz' not found in the catalogue.")`, and the
equivalent text for the resource and the prompt. A blank `query` returns every service.

## Tests and observability

- One `@QuarkusTest` that starts the application (an empty test method is enough to prove the primitives
  register at build time).
- `src/main/resources/application.properties` with `quarkus.log.level=INFO`.

## Checklist before answering

1. Run `mvn -q -DskipTests compile` and fix every compile error before answering.
2. The four public names of the table appear exactly once each in `CatalogueMcpServer`.
3. `CatalogueMcpServer` contains no `ObjectMapper`, `Files`, `getResourceAsStream`, `stream()` or `for` over
   the catalogue data.
4. Every annotation has a description meeting the three points above.
5. Do not start the server and do not run `quarkus:dev`.
