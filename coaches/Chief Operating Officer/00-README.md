# COO Interview — Prompt Collection

A collection of **meta-prompts**. Each one instructs an AI to *generate* a CEO-style interview question or test-task for a future COO candidate. The candidate then solves the task in a chat with AI, submits a file, and asks for feedback.

## How the workflow runs

1. **Generate the task** — Pick a prompt file below and run it in a fresh AI chat. The AI produces a realistic CEO task brief (problem, context, constraints, time limit, expected deliverable).
2. **Solve it** — The candidate uses AI as "a team of 5–10 people": decompose the task, write prompts, produce a draft, verify, refine.
3. **Submit** — The candidate exports the solution as a file (doc, table, SOP, PRD, roadmap, financial model, etc.).
4. **Get feedback** — The candidate runs the matching *evaluation prompt* (Section B in each file) to receive scored feedback and "what to fix to pass."

## Each file contains 3 blocks

- **A. Generator prompt** — produces the interview task.
- **B. Evaluator prompt** — scores a submitted solution and gives improvement feedback.
- **C. Difficulty / variation knobs** — to regenerate variants (junior/senior, different industry, harder constraints).

## Files

| # | Competency | Deliverable type |
|---|-----------|-----------------|
| 01 | PRD writing | Product requirements doc |
| 02 | Org structure design | Org chart + role specs |
| 03 | KPI / OKR design | Metrics framework |
| 04 | Onboarding process | Process + checklist |
| 05 | SOP writing | Standard operating procedure |
| 06 | Automation design | Automation spec |
| 07 | AI-agent design | Agent architecture |
| 08 | Financial model | Spreadsheet model |
| 09 | Roadmap building | Quarterly roadmap |
| 10 | Company audit | Diagnostic report |
| 11 | Crisis / firefight | Decision memo |
| 12 | General COO case | Mixed deliverable |

## Tip for the candidate

The interview is **not** testing whether you know the answer. It tests whether you can turn a vague task into a clear problem statement, an MVP, a plan, risks, resources, and a first prototype — within 30–60 minutes.
