package com.coach.coach;

import com.coach.config.AppConfig;
import com.coach.docs.DocsService;
import com.coach.model.CoachType;
import com.coach.dto.SentenceItem;
import com.coach.dto.TopicSection;
import com.coach.word.WordPair;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.lang.String.format;

/**
 * Coach persona support: selects a random interview-scenario prompt when a coach
 * chat is created, and rebuilds the system prompt (persona + whole scenario file)
 * on every turn — the scenario is re-read from disk each time, stateless like the
 * conversation history itself.
 */
@Component
public class CoachService {

    /** The synthetic first user turn of a coach chat — persisted and displayed as-is. */
    public static final String OPENING_INSTRUCTION =
            "Розпочни співбесіду — постав лише перше запитання.";

    /** The synthetic first user turn of a Claude Architect quiz chat. */
    public static final String CLAUDE_OPENING_INSTRUCTION = "Ask me the first exam question.";

    /** Opening turn directing the first question at one topic bullet: {@code %s} = bullet. */
    private static final String CLAUDE_OPENING_WITH_BULLET =
            CLAUDE_OPENING_INSTRUCTION + " Base it on this aspect of the topic:\n%s";

    private static final String CLAUDE_PERSONA = """
            You are an exam coach preparing a student for the "Claude Certified Architect –
            Foundations" certification. You quiz the student with realistic exam-style
            multiple-choice questions grounded in the topic material below, one question at
            a time, and you explain every answer.

            Question rules:
            - Exactly one correct answer and three plausible distractors — options a
              candidate with incomplete knowledge or experience might choose.
            - Frame each question in a realistic production context, like the real exam.
            - Never repeat a question already asked in this conversation, and vary which
              knowledge and skills bullets you test.

            MANDATORY format of every question reply (the client parses it automatically,
            so any deviation breaks it):
            - First the question stem: one or more lines of plain text (a short scenario
              and the question itself).
            - Then EXACTLY four lines, in this order, each option on a single line:
            A) <first option>
            B) <second option>
            C) <third option>
            D) <fourth option>
            - A question reply must contain ONLY the stem and those four option lines: no
              introduction, no numbering, no Markdown emphasis, lists or code fences, and
              no text after option D.

            When the student answers (a letter or an option text):
            - Reply in normal prose: state whether they are right, name the correct
              option, explain why it is correct, and briefly explain why each of the other
              options is wrong. Markdown is fine here.
            - NEVER include a new question or new A) B) C) D) lines in a feedback reply —
              the client shows a "Next question" button instead.
            - If the student asks a follow-up about the explanation, answer it in prose,
              again without a new question.

            When the student writes "Next question.", reply with ONLY the next question in
            the mandatory format above.""";

    private static final String COO_PERSONA = """
            You are a seasoned CEO interviewing a candidate for the Chief Operating Officer \
            role at your company. Follow the interview scenario below. Conduct the interview \
            one step at a time, stay in character as the CEO throughout, and be concrete and \
            demanding but professional.

            Conduct the entire interview in Ukrainian — questions, follow-ups, feedback, and \
            evaluation — even though the scenario below is written in English.

            Write like a real business conversation, not a screenplay: say only what the CEO \
            would actually say out loud. Never use stage directions, narrated actions, or \
            dramatic pauses (e.g. "*leans forward*", "*long pause*", "*reads the memo*"), no \
            asterisked or italicized action lines, and no other theatrical framing. Markdown \
            structure (headings, bold, lists) is fine for task briefs and feedback.""";

    /** The synthetic first user turn of a Java interview chat. */
    public static final String JAVA_OPENING_INSTRUCTION = "Ask me the first interview question.";

    /** Opening turn directing the first question at one topic bullet: {@code %s} = bullet. */
    private static final String JAVA_OPENING_WITH_BULLET =
            JAVA_OPENING_INSTRUCTION + " Base it on this aspect of the topic:\n%s";

