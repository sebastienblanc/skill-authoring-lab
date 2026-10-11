# Skill Authoring Lab — `create-quarkus-mcp-server`

A short loop for authoring a skill: **edit the skill, run one JUnit test, read a score.**

The test generates a Quarkus MCP server with a headless OpenCode session that uses the skill, sends the
generated sources to the Jev judge (TypeSafe "System One"), computes a score out of 10 from the judge's
typed answers, and fails below a minimum. The skill is the thing under test; the generated server is only
the evidence.

Maven coordinates: `org.sebi.skilllab:skill-authoring-lab:1.0.0`. The harness uses the
`org.sebi.skilllab` Java package; both skill variants request the same group ID and package for new
generated projects. Existing generated workspaces and their recorded scores are left unchanged.

```
skills/create-quarkus-mcp-server/SKILL.md   ──►  headless OpenCode  ──►  generated server
                                                     (skill tool)              │
                                              Jev judge  ◄── sources ──────────┘
                                                  │
                                      typed answers ──► weighted score ──► ≥ minimum ?
```

## Layout

| Path | Role |
| --- | --- |
| [`skills/create-quarkus-mcp-server/SKILL.md`](skills/create-quarkus-mcp-server/SKILL.md) | **The skill under authoring. Edit this.** |
| [`skills/create-quarkus-mcp-server-degraded/SKILL.md`](skills/create-quarkus-mcp-server-degraded/SKILL.md) | Deliberately weak, demo-only comparison variant |
| [`skills/create-quarkus-mcp-server/references/conventions-quarkus-mcp.md`](skills/create-quarkus-mcp-server/references/conventions-quarkus-mcp.md) | The compliance reference the skill points to |
| [`prompts/catalogue-server-request.md`](prompts/catalogue-server-request.md) | The business request sent to OpenCode, as a participant would type it |
| [`domain/catalogue-services.json`](domain/catalogue-services.json) | The data the generated server exposes |
| [`SkillEvalTest`](src/test/java/org/sebi/skilllab/SkillEvalTest.java) | **The loop: rubric, weights, minimum score.** |
| [`SkillWorkspace`](src/test/java/org/sebi/skilllab/SkillWorkspace.java) | Provided: fresh directory with the skill installed in `.opencode/skills/` and the permissions for a headless run |
| [`OpenCodeSession`](src/test/java/org/sebi/skilllab/OpenCodeSession.java) | Provided: runs `opencode run --format json`, parses the events, proves the skill was loaded |
| [`GeneratedProject`](src/test/java/org/sebi/skilllab/GeneratedProject.java) | Provided: the generated server as text, comments stripped |
| [`JevJudge`](src/test/java/org/sebi/skilllab/JevJudge.java) | Provided: client for the Jev `/v1/systemone` endpoint |
| [`RunDashboard`](src/test/java/org/sebi/skilllab/RunDashboard.java) | Read-only localhost dashboard of saved judgments |
| [`docs/skill-evaluation-linkedin.png`](docs/skill-evaluation-linkedin.png) | LinkedIn-ready diagram, with an editable SVG alongside it |

## Prerequisites

