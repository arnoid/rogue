<!--
SYNC IMPACT REPORT
==================
Version change: [TEMPLATE] → 1.0.0
Modified principles: N/A (initial population from template)
Added sections: Core Principles (I–V), Development Workflow, Governance
Removed sections: None (all template placeholders replaced)
Templates reviewed:
  ✅ .specify/templates/plan-template.md — Constitution Check gate present; aligns with principles
  ✅ .specify/templates/spec-template.md — Functional Requirements and Success Criteria align with data-driven principle
  ✅ .specify/templates/tasks-template.md — Phase structure (Setup → Foundational → User Stories) aligns with SOLID decomposition principle
Follow-up TODOs: None — all placeholders resolved.
-->

# Roguelike Constitution

## Core Principles

### I. Core-Rendering Separation

The `core` package MUST contain zero LibGDX imports. All game logic (movement, collision,
gravity, interaction, world model) MUST live in `core`. Rendering concerns are isolated to
the `rendering` package, connected via bridge patterns (e.g., `TileRenderRegistry`). The
editor, serialization, and utilities packages may reference LibGDX but MUST NOT bleed
rendering state into core data classes.

**Rationale**: The LibGDX-free core can be unit-tested without a running game or display
context, making the entire simulation layer independently verifiable.

### II. Test-First (NON-NEGOTIABLE)

TDD is mandatory for all core logic. Tests MUST be written and confirmed to fail before
implementation begins. The Red-Green-Refactor cycle is strictly enforced. Integration
tests cover inter-system contracts (movement ↔ collision, gravity ↔ stairs). Because core
is LibGDX-free, tests MUST run without a display and without mocking the render layer.

**Rationale**: Prior architecture mixed rendering into game logic, making automated testing
impossible. The clean-core design enables fast, deterministic tests; TDD protects that
investment.

### III. SOLID & Single Responsibility

Every class MUST have one clearly stated reason to change. Large orchestrators (e.g.,
`MapEditor`, `RoguelikeGame`) MUST delegate to focused collaborators rather than
implementing behavior inline. When a class grows beyond one responsibility it MUST be
decomposed — following the editor decomposition precedent (`EditorPalettePanel`,
`EditorStatusBar`, `EditorInputHandler`). Dependencies MUST be injected (constructor or
parameter) rather than obtained globally; the `GameLogger` fun-interface pattern is the
model.

**Rationale**: Monolithic classes cannot be tested in isolation and become the primary
source of merge conflicts and regressions.

### IV. Data-Driven Game Mechanics

Game rules (movement, collision, gravity, stairs, doors) MUST be documented in
authoritative rule documents (e.g., `game_rules.md`) before or alongside implementation.
Tile types MUST be pure data classes with zero rendering state. Behavior MUST be derived
from tile type and slot, not from rendering properties. Any change to game physics or tile
semantics MUST first update the rule document, then the code.

**Rationale**: Keeping rules in code only leads to undocumented implicit behavior that is
impossible to verify against design intent. The documented-first approach makes the spec
the contract.

### V. Simplicity & YAGNI

No abstraction, layer, or pattern is introduced without a current, concrete justification.
Backward-compatibility typealiases (e.g., `CoreAliases.kt`) are temporary migration aids
and MUST be removed once callers are migrated. Every complexity violation MUST be
documented in plan.md's Complexity Tracking table. "We might need it later" is not
sufficient justification.

**Rationale**: Over-engineering increases cognitive load without delivering value. The
three-package (core / rendering / world) split is the ceiling for structural complexity
until a concrete need is demonstrated.

## Development Workflow

- Feature branches follow the `###-feature-name` convention (e.g., `001-light-system`).
- Every feature MUST have a spec.md before planning and a plan.md Constitution Check
  before implementation begins.
- The Constitution Check in plan.md MUST list each principle and explicitly confirm
  compliance or document a justified violation in the Complexity Tracking table.
- All PRs targeting `master` MUST pass the Constitution Check gates.
- Backward-compat typealiases left in the codebase MUST be tracked as open tasks and
  removed within the same feature or the next feature that touches the affected package.
- Tests for core logic run via `./gradlew test` and MUST pass before any PR is merged.
- The desktop target is the primary build; run via `./gradlew run` (macOS requires
  `-XstartOnFirstThread`, already configured in `build.gradle.kts`).

## Governance

This constitution supersedes all other documented practices. Amendments require:

1. A PR with the updated constitution, an incremented version, and a rationale for the
   version bump type (MAJOR / MINOR / PATCH per semantic versioning rules in this file's
   header comments).
2. A migration plan if any existing code violates a new or changed principle.
3. Updates to affected templates (plan-template.md, spec-template.md, tasks-template.md)
   in the same PR.

All PRs and code reviews MUST verify compliance with the five principles above. Introduced
complexity MUST be justified — not just noted. The Complexity Tracking table in plan.md is
the formal record for violations.

**Version**: 1.0.0 | **Ratified**: 2026-05-12 | **Last Amended**: 2026-05-12