    private static final String JAVA_PERSONA = """
            You are a technical interviewer preparing a candidate for a Java backend
            engineering interview. You quiz the candidate one question at a time,
            drawing from the topic material below, and grade every answer.

            Question rules:
            - Ask realistic interview-style questions grounded in the topic material,
              one at a time; treat the material as a pool to draw from, not a script
              to recite verbatim.
            - Never repeat a question already asked in this conversation.
            - The candidate answers in free text — there is no fixed answer format.

            Grading an answer:
            - Reply in normal prose stating whether the answer is correct and why,
              in plain technical language. Never use praise or affirmation language
              such as "Correct!" or "Great job!" — state the fact and move on.
            - After grading, you may continue the conversation naturally: answer
              follow-up questions, go deeper on related concepts, or discuss side
              topics the candidate raises. Do not pose a new question yourself.
            - Only ask the next question when the candidate writes "Next question."

            When asked "Give me a hint.": give one indirect reference or analogy for
            the information the current question is asking about. Never reveal the
            answer itself in a hint.

            When asked "Reveal the answer.": state the correct/model answer directly,
            then remain open for follow-up clarification.

            When the candidate writes "Next question.", reply with ONLY the next
            question — no other text.""";

    /** Java topic files, e.g. {@code 01 Java Core.md}: two digits, a space, then the topic. */
    private static final Pattern JAVA_TOPIC_FILE =
            Pattern.compile("^\\d{2} (.+)\\.md$", Pattern.CASE_INSENSITIVE);

    private final Path coachesDir;
    private final DocsService docsService;

    public CoachService(AppConfig config, DocsService docsService) {
        this.coachesDir = Path.of(config.coachesDir());
        this.docsService = docsService;
    }

    private static final String SPANISH_PERSONA = """
            Eres un tutor de español experimentado. Tu alumno practica traduciendo oraciones
            del inglés al español, aplicando un tema gramatical concreto y usando palabras
            españolas dadas como pista.

            Cómo funciona la práctica:
            - El alumno te indica el tema y una lista de palabras en español.
            - Tú escribes oraciones en inglés para que él las traduzca al español usando
              esas palabras y el tema indicado.
            - Cuando el alumno envía sus traducciones, revisa cada una por separado: indica
              claramente si es correcta o incorrecta, da la versión corregida y una breve
              explicación del error. Sé concreto, exigente y amable. Comunícate siempre en
              español.

            Formato OBLIGATORIO de toda lista de oraciones (el cliente la procesa
            automáticamente, así que cualquier desviación la rompe):
            - Una oración por línea.
            - Cada línea empieza con la(s) palabra(s) pista en español entre paréntesis,
              seguida de un espacio y la oración en inglés.
            - Ejemplo:
            (caber) I'm surprised that the shovel hasn't fit in the car.
            (cavar, pala) It's a shame that they haven't finished digging the hole yet.
            - Cuando envíes una lista de oraciones, la respuesta debe contener ÚNICAMENTE
              esas líneas: sin introducción, sin numeración, sin viñetas, sin Markdown y
              sin texto de despedida.
            - Usa exactamente el mismo formato cuando el alumno pida más oraciones.""";

    /**
     * Appended to the Spanish persona (+ topic clause) when {@link #systemPrompt(CoachMeta, boolean)}
     * is called with {@code spanishVerdicts} true, so a grading turn's tutor reply carries a
     * machine-readable verdict block that {@code coach-web} can parse and report to noam. Never
     * sent when noam is unavailable — the caller's boolean is the whole seam; coach-core itself
     * knows nothing about noam.
     */
    private static final String SPANISH_VERDICT_INSTRUCTION = """
            Formato OBLIGATORIO adicional cuando corrijas las traducciones del alumno (el cliente
            lo procesa automáticamente, así que cualquier desviación lo rompe):
            - Después de tu corrección en prosa de cada oración, termina el mensaje con una línea
              exactamente igual a:
            ===EVALUACIÓN===
            - A continuación, una línea por cada oración corregida, empezando con la(s) misma(s)
              palabra(s) pista en español entre paréntesis que usaste al proponer esa oración,
              seguida de un espacio y exactamente una de estas palabras: CORRECTO, PARCIAL o
              INCORRECTO.
            - Ejemplo:
            ===EVALUACIÓN===
            (caber) CORRECTO
            (cavar, pala) INCORRECTO
            - No escribas nada después de ese bloque.
            - NUNCA incluyas este bloque en una respuesta que sea una lista de oraciones nuevas
              para traducir — únicamente cuando estés corrigiendo las traducciones del alumno.""";

