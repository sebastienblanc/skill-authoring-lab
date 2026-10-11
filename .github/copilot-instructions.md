# Project conventions

This is a Maven/Java 21 skill evaluation harness: OpenCode generates a project and
Jev scores the generated sources. Preserve the skill-loading proof and scoring rubric.

- Write unit tests with JUnit 6.
- Keep subprocess and HTTP waits bounded, including cleanup and retry delays.
- Exercise failure paths with local subprocess fixtures and local HTTP servers.
- Keep API keys in environment variables, never in source or recorded requests.
- Document changes to run settings and diagnostics in README.md.
