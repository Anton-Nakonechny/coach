package com.coach.coach;

import com.coach.config.AppConfig;
import com.coach.model.CoachType;
import com.coach.web.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
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

    private final Path coachesDir;

    public CoachService(AppConfig config) {
        this.coachesDir = Path.of(config.coachesDir());
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

    /** Validate topic membership and return the meta for a new Spanish conversation. */
    public CoachMeta startSpanish(String topic) {
        if (!spanishTopics().contains(topic))
            throw new InvalidRequestException("Unknown Spanish topic: " + topic);
        return new CoachMeta(CoachType.SPANISH, null, topic);
    }

    /** Validate topic membership and return the meta for a new Claude Architect conversation. */
    public CoachMeta startClaudeArchitect(String topic) {
        if (!claudeTopics().contains(topic))
            throw new InvalidRequestException("Unknown Claude Architect topic: " + topic);
        return new CoachMeta(CoachType.CLAUDE_ARCHITECT, topic + ".md", null);
    }

    /** Opening practice prompt when the words are typed in: {@code %s} = topic, then the word list. */
    private static final String OPENING_WITH_WORDS = """
            Quiero practicar «%s» usando las siguientes palabras:
            %s

            Escríbeme una oración en inglés por cada palabra o expresión española de la lista \
            —exactamente una oración por cada una— para que yo las traduzca al español. \
            Si una línea incluye una traducción o comentario después de un guión, ignóralo y \
            usa solo la palabra o expresión española. Baraja el orden de las oraciones. \
            Al principio de cada oración, escribe entre paréntesis la(s) palabra(s) española(s) \
            que debo usar como pista.""";

    /** Opening practice prompt when the words come from an attachment: {@code %s} = topic. */
    private static final String OPENING_WITH_ATTACHMENT = """
            Quiero practicar «%s» usando las palabras del archivo adjunto.

            Escríbeme una oración en inglés por cada palabra o expresión para que yo las traduzca \
            al español. Baraja el orden de las oraciones. \
            Al principio de cada oración, escribe entre paréntesis la(s) palabra(s) española(s) \
            que debo usar como pista.""";

    /** Compose the opening practice prompt for a Spanish first turn. */
    public String spanishOpeningPrompt(String topic, String words) {
        return words == null
                ? format(OPENING_WITH_ATTACHMENT, topic)
                : format(OPENING_WITH_WORDS, topic, words);
    }

    /** System prompt for any turn: Spanish persona + topic, or COO/Claude persona + scenario file. */
    public String systemPrompt(CoachMeta meta) {
        if (meta.coachType() == CoachType.SPANISH)
            return SPANISH_PERSONA + "\n\nTema de práctica: " + meta.topic();
        // Basename guard: the stored filename must never escape the coach folder.
        String safe = Path.of(meta.promptFile()).getFileName().toString();
        Path scenario = coachDir(meta.coachType()).resolve(safe);
        if (!Files.isRegularFile(scenario))
            throw new IllegalStateException("The coach scenario for this conversation is no longer available.");
        var persona = switch (meta.coachType()) {
            case CHIEF_OPERATING_OFFICER -> COO_PERSONA;
            case CLAUDE_ARCHITECT -> CLAUDE_PERSONA;
            case SPANISH, NONE -> throw new IllegalStateException(
                    "No file-based persona for coach " + meta.coachType());
        };
        try {
            return persona + "\n\n" + Files.readString(scenario);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Topics from {@code coaches/Spanish/temas.txt}, trimmed and blanks skipped, in file order. */
    public List<String> spanishTopics() {
        Path path = coachesDir.resolve("Spanish").resolve("temas.txt");
        try {
            var topics = Files.readAllLines(path).stream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
            if (topics.isEmpty())
                throw new IllegalStateException("No Spanish topics found: " + path);
            return topics;
        } catch (IOException e) {
            throw new IllegalStateException("No Spanish topics found: " + path, e);
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
            case NONE -> throw new IllegalArgumentException("No coach directory for NONE");
        };
    }
}
