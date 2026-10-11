# House conventions — Quarkus MCP server (service catalogue)

This document is the **compliance reference**. The skill must encode it; the scorers verify it.

## Stack and structure
- Extension: `io.quarkiverse.mcp:quarkus-mcp-server-http` (HTTP transport); `-stdio` only on request.
- Maven group ID and root package: `org.sebi.skilllab`.
- **Strict separation** between business logic and the MCP adapter:
  - `CatalogueService`: loads `catalogue-services.json` (classpath) and exposes the logic.
  - `CatalogueMcpServer`: contains ONLY the MCP annotations and delegates to the service.

## Primitives (service catalogue domain)
- **Tools** (verbs, snake_case):
  - `find_service(query)`: search by service name or team.
  - `get_owner(service)`: owning team and criticality.
- **Resource**: `service://{name}` → full record (team, criticality, dependencies).
- **Prompt**: `fiche_service` → summary template for the on-call engineer.

## Annotations
- `@Tool(description = "…")` on each tool, `@ToolArg(description = "…")` on each argument.
- `@ResourceTemplate(uriTemplate = "service://{name}")` + `@ResourceTemplateArg`.
- `@Prompt` with an explicit `name`.

## Tests and observability
- At least one test proving that the server starts and that the primitives register at build time.
- Application logs enabled (INFO level).

## The data

`catalogue-services.json` keeps its original French field names; do not rename them:

```json
{ "services": [
  { "name": "facturation", "equipe": "Team Billing", "criticite": "haute", "depend_de": ["auth", "catalogue-produits"] }
] }
```

| Field | Meaning | Values |
| --- | --- | --- |
| `name` | service identifier, unique | lowercase, hyphens |
| `equipe` | owning team | free text |
| `criticite` | criticality | `critique`, `haute`, `moyenne`, `basse` |
| `depend_de` | names of the services it depends on | possibly empty list |
