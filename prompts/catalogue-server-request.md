Load the create-quarkus-mcp-server skill with the skill tool before writing any code, and follow it.

Generate, in the current directory, a Quarkus MCP server over the file catalogue-services.json
(already present here) that exposes:

- a tool `find_service`: search services by name or team;
- a tool `get_owner`: owning team and criticality of a service;
- a resource `service://{name}`: full record of a service (team, criticality, dependencies);
- a prompt `fiche_service`: an on-call summary template for a service.

Constraints:
- Work only in the current directory and never leave it (no cd to another directory). Do not ask questions: decide and move on.
- You may run Maven to compile. Do not start the server and do not run quarkus:dev.
- When done, reply with the list of files you created, nothing else.
