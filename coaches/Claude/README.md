# Claude Certified Architect – Foundations: topic list

Source: `Claude_Certified_Architect_–_Foundations_Certification_Exam_Guide.pdf` (this folder).

Exam facts: all questions are multiple choice — one correct response, three
distractors. Scored 100–1,000 (scaled), minimum passing score 720. Questions are
framed by 6 realistic production scenarios: Customer Support Resolution Agent,
Code Generation with Claude Code, Multi-Agent Research System, Developer
Productivity with Claude, Claude Code for Continuous Integration, and Structured
Data Extraction.

Each task statement below has a matching prompt file in this folder (the file
stem is the topic shown in the UI), plus `Full exam simulation.md` which mixes
all domains by their exam weightings. This README and the PDF are excluded from
the topic listing.

## Domain 1: Agentic Architecture & Orchestration (27%)

- 1.1 Agentic loops — Design and implement agentic loops for autonomous task execution
- 1.2 Multi-agent orchestration — Orchestrate multi-agent systems with coordinator-subagent patterns
- 1.3 Subagent context and spawning — Configure subagent invocation, context passing, and spawning
- 1.4 Workflow enforcement and handoffs — Implement multi-step workflows with enforcement and handoff patterns
- 1.5 Agent SDK hooks — Apply Agent SDK hooks for tool call interception and data normalization
- 1.6 Task decomposition — Design task decomposition strategies for complex workflows
- 1.7 Session state and forking — Manage session state, resumption, and forking

## Domain 2: Tool Design & MCP Integration (18%)

- 2.1 Tool interface design — Design effective tool interfaces with clear descriptions and boundaries
- 2.2 Structured tool errors — Implement structured error responses for MCP tools
- 2.3 Tool distribution and tool_choice — Distribute tools appropriately across agents and configure tool choice
- 2.4 MCP server integration — Integrate MCP servers into Claude Code and agent workflows
- 2.5 Built-in tools — Select and apply built-in tools (Read, Write, Edit, Bash, Grep, Glob) effectively

## Domain 3: Claude Code Configuration & Workflows (20%)

- 3.1 CLAUDE.md configuration — Configure CLAUDE.md files with appropriate hierarchy, scoping, and modular organization
- 3.2 Slash commands and skills — Create and configure custom slash commands and skills
- 3.3 Path-specific rules — Apply path-specific rules for conditional convention loading
- 3.4 Plan mode vs direct execution — Determine when to use plan mode vs direct execution
- 3.5 Iterative refinement — Apply iterative refinement techniques for progressive improvement
- 3.6 CI-CD integration — Integrate Claude Code into CI/CD pipelines

## Domain 4: Prompt Engineering & Structured Output (20%)

- 4.1 Explicit criteria prompts — Design prompts with explicit criteria to improve precision and reduce false positives
- 4.2 Few-shot prompting — Apply few-shot prompting to improve output consistency and quality
- 4.3 Structured output and schemas — Enforce structured output using tool use and JSON schemas
- 4.4 Validation and retry loops — Implement validation, retry, and feedback loops for extraction quality
- 4.5 Batch processing — Design efficient batch processing strategies
- 4.6 Multi-pass review — Design multi-instance and multi-pass review architectures

## Domain 5: Context Management & Reliability (15%)

- 5.1 Conversation context — Manage conversation context to preserve critical information across long interactions
- 5.2 Escalation patterns — Design effective escalation and ambiguity resolution patterns
- 5.3 Error propagation — Implement error propagation strategies across multi-agent systems
- 5.4 Codebase exploration context — Manage context effectively in large codebase exploration
- 5.5 Human review and confidence — Design human review workflows and confidence calibration
- 5.6 Provenance and uncertainty — Preserve information provenance and handle uncertainty in multi-source synthesis

## Out of scope (never ask about these)

Fine-tuning or training custom models; API authentication, billing, or account
management; deploying/hosting MCP server infrastructure; Claude's internal
architecture or safety training; embeddings or vector databases; computer use;
vision; streaming implementation; rate limits, quotas, or pricing math; OAuth or
key rotation; cloud-provider specifics; benchmarking; prompt-caching internals.
