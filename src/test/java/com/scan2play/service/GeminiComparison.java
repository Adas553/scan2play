package com.scan2play.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.HttpOptions;
import com.scan2play.model.CommentStyle;
import com.scan2play.model.DjResponse;
import com.scan2play.util.SongNames;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The Gemini comparison (2026-10-07: chose gemini-3.5-flash, thinking low): the same guests' requests through the real prompt of
 * {@code src/main/resources/prompts} — or a rewrite of it put in {@code src/test/resources/gemini-comparison/proposed} (a variant
 * with the prompt "proposed") — on several models and thinking settings; the answers, the time of each call and its price side by
 * side. Not a test: run {@link #main} from IntelliJ (the green arrow) with
 * {@code GOOGLE_AI_API_KEY} in the run configuration's environment. It writes {@code target/gemini-comparison.md}.
 * <p>
 * Each call costs a fraction of a cent (the whole run, ~360 calls, well under $2). The key is read from the environment and never
 * written anywhere.
 */
public final class GeminiComparison {

    /** How many times each request goes to each variant: the AI does not answer the same way every time. */
    private static final int REPEATS = 2;
    /** The production call's timeout (GeminiConfig): a slower answer would have gone to the DJ unchecked. */
    private static final long PRODUCTION_TIMEOUT_MS = 15_000;
    private static final Locale PL = Locale.forLanguageTag("pl");

    /**
     * A guest's request. {@code expected}: words the right song's name contains (null = for a person to judge); {@code accept}: the
     * decision it should get (null = either).
     */
    record Case(String text, String vibeNote, CommentStyle style, Locale locale, String expected, Boolean accept) {
        Case(String text, String expected) {
            this(text, null, CommentStyle.CLASSIC, PL, expected, null);
        }
    }

    /** A model, how it thinks ("budget:1024" or a level), and which prompt: "current" (src/main) or "proposed". */
    record Variant(String model, String thinking, String prompt) {
        String label() {
            return model + " · " + thinking + " · " + prompt;
        }
    }

    record Result(Variant variant, Case aCase, DjResponse answer, long millis, int inTokens, int outTokens, int thoughtTokens,
                  String error) {
        double dollars() {
            double[] price = price(variant.model());
            return inTokens * price[0] / 1e6 + (outTokens + thoughtTokens) * price[1] / 1e6;
        }

        /** "ok", "WRONG" or "?" (nothing to check against). */
        String verdict() {
            if (answer == null) {
                return "ERROR";
            }
            boolean song = aCase.expected() == null
                    || SongNames.comparable(answer.songName()).contains(SongNames.comparable(aCase.expected()))
                    || ("mood".equals(aCase.expected()) && answer.isMood());
            boolean decision = aCase.accept() == null || aCase.accept() == "accepted".equals(answer.decision());
            if (!song || !decision) {
                return "WRONG";
            }
            return aCase.expected() == null && aCase.accept() == null ? "?" : "ok";
        }
    }

    static final List<Case> CASES = List.of(
            // Varius Manx - "Orła cień" (1994): 2.5 Flash saved Dżem by its mood (2026-10-07), and other songs on each try
            new Case("orła cień", "Varius Manx"),
            new Case("widziałem orła cień", "Varius Manx"),
            // the owner's tries, 2026-10-07, the note "Salsa", sarcastic comments
            new Case("somos hermanos", "Salsa", CommentStyle.SARCASTIC, PL, null, null),
            new Case("Crooked Stilo - Somos Hermanos ft. C-Kan", "Salsa", CommentStyle.SARCASTIC, PL, "Somos Hermanos", null),
            new Case("El Zorro - Somos Hermanos", "Salsa", CommentStyle.SARCASTIC, PL, "Somos Hermanos", null),
            new Case("ta o Baśce", "Baśka"),
            new Case("jesteś szalona mówię ci", "Jesteś szalona"),
            new Case("przez twe oczy zielone", "Przez twe oczy zielone"),
            new Case("nie płacz ewka", "Nie płacz Ewka"),
            new Case("kolorowe jarmarki", "Kolorowe jarmarki"),
            new Case("sen o wiktorii", "Dżem - Sen o"),
            new Case("ta z shreka", null),
            new Case("bitelsi yesterday", "Yesterday"),
            new Case("zenek przekorny los", "wesele 40+", CommentStyle.CLASSIC, PL, "Przekorny los", true),
            new Case("ona tańczy dla mnie", "Ona tańczy dla mnie"),
            new Case("jolka jolka", "Jolka"),
            new Case("takie tango", "Takie tango"),
            new Case("autobiografia", "Autobiografia"),
            new Case("golec uorkiestra", "Golec"),
            new Case("coś wolnego dla par", null, CommentStyle.CLASSIC, PL, "mood", false),
            new Case("sanah szampan", "Szampan"),
            new Case("Zenon Martyniuk & Edward Hulewicz – Za zdrowie Pań", "Za zdrowie Pań"),
            new Case("bajlando", "Latino", CommentStyle.CLASSIC, PL, "Bailando", true),
            new Case("nirvana smells like teen spirit", "Latino", CommentStyle.FUNNY, PL, "Smells Like Teen Spirit", false),
            new Case("dancing queen", null, CommentStyle.SARCASTIC, PL, "Dancing Queen", true),
            new Case("lambada", "Lambada"),
            new Case("macarena", "Salsa", CommentStyle.CLASSIC, PL, "Macarena", null),
            // a new song the model cannot know (made up for the test): not knowing it is no reason to reject it
            new Case("Sobel - Fiołkowy Blask", null, CommentStyle.CLASSIC, PL, "Fiołkowy Blask", true),
            new Case("despacito", "Despacito"),
            new Case("the one from titanic", null, CommentStyle.CLASSIC, Locale.ENGLISH, "My Heart Will Go On", true));

    static final List<Variant> VARIANTS = List.of(
            new Variant("gemini-3.5-flash", "low", "current"),              // production since 2026-10-07
            new Variant("gemini-3.5-flash", "medium", "current"),
            new Variant("gemini-2.5-flash", "budget:1024", "current"),
            new Variant("gemini-3.5-flash-lite", "low", "current"));

    /** Paid tier, $ per 1M tokens: input, output (thinking is output). ai.google.dev/gemini-api/docs/pricing, 2026-10-07. */
    static double[] price(String model) {
        return switch (model) {
            case "gemini-2.5-flash", "gemini-3.5-flash-lite" -> new double[]{0.30, 2.50};
            case "gemini-3.5-flash" -> new double[]{1.50, 9.00};
            default -> new double[]{0, 0};
        };
    }

    public static void main(String[] args) throws Exception {
        String key = System.getenv("GOOGLE_AI_API_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Set GOOGLE_AI_API_KEY in the run configuration's environment");
        }
        Client client = Client.builder().apiKey(key).httpOptions(HttpOptions.builder().timeout(60_000).build()).build();
        ObjectMapper mapper = new ObjectMapper();

        List<Future<Result>> pending = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        for (Variant variant : VARIANTS) {
            SongEvaluationService prompts = prompts(variant.prompt());
            GenerateContentConfig config = config(variant);
            for (Case aCase : CASES) {
                String prompt = prompts.buildPrompt(SongEvaluationService.forPrompt(aCase.text()), "ANY", "None", aCase.vibeNote(),
                        aCase.style(), aCase.locale());
                for (int i = 0; i < REPEATS; i++) {
                    pending.add(pool.submit(() -> ask(client, mapper, variant, aCase, prompt, config)));
                }
            }
        }
        List<Result> results = new ArrayList<>();
        for (Future<Result> future : pending) {
            results.add(future.get());
            if (results.size() % 20 == 0) {
                System.out.println(results.size() + " / " + pending.size());
            }
        }
        pool.shutdown();

        Path out = Path.of("target", "gemini-comparison.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report(results), StandardCharsets.UTF_8);
        System.out.println(summary(results));
        System.out.println("Written: " + out.toAbsolutePath());
    }

    private static Result ask(Client client, ObjectMapper mapper, Variant variant, Case aCase, String prompt, GenerateContentConfig config) {
        long start = System.nanoTime();
        try {
            GenerateContentResponse response = client.models.generateContent(variant.model(), prompt, config);
            long millis = (System.nanoTime() - start) / 1_000_000;
            GenerateContentResponseUsageMetadata usage = response.usageMetadata().orElse(null);
            int in = usage == null ? 0 : usage.promptTokenCount().orElse(0);
            int out = usage == null ? 0 : usage.candidatesTokenCount().orElse(0);
            int thoughts = usage == null ? 0 : usage.thoughtsTokenCount().orElse(0);
            DjResponse answer = SongEvaluationService.withKnownDecision(mapper.readValue(response.text(), DjResponse.class));
            return new Result(variant, aCase, answer, millis, in, out, thoughts, null);
        } catch (Exception e) {
            return new Result(variant, aCase, null, (System.nanoTime() - start) / 1_000_000, 0, 0, 0, e.toString());
        }
    }

    /** The answer's config as production builds it, for the variant's model and thinking. */
    static GenerateContentConfig config(Variant variant) {
        String thinking = variant.thinking();
        int budget = thinking.startsWith("budget:") ? Integer.parseInt(thinking.substring("budget:".length())) : 0;
        return GenerateContentConfig.builder()
                .responseMimeType("application/json")
                .responseSchema(SongEvaluationService.ANSWER_SCHEMA)
                .thinkingConfig(SongEvaluationService.thinkingConfig(variant.model(), budget, thinking.startsWith("budget:") ? "low" : thinking))
                .build();
    }

    /** The service, only to build the prompts: production's from src/main, or a proposed rewrite in place of them. */
    static SongEvaluationService prompts(String which) {
        DefaultResourceLoader real = new DefaultResourceLoader();
        ResourceLoader loader = "proposed".equals(which)
                ? new DefaultResourceLoader() {
                    @Override
                    public Resource getResource(String location) {
                        return real.getResource(location.replace("classpath:prompts/", "classpath:gemini-comparison/proposed/"));
                    }
                }
                : real;
        SongEvaluationService service = new SongEvaluationService(null, new ObjectMapper(), null, null, new StaticMessageSource(),
                loader, null, null, null);
        ReflectionTestUtils.setField(service, "modelName", "gemini-2.5-flash");
        service.init();
        return service;
    }

    static String summary(List<Result> results) {
        StringBuilder text = new StringBuilder("| Variant | ok | WRONG | ? | errors | > 15 s | avg time | max time | $ per 1000 requests |\n")
                .append("|---|---|---|---|---|---|---|---|---|\n");
        for (Variant variant : VARIANTS) {
            List<Result> mine = results.stream().filter(r -> r.variant().equals(variant)).toList();
            long ok = mine.stream().filter(r -> r.verdict().equals("ok")).count();
            long wrong = mine.stream().filter(r -> r.verdict().equals("WRONG")).count();
            long unknown = mine.stream().filter(r -> r.verdict().equals("?")).count();
            long errors = mine.stream().filter(r -> r.answer() == null).count();
            long slow = mine.stream().filter(r -> r.millis() > PRODUCTION_TIMEOUT_MS).count();
            double avg = mine.stream().mapToLong(Result::millis).average().orElse(0) / 1000;
            double max = mine.stream().mapToLong(Result::millis).max().orElse(0) / 1000.0;
            double perThousand = mine.stream().mapToDouble(Result::dollars).average().orElse(0) * 1000;
            text.append(String.format(Locale.ROOT, "| %s | %d | %d | %d | %d | %d | %.1f s | %.1f s | $%.2f |%n",
                    variant.label(), ok, wrong, unknown, errors, slow, avg, max, perThousand));
        }
        return text.toString();
    }

    static String report(List<Result> results) {
        StringBuilder text = new StringBuilder("# Gemini comparison\n\n").append(summary(results)).append('\n');
        for (Case aCase : CASES) {
            text.append("## ").append(aCase.text().replace("|", "/"));
            if (aCase.vibeNote() != null) {
                text.append(" — note: ").append(aCase.vibeNote());
            }
            text.append(" — ").append(aCase.style()).append(aCase.expected() != null ? " — expected: " + aCase.expected() : "")
                    .append(aCase.accept() != null ? (aCase.accept() ? " (accepted)" : " (rejected)") : "").append("\n\n")
                    .append("| Variant | verdict | song | kind | decision | comment | time | thinking tokens |\n|---|---|---|---|---|---|---|---|\n");
            results.stream().filter(r -> r.aCase() == aCase).forEach(r -> text.append(String.format(Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %s | %.1f s | %d |%n", r.variant().label(), r.verdict(),
                    r.answer() == null ? "" : cell(r.answer().songName()), r.answer() == null ? "" : r.answer().requestKind(),
                    r.answer() == null ? "" : r.answer().decision(), r.answer() == null ? cell(r.error()) : cell(r.answer().comment()),
                    r.millis() / 1000.0, r.thoughtTokens())));
            text.append('\n');
        }
        return text.toString();
    }

    private static String cell(String value) {
        return value == null ? "" : value.replace("|", "/").replace("\n", " ");
    }

    private GeminiComparison() {
    }
}
