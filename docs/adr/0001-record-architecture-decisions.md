# 1. Record architecture decisions

Date: 2026-07-19

## Status

Accepted

## Context

CLAUDE.md and IMPLEMENTATION_PLAN.md require that "every non-obvious architectural choice
gets a short ADR in `docs/adr/`" and that any decision made along the way is recorded
rather than silently revisited. We need a lightweight, consistent format.

## Decision

We use Architecture Decision Records, one Markdown file per decision, numbered
sequentially (`NNNN-title.md`), in the style described by Michael Nygard. Each record has
Status, Context, Decision and Consequences. Records are immutable once accepted; a later
decision that reverses an earlier one is a new record that supersedes it.

## Consequences

- The reasoning behind a choice is discoverable next to the code, not lost in chat logs.
- Superseding rather than editing keeps the history of *why* honest.
- Trivial or obvious choices are not recorded; ADRs are for decisions that constrain future
  work.
