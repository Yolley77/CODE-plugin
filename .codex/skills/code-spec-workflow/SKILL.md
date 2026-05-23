---
name: code-spec-workflow
description: Create or validate portable project initiation artifacts for coding agents and developers: CODE.md as a PDCA workflow agreement and SPEC.md/docs/SPEC.md as an LLM-ready spec-driven development artifact. Use when Codex needs to inspect any repository, generate missing CODE/SPEC files, validate existing ones, prepare documentation an external developer can run on their own project, reduce future context scanning, or create implementation plans for other agents.
---

# CODE/SPEC Workflow

Use this skill to create or improve the two project-entry artifacts for the current repository or for a portable handoff prompt that another developer can run in their own project:

- `CODE.md`: a compact workflow agreement for humans and LLMs, organized by Prepare, Develop, Control, Apply.
- `SPEC.md` or `docs/SPEC.md`: a spec-driven development artifact that captures product/system behavior, capabilities, current implementation status, architecture flows, quality gates, and an implementation plan for future agents.

CODE and SPEC are complementary: `SPEC.md` says what the project does and what should be implemented; `CODE.md` says how a human or agent should safely move through the codebase using Prepare, Develop, Control, Apply.

## CODE Method

CODE is a project-specific operating agreement for humans and LLM agents. It is not a README, a generic style guide, or a product spec. It tells the next agent how to enter this repository, choose the right context, make changes in the local style, prove the result, and hand the work forward.

Use CODE as the operational companion to spec-driven development:

- `SPEC.md` defines the product/system truth and implementation intent.
- `CODE.md` defines the working loop that keeps implementation accurate, reviewable, and cheap in context.

The loop has four stages:

- `Prepare`: understand the task, read the minimum useful context, identify entrypoints, boundaries, risks, and questions before editing.
- `Develop`: implement in the repository's existing style, reuse local abstractions, respect module boundaries, and avoid speculative rewrites.
- `Control`: run or name the real checks, collect evidence, inspect likely regressions, and mark anything that could not be verified.
- `Apply`: summarize changed files, checks, risks, decisions, docs updates, and the next PDCA/CODE step for the following human or agent.

## Quick Workflow

1. In interactive mode, ask at least 3 clarifying questions before final writes. In batch/default execution, do a lightweight scan, choose conservative defaults, continue, and ask only for decisions that cannot be safely inferred.
2. Inspect the repository before writing. Start with `rg --files`, existing initiation docs, build/config files, runtime entrypoints, tests, and `git status`.
3. Decide per artifact:
   - if `CODE.md` is missing, create it;
   - if `CODE.md` exists, validate and improve stale, generic, contradictory, or missing sections;
   - if `SPEC.md`/`docs/SPEC.md` is missing, create it;
   - if a spec exists, validate and improve gaps, stale facts, missing acceptance criteria, and unsupported claims.
4. Keep `CODE.md` and `SPEC.md` separate. Do not turn `CODE.md` into a product spec, and do not duplicate the full PDCA workflow inside `SPEC.md`.
5. Adapt to the project type before applying templates: IDE plugin, web app, backend service, CLI, library, mobile app, data/ML pipeline, or another stack.
6. Ground every important claim in repository evidence: `Source: <path>` or `Inferred from: <paths>`.
7. Validate that the result can be handed to a human developer, an external developer adopting CODE, and a future LLM agent as starter context.

For the canonical reusable prompt and output contract, read [references/code-spec-agent-prompt.md](references/code-spec-agent-prompt.md). `docs/project-spec-agent-prompt.md` is a shareable project copy for external developers; keep its version and core rules aligned with the reference.

## Portable Handoff

When the user wants to popularize CODE or give another developer a reusable artifact, provide the prompt from `docs/project-spec-agent-prompt.md` or `references/code-spec-agent-prompt.md` as the artifact to run in the other project. It must work even when the target project has no `CODE.md`, no `SPEC.md`, no `CODE.yaml`, and no CODE plugin installed.

Do not add extra strategy documents unless the user asks. Keep the approach concentrated in the skill and the reusable prompt.

## CODE.md Rules

Use `CODE.md` for project-specific working practices:

- `Prepare`: repository map, entrypoints, source roots, first files to read, setup/config surface, boundaries, questions/gates before edits.
- `Develop`: local patterns, module boundaries, extension points, feature/test/config conventions, anti-patterns, rules for reusing or adding abstractions.
- `Control`: real build/test/lint/typecheck commands, manual checks, expected evidence, likely regressions, skipped/failed check reporting.
- `Apply`: handoff format, PR/review checklist, changed files/checks/risks/gaps reporting, docs update rules, decisions and follow-up work.

Keep it compact enough to load often. Prefer paths, commands, tables, and concrete decision rules over generic advice.

## SPEC.md Rules

Use `SPEC.md` or `docs/SPEC.md` for project truth:

- users, scenarios, capabilities, and current status;
- product/system behavior and user-visible workflows;
- architecture/runtime flows with entrypoints and verification evidence;
- data/config/AI behavior and failure modes;
- acceptance criteria, risks, open questions, and future-agent implementation plan;
- context budget plan so later agents do not rescan the whole repository.

Default to `docs/SPEC.md` when the project already has a `docs/` directory; otherwise use root `SPEC.md`.

Use the user's language or the repository's existing documentation language by default. Russian with technical English terms is appropriate when the user asks for Russian or when distributing the Russian prompt artifact.

## Validation Bar

The artifacts are good enough only if:

- the next agent can answer "what to read first, what to change, how to check, how to hand off";
- paths and commands are current and supported by repo files;
- capabilities have status, owner/entrypoint, user-visible behavior, and acceptance criteria;
- gaps and assumptions are explicit;
- secrets and credential values are not copied;
- generic advice has been replaced with project-specific rules;
- `SPEC.md` supports spec-driven development, while `CODE.md` remains the operational workflow companion;
- existing hand-written docs keep useful project-specific sections unless they are stale, generic, contradictory, or harmful for future agents.

## Final Response

Report changed files, sources inspected, checks run or skipped, remaining assumptions/gaps, and the recommended next PDCA step.
