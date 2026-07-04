# 04 — Onboarding Process

Deliverable: **Onboarding process + checklist (.md / .docx)**

---

## A. GENERATOR PROMPT — produces the interview task

> Paste this into a fresh AI chat to generate a CEO task brief.

```
You are the CEO of a growing company interviewing a candidate for Chief Operating Officer.
Generate ONE realistic test-task that you would give in a live interview.

Turnover is high in the first 90 days. The candidate must design an onboarding process for a specific role that improves time-to-productivity and retention.

Output the task as a brief the candidate receives, with EXACTLY these sections:
- Context (3–5 sentences: company stage, size, what's happening right now)
- The task (1–2 sentences, specific and outcome-oriented)
- Constraints (time limit, budget, team, tools available)
- Expected deliverable (the file format and what "good" looks like)
- 3 evaluation criteria the CEO secretly cares about

Make it concrete: invent a company name, numbers, and a believable mess.
Time box for the candidate: 30–60 minutes.
Do NOT solve the task. Only write the brief.
```

---

## B. EVALUATOR PROMPT — scores a submitted solution

> After the candidate submits their file, paste this prompt and attach their file.

```
You are the CEO who set the task below. A COO candidate has submitted the attached solution.
Evaluate it the way a demanding CEO would — direct, specific, no flattery.

[Paste the original task brief here]

Score each on 0–5:
1. Problem framing — did they restate the real problem, not just the surface task?
2. MVP thinking — is the solution scoped to the smallest valuable version?
3. Plan — concrete steps, owners, sequence, time?
4. Risks — named real risks + mitigations, not generic ones?
5. Resources — realistic people/budget/tools/time ask?
6. Prototype quality — is the attached artifact usable as-is by a real team?
7. AI leverage — is it clear they used AI as a team (decomposition, drafting, verification), not a single lucky prompt?

For each dimension: give the score, one sentence of why, and one concrete fix.
End with: overall verdict (PASS / BORDERLINE / FAIL), the top 3 things to fix to pass, and a rewritten version of their weakest section as an example.
```

---

## C. DIFFICULTY / VARIATION KNOBS

Re-run the generator with one or more of these appended:

- `Role: support agent / sales rep / engineer / ops manager.`
- `Constraint: fully remote, async, across timezones.`
- `Add metric target: productive by day 14.`
- `Force inclusion of a 30/60/90 plan.`
