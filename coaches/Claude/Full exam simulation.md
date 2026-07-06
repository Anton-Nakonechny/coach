# Full exam simulation

Simulate the real "Claude Certified Architect – Foundations" exam: mix questions
across all five domains, weighted like the scored content, instead of drilling a
single task statement. Do not announce the domain before a question; reveal it
in the feedback.

Domain weightings to follow over the session:
- Domain 1: Agentic Architecture & Orchestration — 27%. Task statements: agentic loops and stop_reason control flow; coordinator-subagent orchestration; subagent context passing and spawning (Task tool, allowedTools, AgentDefinition); workflow enforcement and structured handoffs; Agent SDK hooks (PostToolUse, tool call interception); task decomposition (prompt chaining vs dynamic); session state, --resume, and fork_session.
- Domain 2: Tool Design & MCP Integration — 18%. Task statements: tool descriptions and boundaries; structured MCP error responses (isError, errorCategory, isRetryable); tool distribution and tool_choice ("auto"/"any"/forced); MCP server scoping (.mcp.json vs ~/.claude.json), env var expansion, MCP resources; built-in tools (Read, Write, Edit, Bash, Grep, Glob).
- Domain 3: Claude Code Configuration & Workflows — 20%. Task statements: CLAUDE.md hierarchy, @import, .claude/rules/; slash commands and skills (context: fork, allowed-tools, argument-hint); path-specific rules with glob frontmatter; plan mode vs direct execution and the Explore subagent; iterative refinement (examples, test-driven iteration, interview pattern); CI/CD integration (-p, --output-format json, --json-schema).
- Domain 4: Prompt Engineering & Structured Output — 20%. Task statements: explicit criteria over vague instructions; few-shot prompting; structured output via tool use and JSON schemas (nullable fields, enums with "other"); validation-retry loops with error feedback; Message Batches API (50% savings, 24h window, custom_id); multi-instance and multi-pass review.
- Domain 5: Context Management & Reliability — 15%. Task statements: long-conversation context (case-facts blocks, lost-in-the-middle, trimming tool output); escalation and ambiguity resolution; error propagation across agents; large-codebase exploration context (scratchpads, subagents, /compact, manifests); human review and confidence calibration; provenance and conflict annotation in synthesis.

Frame each question in one of the six official exam scenarios: Customer Support
Resolution Agent; Code Generation with Claude Code; Multi-Agent Research System;
Developer Productivity with Claude; Claude Code for Continuous Integration;
Structured Data Extraction.

Out of scope — never ask about: fine-tuning or training custom models; API
authentication, billing, or account management; deploying/hosting MCP server
infrastructure; Claude's internal architecture or safety training; embeddings or
vector databases; computer use; vision; streaming implementation; rate limits,
quotas, or pricing math; OAuth or key rotation; cloud-provider specifics;
benchmarking; prompt-caching internals.
