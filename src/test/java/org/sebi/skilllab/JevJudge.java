package org.sebi.skilllab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * PROVIDED — "System One" judge: TypeSafe Jev, endpoint POST /v1/systemone (https://docs.typesafe.ai/api).
 *
 * Jev does not generate text. It receives a state (the sources) and typed questions, and returns calibrated
 * probabilities, one per question, in a single call:
 *  - Noul   : yes/no question → probability that the answer is yes (0..1);
 *  - Score  : ordered levels described in words → position on the scale + confidence;
 *  - Choice : named options → most probable option + distribution + confidence.
 * The mark out of 10 is never asked of the model: it is computed in code from the answers
 * ("composite scoring"). There is no free-form JSON to parse and no justification to read: the answers are
 * the justification.
 *
 * Environment: TYPESAFE_API_KEY (required), TYPESAFE_MODEL (default jev-latest; pin jev-1.13.0 once thresholds
 * are calibrated), TYPESAFE_ENDPOINT (default: the public API). The raw request and response are always written
 * to {@code <traceDir>/jev-request.json} and {@code <traceDir>/jev-response.json}; TYPESAFE_TRACE=1 also
 * prints them to the console.
 */
public final class JevJudge {

    public static final String DEFAULT_ENDPOINT = "https://api.typesafe.ai/v1/systemone";
    public static final String DEFAULT_MODEL = "jev-latest";

    // --- Questions ---------------------------------------------------------------------------
    /** instructions: a String, or a Map for a structured instruction (the question in one field, the data it
     *  cites in the others, referenced in backticks: `field`). */
    public sealed interface Question permits Noul, Score, Choice {}
    public record Noul(Object instructions, Object yes, Object no) implements Question {}
    public record Score(Object instructions, List<?> levels) implements Question {}
    public record Choice(Object instructions, Map<String, ?> options) implements Question {}

    public static Noul noul(Object instructions) { return new Noul(instructions, null, null); }
    /** yes / no: what an answer close to 1 and an answer close to 0 mean. */
    public static Noul noul(Object instructions, Object yes, Object no) { return new Noul(instructions, yes, no); }
    /** levels: lowest to highest, 2 to 10 levels, each describing a situation (not a degree). */
    public static Score score(Object instructions, Object... levels) { return new Score(instructions, List.of(levels)); }
    public static Choice choice(Object instructions, Map<String, ?> options) { return new Choice(instructions, options); }

    // --- Answers -----------------------------------------------------------------------------
    public sealed interface Answer permits NoulAnswer, ScoreAnswer, ChoiceAnswer {}
    public record NoulAnswer(double noul) implements Answer {
        public boolean yes(double threshold) { return noul >= threshold; }
    }
    public record ScoreAnswer(double score, double confidence, Map<Integer, Double> probabilities, Map<Integer, String> legend) implements Answer {
        /** The score brought back to 0..1: score / (number of levels − 1). Required before weighting scales of
         *  different lengths. */
        public double normalized() { return legend.size() <= 1 ? 0 : score / (legend.size() - 1); }
        /** The most probable level, in words. */
        public String likelyLevel() {
            return probabilities.entrySet().stream().max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + ": " + legend.get(e.getKey())).orElse("?");
        }
    }
    public record ChoiceAnswer(String choice, double confidence, Map<String, Double> probabilities) implements Answer {}

    /** The answers of one call, under the identifiers chosen for the questions. */
    public record Verdict(String model, Map<String, Answer> answers, int inputTokens) {
        public NoulAnswer noul(String id) { return (NoulAnswer) answer(id, NoulAnswer.class); }
        public ScoreAnswer score(String id) { return (ScoreAnswer) answer(id, ScoreAnswer.class); }
        public ChoiceAnswer choice(String id) { return (ChoiceAnswer) answer(id, ChoiceAnswer.class); }
        private Answer answer(String id, Class<? extends Answer> type) {
            Answer a = answers.get(id);
            if (a == null) throw new IllegalArgumentException("No answer '" + id + "'; received: " + answers.keySet());
            if (!type.isInstance(a)) throw new IllegalArgumentException("'" + id + "' is a " + a.getClass().getSimpleName() + ", not a " + type.getSimpleName());
            return a;
        }
        /** Readable summary for a failure message: one line per question. */
        public String summary() {
            StringBuilder sb = new StringBuilder("model ").append(model).append('\n');
            answers.forEach((id, a) -> sb.append("  ").append(id).append(" = ").append(switch (a) {
                case NoulAnswer n -> String.format("noul %.2f", n.noul());
                case ScoreAnswer s -> String.format("score %.2f of %d, i.e. %.2f normalized (confidence %.2f, likely level %s)",
                    s.score(), s.legend().size() - 1, s.normalized(), s.confidence(), s.likelyLevel());
                case ChoiceAnswer c -> String.format("choice %s (confidence %.2f, %s)", c.choice(), c.confidence(), c.probabilities());
            }).append('\n'));
            return sb.toString();
        }
    }

    // --- Call --------------------------------------------------------------------------------
    private static final ObjectMapper M = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final Duration timeout;
    private final Path traceDir;

    /** @param traceDir where jev-request.json and jev-response.json are written. */
    public JevJudge(Path traceDir) {
        this(traceDir, envOr("TYPESAFE_ENDPOINT", DEFAULT_ENDPOINT), envOr("TYPESAFE_MODEL", DEFAULT_MODEL),
            env("TYPESAFE_API_KEY"), Duration.ofSeconds(Long.getLong("skill.lab.judge.timeout.seconds", 300)));
    }

    JevJudge(Path traceDir, String endpoint, String model, String apiKey, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Jev timeout must be positive");
        timeout.toNanos();
        this.traceDir = traceDir;
        this.endpoint = endpoint;
        this.model = model;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    /** @param state     what is judged: a String, or a Map/List (named JSON object, recommended);
     *  @param questions all questions at once: they are evaluated in parallel on the same state. */
    public Verdict evaluate(Object state, Map<String, ? extends Question> questions) {
        try {
            ObjectNode body = M.createObjectNode();
            body.set("state", M.valueToTree(state));
            body.put("model", model);
            ObjectNode qs = body.putObject("questions");
            questions.forEach((id, q) -> qs.set(id, json(q)));
            trace("request", body);
            System.out.println("Jev judging " + questions.size() + " questions (overall timeout " + timeout
                + "). Request: " + traceDir.resolve("jev-request.json"));
            JsonNode response = M.readTree(send(M.writeValueAsString(body)));
            trace("response", response);
            return read(response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Jev", e);
        } catch (IOException e) {
            throw new IllegalStateException("Jev judge call failed: " + e.getMessage()
                + ". Request: " + traceDir.resolve("jev-request.json"), e);
        }
    }

    private ObjectNode json(Question q) {
        ObjectNode n = M.createObjectNode();
        switch (q) {
            case Noul x -> {
                n.put("type", "noul");
                n.set("instructions", M.valueToTree(x.instructions()));
                if (x.yes() != null || x.no() != null) {
                    ObjectNode c = n.putObject("criteria");
                    if (x.yes() != null) c.set("true", M.valueToTree(x.yes()));
                    if (x.no() != null) c.set("false", M.valueToTree(x.no()));
                }
            }
            case Score x -> {
                if (x.levels().size() < 2 || x.levels().size() > 10)
                    throw new IllegalArgumentException("A Score has 2 to 10 levels; got " + x.levels().size());
                n.put("type", "score");
                n.set("instructions", M.valueToTree(x.instructions()));
                n.set("criteria", M.valueToTree(x.levels()));
            }
            case Choice x -> {
                n.put("type", "choice");
                n.set("instructions", M.valueToTree(x.instructions()));
                n.set("criteria", M.valueToTree(x.options()));
            }
        }
        return n;
    }

    /** The overall deadline includes response bodies and retry delays, not just response headers. */
    private String send(String body) throws IOException, InterruptedException {
        long start = System.nanoTime();
        long budget = timeout.toNanos();
        long wait = 1000;
        for (int attempt = 1; ; attempt++) {
            long remaining = budget - (System.nanoTime() - start);
            if (remaining <= 0) throw timedOut(null);
            HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofNanos(Math.min(remaining, TimeUnit.SECONDS.toNanos(60))))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
            System.out.printf("Jev request attempt %d/4 (%d s remaining)%n",
                attempt, TimeUnit.NANOSECONDS.toSeconds(remaining));
            var response = http.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> res;
            try {
                res = response.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                response.cancel(true);
                throw timedOut(e);
            } catch (InterruptedException e) {
                response.cancel(true);
                throw e;
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw new IllegalStateException("Jev request failed", e.getCause());
            }
            int code = res.statusCode();
            if (code == 200) return res.body();
            boolean retry = (code == 429 || code == 529) && attempt < 4;
            if (!retry) throw new IllegalStateException("HTTP " + code + " from " + endpoint + ": " + res.body());
            Duration delay = retryDelay(res.headers().firstValue("retry-after").orElse(""), Duration.ofMillis(wait));
            remaining = budget - (System.nanoTime() - start);
            if (remaining <= 0 || delay.compareTo(Duration.ofNanos(remaining)) >= 0)
                throw new IllegalStateException("Jev HTTP " + code + ": retry delay " + delay
                    + " exceeds the remaining overall timeout " + timeout
                    + " (-Dskill.lab.judge.timeout.seconds). Request: " + traceDir.resolve("jev-request.json"));
            System.out.println("Jev HTTP " + code + ": retrying after " + delay);
            TimeUnit.NANOSECONDS.sleep(delay.toNanos());
            wait *= 2;
        }
    }

    private IllegalStateException timedOut(Throwable cause) {
        return new IllegalStateException("Jev did not finish within " + timeout
            + " (-Dskill.lab.judge.timeout.seconds). Request: " + traceDir.resolve("jev-request.json"), cause);
    }

    static Duration retryDelay(String header, Duration fallback) {
        if (header.isBlank()) return fallback;
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds >= 0) return Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            try {
                Instant retryAt = ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                Duration delay = Duration.between(Instant.now(), retryAt);
                return delay.isNegative() ? Duration.ZERO : delay;
            } catch (DateTimeParseException invalidDate) {
                // Invalid server headers use the same backoff as an absent Retry-After.
            }
        }
        System.err.println("Invalid Jev Retry-After header '" + header + "'; using " + fallback);
        return fallback;
    }

    private static Verdict read(JsonNode root) {
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = root.path("answers").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode a = e.getValue();
            answers.put(e.getKey(), switch (a.path("type").asText()) {
                case "noul" -> new NoulAnswer(a.path("noul").asDouble());
                case "score" -> {
                    Map<Integer, Double> p = new LinkedHashMap<>();
                    a.path("probabilities").fields().forEachRemaining(x -> p.put(Integer.parseInt(x.getKey()), x.getValue().asDouble()));
                    Map<Integer, String> l = new LinkedHashMap<>();
                    a.path("legend").fields().forEachRemaining(x -> l.put(Integer.parseInt(x.getKey()), x.getValue().isTextual() ? x.getValue().asText() : x.getValue().toString()));
                    yield new ScoreAnswer(a.path("score").asDouble(), a.path("confidence").asDouble(), p, l);
                }
                case "choice" -> {
                    Map<String, Double> p = new LinkedHashMap<>();
                    a.path("probabilities").fields().forEachRemaining(x -> p.put(x.getKey(), x.getValue().asDouble()));
                    yield new ChoiceAnswer(a.path("choice").asText(), a.path("confidence").asDouble(), p);
                }
                default -> throw new IllegalStateException("Unknown answer type for '" + e.getKey() + "': " + a);
            });
        }
        return new Verdict(root.path("model").asText(), answers, root.path("usage").path("input_tokens").asInt());
    }

    /** Writes the JSON as sent (or received) to {@code <traceDir>/jev-<name>.json}; to the console if TYPESAFE_TRACE is set. */
    private void trace(String name, JsonNode json) {
        try {
            String pretty = M.writerWithDefaultPrettyPrinter().writeValueAsString(json);
            Files.createDirectories(traceDir);
            Path f = traceDir.resolve("jev-" + name + ".json");
            Files.writeString(f, pretty);
            if (!envOr("TYPESAFE_TRACE", "").isBlank()) System.out.println("=== Jev " + name + " (" + f + ")\n" + pretty);
        } catch (IOException e) {
            System.err.println("Trace " + name + " not written: " + e.getMessage());
        }
    }

    private static String env(String k) {
        String v = System.getenv(k);
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing environment variable: " + k);
        return v;
    }

    private static String envOr(String k, String fallback) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? fallback : v;
    }
}