    /** Randomly select a scenario prompt for a new COO coach conversation. */
    public CoachMeta startCoach(CoachType type) {
        Path dir = coachDir(type);
        List<Path> candidates;
        try (Stream<Path> files = Files.list(dir)) {
            candidates = files.filter(CoachService::eligible).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (candidates.isEmpty())
            throw new IllegalStateException("No scenario prompts found for coach " + type.value());
        Path chosen = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        return new CoachMeta(type, chosen.getFileName().toString(), null);
    }

    /**
     * Return the meta for a new Spanish conversation. When {@code topic} is blank/null,
     * the chat is topic-less (字 word-seeding into a 語 session) and no validation is done.
     */
    public CoachMeta startSpanish(String topic) {
        if (topic == null || topic.isBlank())
            return new CoachMeta(CoachType.SPANISH, null, null);
        if (spanishTopics().stream().noneMatch(s -> s.topics().contains(topic)))
            throw new InvalidRequestException("Unknown Spanish topic: " + topic);
        return new CoachMeta(CoachType.SPANISH, null, topic);
    }

    /** Validate topic membership and return the meta for a new Claude Architect conversation. */
    public CoachMeta startClaudeArchitect(String topic) {
        if (!claudeTopics().contains(topic))
            throw new InvalidRequestException("Unknown Claude Architect topic: " + topic);
        return new CoachMeta(CoachType.CLAUDE_ARCHITECT, topic + ".md", null);
    }

    /**
     * Validate topic membership and return the meta for a new Java interview conversation.
     * Unlike Claude Architect, the topic id (e.g. {@code "Java Core"}) has had its filename's
     * {@code NN } sort prefix stripped, so the matching file must be re-located by name.
     */
    public CoachMeta startJava(String topic) {
        if (!javaTopics().contains(topic))
            throw new InvalidRequestException("Unknown Java topic: " + topic);
        Path dir = coachDir(CoachType.JAVA);
        try (Stream<Path> files = Files.list(dir)) {
            Path match = files
                    .filter(CoachService::eligible)
                    .filter(p -> stripJavaTopicPrefix(p.getFileName().toString()).equals(topic))
                    .findFirst()
                    .orElseThrow();
            return new CoachMeta(CoachType.JAVA, match.getFileName().toString(), null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Opening user turn for a new Claude Architect quiz: the plain instruction plus a
     * randomly chosen {@code - } bullet of the topic file, so first questions spread
     * over the whole blueprint instead of converging on the model's modal pick.
     * Topics without bullets fall back to the plain instruction.
     */
    public String claudeOpeningInstruction(CoachMeta meta) {
        var bullets = readTopicLines(scenarioPath(meta)).stream()
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).strip())
                .toList();
        if (bullets.isEmpty()) return CLAUDE_OPENING_INSTRUCTION;
        return format(CLAUDE_OPENING_WITH_BULLET,
                bullets.get(ThreadLocalRandom.current().nextInt(bullets.size())));
    }

    /**
     * Opening user turn for a new Java interview: the plain instruction plus a randomly
     * chosen {@code - } bullet of the topic file, mirroring {@link #claudeOpeningInstruction}.
     * Topics without bullets fall back to the plain instruction.
     */
    public String javaOpeningInstruction(CoachMeta meta) {
        var bullets = readTopicLines(scenarioPath(meta)).stream()
                .filter(line -> line.startsWith("- "))
                .map(line -> line.substring(2).strip())
                .toList();
        if (bullets.isEmpty()) return JAVA_OPENING_INSTRUCTION;
        return format(JAVA_OPENING_WITH_BULLET,
                bullets.get(ThreadLocalRandom.current().nextInt(bullets.size())));
    }

    /**
     * Opening practice prompt when words are typed in. {@code %s} = optional topic clause
     * (e.g. {@code " «Ser y estar»"} or {@code ""} for topic-less), then the word list.
     */
    private static final String OPENING_WITH_WORDS = """
            Quiero practicar%s usando las siguientes palabras:
            %s

            Escríbeme una oración en inglés por cada palabra o expresión española de la lista \
            —exactamente una oración por cada una— para que yo las traduzca al español. \
            Si una línea incluye una traducción o comentario después de un guión, ignóralo y \
            usa solo la palabra o expresión española. Baraja el orden de las oraciones. \
            Al principio de cada oración, escribe entre paréntesis la(s) palabra(s) española(s) \
            que debo usar como pista.""";

    /**
     * System prompt for the 字 translate step: each input Spanish word/expression on one line
     * in {@code (español) english} format so {@link SentenceParser} can parse it.
     */
    public static final String WORD_TRANSLATE_SYSTEM = """
            Eres un asistente de traducción del español al inglés. Para cada palabra o \
            expresión española de la lista, escribe exactamente una línea con este formato:
            (palabra_española) traducción_al_inglés

            Reglas OBLIGATORIAS:
            - Exactamente una línea por entrada, en el mismo orden que la lista.
            - Escribe la palabra o expresión española original entre paréntesis, \
              seguida de un espacio y la traducción al inglés.
            - Si una entrada incluye texto después de un guión, ignóralo y usa solo \
              la palabra o expresión española.
            - Sin introducción, sin numeración, sin viñetas, sin Markdown y sin texto \
              de despedida.""";

    /** Opening practice prompt when the words come from an attachment: {@code %s} = topic. */
    private static final String OPENING_WITH_ATTACHMENT = """
            Quiero practicar «%s» usando las palabras del archivo adjunto.

            Escríbeme una oración en inglés por cada palabra o expresión para que yo las traduzca \
            al español. Baraja el orden de las oraciones. \
            Al principio de cada oración, escribe entre paréntesis la(s) palabra(s) española(s) \
            que debo usar como pista.""";

    /** Compose the opening practice prompt for a Spanish first turn. */
    public String spanishOpeningPrompt(String topic, String words) {
        if (words == null) return format(OPENING_WITH_ATTACHMENT, topic);
        String topicClause = topic == null ? "" : " «" + topic + "»";
        return format(OPENING_WITH_WORDS, topicClause, words);
    }

    /** System prompt for any turn: Spanish persona (+ topic if set), or COO/Claude persona + scenario file. */
    public String systemPrompt(CoachMeta meta) {
        return systemPrompt(meta, false);
    }

    /**
     * As {@link #systemPrompt(CoachMeta)}, plus — when {@code spanishVerdicts} is true and the
     * coach is {@link CoachType#SPANISH} — the verdict-block instruction appended after the
     * topic clause. {@code coach-web} gates {@code spanishVerdicts} on
     * {@code NoamGateway.isAvailable()}; every other caller (including {@code coach-mcp} and
     * every non-Spanish coach) uses the one-argument overload and is unaffected.
     */
    public String systemPrompt(CoachMeta meta, boolean spanishVerdicts) {
        if (meta.coachType() == CoachType.SPANISH) {
            String base = meta.topic() == null
                    ? SPANISH_PERSONA
                    : SPANISH_PERSONA + "\n\nTema de práctica: " + meta.topic();
            return spanishVerdicts ? base + "\n\n" + SPANISH_VERDICT_INSTRUCTION : base;
        }
        Path scenario = scenarioPath(meta);
        if (!Files.isRegularFile(scenario))
            throw new IllegalStateException("The coach scenario for this conversation is no longer available.");
        var persona = switch (meta.coachType()) {
            case CHIEF_OPERATING_OFFICER -> COO_PERSONA;
            case CLAUDE_ARCHITECT -> CLAUDE_PERSONA;
            case JAVA -> JAVA_PERSONA;
            case SPANISH, NONE -> throw new IllegalStateException(
                    "No file-based persona for coach " + meta.coachType());
        };
        try {
            String scenarioText = Files.readString(scenario);
            if (meta.coachType() == CoachType.CLAUDE_ARCHITECT)
                scenarioText = docsService.withOfficialDocs(scenarioText);
            return persona + "\n\n" + scenarioText;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The meta's scenario file; the basename guard keeps it inside the coach folder. */
    private Path scenarioPath(CoachMeta meta) {
        String safe = Path.of(meta.promptFile()).getFileName().toString();
        return coachDir(meta.coachType()).resolve(safe);
    }

    /**
     * Spanish topics as ordered level sections, one per {@code temas-<level>.txt} under
     * {@code coaches/Spanish/} (e.g. {@code temas-b1.txt} → level {@code B1}), sorted by
     * filename so levels ascend. Each file's lines are trimmed and blanks skipped, in
     * file (workbook) order; empty files are dropped. No section at all is an error.
     */
    public List<TopicSection> spanishTopics() {
        Path dir = coachesDir.resolve("Spanish");
        try (Stream<Path> files = Files.list(dir)) {
            var sections = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("(?i)temas-.+\\.txt"))
                    .sorted()
                    .map(p -> new TopicSection(levelOf(p), readTopicLines(p)))
                    .filter(s -> !s.topics().isEmpty())
                    .toList();
            if (sections.isEmpty())
                throw new IllegalStateException("No Spanish topics found: " + dir);
            return sections;
        } catch (IOException e) {
            throw new IllegalStateException("No Spanish topics found: " + dir, e);
        }
    }

    /** Level label from a {@code temas-<level>.txt} filename, e.g. {@code temas-b1.txt} → {@code B1}. */
    private static String levelOf(Path path) {
        return path.getFileName().toString()
                .replaceFirst("(?i)^temas-", "")
                .replaceFirst("(?i)\\.txt$", "")
                .toUpperCase();
    }

    /** Non-blank, trimmed lines of a topic file, in file order. */
    private static List<String> readTopicLines(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Scenario candidates: markdown files that are not documentation or junk. */
    private static boolean eligible(Path file) {
        String name = file.getFileName().toString();
        String lower = name.toLowerCase();
        return Files.isRegularFile(file)
                && lower.endsWith(".md")
                && !name.startsWith(".")
                && !lower.contains("readme");
    }

    /** Topics from {@code coaches/Claude/*.md} files (README and dotfiles excluded), sorted. */
    public List<String> claudeTopics() {
        Path dir = coachDir(CoachType.CLAUDE_ARCHITECT);
        try (Stream<Path> files = Files.list(dir)) {
            var topics = files
                    .filter(CoachService::eligible)
                    .map(p -> p.getFileName().toString().replaceFirst("(?i)\\.md$", ""))
                    .sorted()
                    .toList();
            if (topics.isEmpty())
                throw new IllegalStateException("No Claude Architect topics found: " + dir);
            return topics;
        } catch (IOException e) {
            throw new IllegalStateException("No Claude Architect topics found: " + dir, e);
        }
    }

    private Path coachDir(CoachType type) {
        return switch (type) {
            case CHIEF_OPERATING_OFFICER -> coachesDir.resolve("Chief Operating Officer");
            case SPANISH -> coachesDir.resolve("Spanish");
            case CLAUDE_ARCHITECT -> coachesDir.resolve("Claude");
            case JAVA -> coachesDir.resolve("Java");
            case NONE -> throw new IllegalArgumentException("No coach directory for NONE");
        };
    }

    /**
     * Topics from {@code coaches/Java/*.md} files (README and dotfiles excluded), sorted by
     * filename (the {@code NN } prefix controls that order) and returned with the prefix
     * stripped — the topic id sent to/from the API and shown on a button is just the name.
     */
    public List<String> javaTopics() {
        Path dir = coachDir(CoachType.JAVA);
        try (Stream<Path> files = Files.list(dir)) {
            var topics = files
                    .filter(CoachService::eligible)
                    .sorted()
                    .map(p -> stripJavaTopicPrefix(p.getFileName().toString()))
                    .toList();
            if (topics.isEmpty())
                throw new IllegalStateException("No Java topics found: " + dir);
            return topics;
        } catch (IOException e) {
            throw new IllegalStateException("No Java topics found: " + dir, e);
        }
    }

    /** Strip a {@code NN } sort prefix from a Java topic filename; fails loudly if absent. */
    private static String stripJavaTopicPrefix(String filename) {
        var m = JAVA_TOPIC_FILE.matcher(filename);
        if (!m.matches())
            throw new IllegalStateException(
                    "Java topic file name must start with two digits and a space: " + filename);
        return m.group(1);
    }

    // ── 字 word-quiz helpers ─────────────────────────────────────────────────── //

    /**
     * Split {@code raw} on commas and newlines, strip anything after a dash
     * ({@code -}, {@code –}, {@code —}), trim, and drop blanks.
     */
    public List<String> parseWordList(String raw) {
        if (raw == null) return List.of();
        // Strip a per-line "— translation" before splitting on commas, so a comma inside
        // the (ignored) translation can't leak a bogus extra token.
        return Arrays.stream(raw.split("\\R+"))
                .map(line -> line.replaceFirst("\\s+-\\s+.*|[–—].*", ""))
                .flatMap(line -> Arrays.stream(line.split(",")))
                .map(String::trim)
                .map(Text::stripEdges)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * Reveal the first {@code ceil(len/4)} characters of each word in {@code word},
     * replacing the rest with {@code ·} (U+00B7) and preserving spaces — so longer
     * words expose several leading letters and every word shows at least its first.
     * <p>Examples: {@code cráneo} → {@code cr····}, {@code caber en} → {@code ca··· e·},
     * {@code ser compatible} → {@code s·· com·······}.
     */
    public String maskHint(String word) {
        var sb = new StringBuilder();
        // Split into space-separated words (-1 keeps empty runs, so multiple/edge
        // spaces round-trip), then reveal ceil(len/4) leading chars of each.
        String[] words = word.split(" ", -1);
        for (int p = 0; p < words.length; p++) {
            if (p > 0) sb.append(' ');
            String w = words[p];
            int reveal = (w.length() + 3) / 4;       // ceil(len/4), ≥1 for a non-empty word
            for (int k = 0; k < w.length(); k++)
                sb.append(k < reveal ? w.charAt(k) : '·');
        }
        return sb.toString();
    }

    /**
     * Match the LLM-translated lines (via {@link SentenceParser}) back to the original
     * input tokens and build {@link WordPair}s.
     *
     * <p>Matching strategy: normalise the echoed español in each parsed line with
     * {@link Text#normalizeKey} and look it up in the original token list; fall back to
     * positional order when no normalised match exists.
     *
     * @throws IllegalStateException if the LLM output cannot be parsed
     */
    public List<WordPair> pairTranslations(List<String> tokens, String llmAnswer) {
        List<SentenceItem> items = SentenceParser.parse(llmAnswer);
        if (items.isEmpty())
            throw new IllegalStateException("Could not parse translations, try again.");

        // Normalized original → original token (first occurrence wins)
        Map<String, String> normToOrig = new LinkedHashMap<>();
        for (String token : tokens)
            normToOrig.put(Text.normalizeKey(token), token);

        int count = Math.min(tokens.size(), items.size());
        List<WordPair> pairs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            SentenceItem item = items.get(i);
            String norm = Text.normalizeKey(item.hint());
            String original = normToOrig.getOrDefault(norm, tokens.get(i));
            pairs.add(new WordPair(item.sentence(), original, null));
        }
        return pairs;
    }
}