- JDK 21+ and Maven 3.9+.
- [OpenCode](https://opencode.ai) on the PATH with a configured provider (`opencode auth list`).
- A TypeSafe API key for the Jev judge (`TYPESAFE_API_KEY`).
- Network access to Maven Central: the agent compiles the server it generates.

## Run

```bash
export TYPESAFE_API_KEY=...
export OPENCODE_MODEL=opencode/claude-sonnet-4-5    # optional: provider/model, else OpenCode's default
mvn test
```

For file-based configuration, copy `.env.example` to `.env` and fill in your key locally.
Maven does not automatically read `.env`. If you keep settings in a trusted local `.env` file,
export them in the shell that launches Maven (the file is ignored by version control):

```bash
set -a
. ./.env
set +a
mvn test
```

One evaluation takes 2 to 10 minutes, most of it in the generation. Without `TYPESAFE_API_KEY` or without
the `opencode` binary the evaluation is **skipped**, not passed; local harness tests still run. Generation
prints its log paths immediately and a heartbeat approximately every 10 seconds, including elapsed time
and the size of the event log. Jev prints each request attempt and retry delay. Run `mvn test` without `-q`
to see the verdict in the console; it is also captured in `target/surefire-reports/TEST-*SkillEvalTest.xml`.

To check the harness without calling OpenCode or Jev (no API keys or model costs):

```bash
mvn test -Dtest=OpenCodeSessionTest,JevJudgeTest,SkillWorkspaceTest,RunReportsTest,RunDashboardTest
```

These JUnit 6 tests use local Java subprocesses and a local HTTP server to exercise completion, timeouts,
interruption, inherited stdout, provider errors, permissions, and judge retries.

## Demo: standard vs. degraded

The default remains the original skill. Select the degraded skill explicitly:

```bash
# Load credentials first, as above. These are two separate paid generations.
mvn test -Dtest=SkillEvalTest
mvn test -Dtest=SkillEvalTest -Dskill.lab.variant=degraded
```

The degraded recipe keeps the same Quarkus stack, catalogue and four MCP primitives, but deliberately
uses name-only descriptions, parsing/filtering inside annotated methods, and unchecked missing-record
lookups. It is **not a production recommendation**. Only the selected skill is installed in the workspace;
the prompt names that skill, and its completed skill-tool event is required before judging.

The rubric, weights, confidence gate and **7/10 pass threshold are unchanged**. The degraded run should
finish with a **Maven test failure**, not a harness error. Its score is saved before the assertion fails,
so it appears in the dashboard. Do not lower the threshold to make the demo green.

Observed runs in this workspace:

| Variant | Score / 10 | Date | Outcome |
| --- | --- | --- | --- |
| Standard | 9.78, 9.76 | 2026-10-09 | Passed |
| Degraded demo | 0.265 (displayed as 0.27) | 2026-10-11 | Below threshold; main defect `unhandled_errors` |

These are real observations, not fixed scores or a controlled benchmark. The generator used its default
model; pin `OPENCODE_MODEL`, `OPENCODE_VARIANT` and `TYPESAFE_MODEL`, keep the same prompt, and repeat both
variants for a stronger comparison. Use fresh workspaces when switching variants. These observations
predate the package rename to `org.sebi.skilllab`; historical snapshots and fingerprints were preserved.

## Score dashboard

In a separate terminal, start the dashboard (no API keys or frontend build required):

```bash
mvn test-compile exec:java
```

Open **http://127.0.0.1:8765/**. Stop with **Ctrl+C**. Change the port with
`-Dskill.lab.dashboard.port=8766` if needed. It listens on loopback only, serves no project files or
credentials, and cannot launch evaluations.

The dashboard refreshes every five seconds and includes variant averages, score bars with each run's
threshold, a variant filter, and a history table. **Details** shows the three weighted contributions,
confidence, model, installed-skill fingerprint and workspace. Undecided judgments and rejudges do not
count toward variant means.

Every new judgment gets a unique JSON history entry, including below-threshold and low-confidence
results. Rejudging the same project adds a row instead of overwriting history. Reports from before the
dashboard are also discovered under `target/workspaces/`; they are marked **legacy**, use their file
timestamp, and have incomplete provenance. A malformed report produces a visible warning.

Generation or HTTP failures that never produced a score are not included. History lives under `target/`:
**`mvn clean` deletes it**, so copy it elsewhere before cleaning if you want to keep your demo results.
A fresh clone starts with an empty dashboard: generated projects, raw model traces, and score history are
local artifacts excluded from Git. The diagram and documented sample scores are included.

## What the test does

1. **Workspace**: `target/workspaces/<timestamp>/` receives the skill (copied to `.opencode/skills/`), the
   catalogue and an `opencode.json` that allows `edit`, `bash` and `skill` without prompting. Questions and
   repeated-call approval prompts (`doom_loop`) are denied rather than waiting for input. Temporary-directory
   allow rules follow the catch-all deny because OpenCode uses the last matching rule. The frontmatter
   of `SKILL.md` is validated first: OpenCode silently ignores a skill whose `name` differs from its directory.
2. **Generation**: `opencode run --format json --pure "<prompt>"` in that directory, stdin closed, with a
   timeout. Stdout is redirected straight to `.skill-lab/opencode-events.jsonl`, so an inherited stdout
   handle cannot hold Maven in an output-reader join after OpenCode exits. On timeout or interruption,
   OpenCode and its observed descendants (including Maven builds) are terminated; logs are kept. Provider
   error events fail the generation even if OpenCode exits with code zero.
3. **Proof of use**: the event log must contain a completed `skill` tool call for
   the selected skill (`create-quarkus-mcp-server` by default). Otherwise the test fails: the score would
   measure the base model, not the skill. Rejudging also checks the saved event log and installed skill.
4. **Sanity gates**: the workspace holds a `pom.xml` declaring `quarkus-mcp-server` and at least one Java
   source. No judge call is paid for an empty project.
5. **Judge**: the sources of `src/main/java`, comments stripped, go to Jev as a named JSON state together
   with the rubric. Jev returns calibrated probabilities, not prose. A five-minute overall deadline covers
   all attempts, response-body reads, and retry delays. A `Retry-After` that exceeds the remaining time
   fails immediately instead of putting Maven to sleep beyond the deadline.
6. **Score and verdict**: the score is computed in code from the answers and compared to the minimum.
   A low confidence on the score question aborts the test as *skipped*: that output is for a human to read.

## Scoring

| Question id | Type | Weight | What it measures |
| --- | --- | --- | --- |
| `useful_descriptions` | score, 3 levels | 0.5 | Do `@Tool` / `@ToolArg` descriptions tell an agent what the tool does, what each argument expects, and what comes back? |
| `clean_delegation` | noul | 0.3 | Do annotated methods only delegate to an injected service and format the result? |
| `not_found_handled` | noul | 0.2 | Do annotated methods answer with a readable message when the service does not exist? |
| `main_defect` | choice | 0 | Reported, not weighted: the most serious defect the judge sees |

`score = 10 × (0.5 × useful_descriptions + 0.3 × clean_delegation + 0.2 × not_found_handled)`,
**minimum 7/10** by default. Things a regex can check (snake_case names, presence of an annotation, the HTTP
dependency) are deliberately not asked of the judge; they belong in deterministic assertions like those of
`tp3-eval`.

## Configuration

| Setting | Where | Default | Effect |
| --- | --- | --- | --- |
| `TYPESAFE_API_KEY` | env | required | Enables the test |
| `TYPESAFE_MODEL` | env | `jev-latest` | Pin a judge version once thresholds are calibrated |
| `TYPESAFE_ENDPOINT` | env | public API | Alternative endpoint (gateway) |
| `TYPESAFE_TRACE` | env | unset | Also print the judge request and response to the console |
| `OPENCODE_MODEL` | env | OpenCode default | `provider/model` passed as `--model` |
| `OPENCODE_VARIANT` | env | unset | Provider-specific reasoning effort passed as `--variant` |
| `OPENCODE_BIN` | env | `opencode` | Path to the OpenCode binary |
| `skill.lab.variant` | `-D` | `standard` | Select `standard` or the intentionally `degraded` demo skill |
| `skill.lab.min.score` | `-D` | `7` | Minimum score to pass |
| `skill.lab.timeout.minutes` | `-D` | `15` | Generation timeout |
| `skill.lab.judge.timeout.seconds` | `-D` | `300` | Overall judge deadline, including retries and response bodies |
| `skill.lab.workspace` | `-D` | `target/workspaces/<timestamp>` | Fixed workspace directory |
| `skill.lab.generated` | `-D` | unset | Judge an existing generated project and skip the generation |
| `skill.lab.dashboard.port` | `-D` | `8765` | Loopback dashboard port for `exec:java` |

Judging again without regenerating, for instance while rewording a question:

```bash
mvn test -Dskill.lab.generated=target/workspaces/20261008-101500
```

Do not combine `skill.lab.generated` with `skill.lab.variant`: the installed skill and saved proof of use
identify the original variant. Keep the workspace's `.opencode/skills/` snapshot and `.skill-lab/` event log.

## Outputs

| File | Content |
| --- | --- |
| `target/workspaces/<timestamp>/` | The generated server, kept for inspection |
| `…/.skill-lab/opencode-command.txt` | The exact command and prompt |
| `…/.skill-lab/opencode-events.jsonl` | Every JSON event of the session: tool calls, text, tokens, cost |
| `…/.skill-lab/opencode-stderr.log` | OpenCode's own logs |
| `…/.skill-lab/jev-request.json`, `jev-response.json` | What the judge received and answered |
| `…/.skill-lab/run.json` and `target/skill-lab/last-run.json` | Latest judgment: score, answers, variant, timestamp, outcome, proof of use, session figures and installed `SKILL.md` SHA-256 |
| `target/skill-lab/runs/<run-id>.json` | Append-only judgment history, including failed thresholds and undecided scores |

## LinkedIn diagram

Upload [`docs/skill-evaluation-linkedin.png`](docs/skill-evaluation-linkedin.png) to your post: it is a
1600 x 1600 PNG. The editable source is [`docs/skill-evaluation-linkedin.svg`](docs/skill-evaluation-linkedin.svg).
It explains the flow, proof of skill use, unchanged weighted rubric, and the observed demo comparison.
The numbers are a dated snapshot, not a promise of future scores.

To regenerate the PNG on macOS after editing the SVG:

```bash
qlmanage -t -s 1600 -o docs docs/skill-evaluation-linkedin.svg
mv docs/skill-evaluation-linkedin.svg.png docs/skill-evaluation-linkedin.png
```

## The authoring loop

1. Run the test once as is and read `last-run.json`: the score, the likely level of each question, the main defect.
2. Change **one** rule in `SKILL.md`. Keep only the delta the model does not apply alone; a rule that repeats
   what the model already does is cost, not value.
3. Run again. One generation is one sample: a score that moves by less than a point tells you nothing. Run
   three times before concluding, and compare the `run.json` files, which carry the skill's fingerprint.
4. When a question's confidence stays low, the levels are badly worded: rewrite the situations they describe
   rather than lowering the minimum.

Experiments worth doing:

- **Remove** the "Descriptions" section of the skill and watch `useful_descriptions` drop.
- **Remove** the first line of the prompt that names the skill: now only the skill's `description` can make
  the agent load it. If the proof-of-use assertion fails, the description needs work, not the body.
- **Change the model** (`OPENCODE_MODEL`) at the same skill version: the delta between models is the part of
  the skill that is really needed.
- **Raise the minimum** once three runs clear it comfortably.

## Authoring log

What the loop taught so far. Add a line each time a run changes the skill.

| Date | Model | Observation | Change to the skill |
| --- | --- | --- | --- |
| 2026-10-08 | `opencode/big-pickle` | Structure, names and descriptions followed the skill, but the code imported `io.quarkiverse.mcp.server.annotation.Tool` and `…server.prompt.PromptMessage`, which do not exist; the agent then inspected jars with `javap` in `/tmp` and the session died on the rejected permission. | Added "Imports: one flat package". Allowed the temp directory in the workspace permissions. |
| 2026-10-08 | `opencode/big-pickle` | `@ResourceTemplateArg(name, description)` and `TextResourceContents.of(...)` do not compile: the skill itself was wrong about the 2.0.2 API, and the agent followed it faithfully. | Fixed the resource-template rule: `@ResourceTemplateArg` has only `name`; use `TextResourceContents.create(uri, text)`. |

## Troubleshooting

| Symptom | Cause and fix |
| --- | --- |
| Test skipped | `TYPESAFE_API_KEY` unset, or `opencode --version` fails. |
| Degraded demo fails with a low score | Expected: the weak recipe is being measured against the unchanged quality gate. The saved score still appears in the dashboard. |
| Dashboard has no runs | Complete at least one evaluation. It only displays scored results; check whether `mvn clean` deleted earlier reports. |
| Dashboard cannot refresh | Restart `mvn test-compile exec:java`, check the port, and inspect the dashboard server console. Stale data stays visible with an error banner. |
| Workspace contains another skill | A fixed workspace was reused across variants. Use a fresh directory or omit `skill.lab.workspace`. |
| "The session never loaded the skill" | `name` in the frontmatter differs from the directory, invalid frontmatter, or the agent decided not to load it: look at `opencode-events.jsonl`, then at the skill `description`. |
| Generation appears stuck | Follow the heartbeat and inspect the printed `opencode-events.jsonl` and `opencode-stderr.log` paths. A growing log means OpenCode is emitting events; no new events can mean provider startup, a slow tool, or a stalled session. The generation deadline still applies. |
| "OpenCode did not finish within …" | Inspect the saved logs first. A slow model or a first Maven dependency download may need a higher `-Dskill.lab.timeout.minutes`, or a pre-warmed local Maven repository. The harness stops OpenCode and observed child processes on timeout. |
| "OpenCode exited with code …" | Read `opencode-stderr.log`: provider authentication, unknown model id, network. |
| No `pom.xml` in the workspace | The agent answered without writing files: read the final text in the events log. |
| Session ends after a few tool calls, no answer, no files | A rejected permission ends a headless session. Look for `"status":"error"` in the events: the agent tried to leave the workspace or use a denied tool. Tighten the prompt, or widen the permissions written by `SkillWorkspace`. |
| HTTP 401/403 from the judge | Wrong `TYPESAFE_API_KEY` or endpoint. |
| "Jev did not finish within …" or "retry delay … exceeds …" | The overall judge budget expired, or the server requested a retry beyond it. Inspect `jev-request.json` and the console attempts; rerun later for rate limits, or increase `-Dskill.lab.judge.timeout.seconds` for a slow endpoint. |
| Score varies a lot between runs | Normal for one sample. Compare several runs; pin `TYPESAFE_MODEL` and `OPENCODE_MODEL`. |
