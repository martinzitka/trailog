# 3. Build toolchain and module skeleton

Date: 2026-07-19

## Status

Accepted

## Context

M1.1 requires a Gradle multi-module project with version catalogs, an Android app module
and a platform-free `:core` module, and CI that builds and tests on every push. The stack
is fixed by CLAUDE.md (Kotlin, Compose, Room, Ktor later, PostGIS later). We need concrete,
mutually compatible tool versions and a layout that enforces the `:core` purity rule.

## Decision

Pin the following, managed centrally in `gradle/libs.versions.toml`:

| Tool | Version | Why |
|---|---|---|
| JDK | 17 (Temurin) | AGP 8.7 requires 17; broadly supported. |
| Gradle | 8.11.1 | Compatible with AGP 8.7.x; recent stable. |
| Android Gradle Plugin | 8.7.3 | Current stable at project start. |
| Kotlin | 2.1.0 | Brings the built-in Compose compiler plugin. |
| KSP | 2.1.0-1.0.29 | Matched to Kotlin 2.1.0; used by Room. |
| Room | 2.6.1 | Stable; KSP codegen. |
| compileSdk / targetSdk | 35 | Android 15; covers the FGS-type rules we depend on. |
| minSdk | 26 | Android 8.0. Adaptive icons, notification channels, modern FGS. |

Module layout is `:core` (pure Kotlin/JVM) and `:app` (Android), per CLAUDE.md. `:server`
and `:tools:importer` are added at M3/M2 respectively; KMP is deliberately not set up now.

`:core` purity is enforced by `NoPlatformLeakTest`, which asserts no `android.*`/Ktor class
is loadable and no such artifact is on the runtime classpath — not merely by convention.

Dependency freshness is delegated to Renovate; the `GradleDependency` and
`AndroidGradlePluginVersion` lint checks are disabled so the build does not nag about
versions Renovate already tracks.

## Consequences

- One source of truth for versions; upgrades are a catalog edit plus a Renovate PR.
- The purity test fails the build if anyone adds an Android or Ktor dependency to `:core`,
  protecting the "one parser, one statistic" guarantee.
- These versions will age. Superseding ADRs record notable upgrades (e.g. a move to
  Gradle 9 or a Kotlin major bump), not routine Renovate bumps.
