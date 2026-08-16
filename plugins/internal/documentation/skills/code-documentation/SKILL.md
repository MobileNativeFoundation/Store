---
name: code-documentation
description: Use when creating, revising, auditing, or synchronizing a repository README, package README, API or interface documentation, implementation documentation, docstring, doc comment, or inline comment.
---

# Code Documentation

## Mandatory composition

Run with `documentation-discipline`. This skill governs evidence, artifact
shape, mutation, and verification. That skill governs every sentence.

## Surface routing

Every surface inherits `documentation-discipline`, including its prohibition
on local or private organizational context.

Repository instructions, complete existing documentation, generators, and
local conventions override these bundled defaults. These playbooks are not
fixed templates; include only material warranted by evidence and the reader
job.

- Repository README: `references/surfaces/repository-readme.md`
- Package README: `references/surfaces/package-readme.md`
- Interface documentation:
  `references/surfaces/interface-documentation.md`
- Implementation documentation:
  `references/surfaces/implementation-documentation.md`
- Inline documentation: `references/surfaces/inline-documentation.md`

## Language routing

Language adapters are repository fallbacks, and every adapter inherits
`documentation-discipline`.

- Python: `references/languages/python.md`
- TypeScript and JavaScript:
  `references/languages/typescript-javascript.md`
- Java: `references/languages/java.md`
- Kotlin: `references/languages/kotlin.md`
- Rust: `references/languages/rust.md`
- Go: `references/languages/go.md`

## Modes

- **Create:** write the smallest coherent artifact for the reader task.
- **Revise:** preserve useful content and change only the material delta.
- **Audit:** remain read-only. For every finding, name the affected file,
  section, or symbol; show the evidence; explain reader impact; and give a
  concrete remediation. Do not edit files, commit, publish, or update external
  systems.

## Workflow

1. Orient to repository instructions, docs, manifests, exports, tests, build
   configuration, generators, and local conventions.
2. Classify mode, surface, reader, audience, publication boundary, language,
   hand-authored or generated ownership, and the requested files, packages,
   modules, or repository scope. If the request already establishes these
   dimensions, proceed without a mandatory clarification or outline gate.
3. Read
   [references/evidence-and-verification.md](references/evidence-and-verification.md).
4. Read only the requested surface references and relevant language adapters.
5. Build the evidence inventory and classify the documentation delta.
6. Create, revise, or audit without expanding authority.
7. Run surface, language, repository, generated-ownership, and source-mutation
   checks.
8. Report files, evidence, checks, uncertainty, omissions, and proof strength.

## Stop conditions

- Missing evidence: omit or label uncertainty.
- Failed command or example: do not present it as working.
- Generated target without its authority: report the blocked source.
- Executable-token change: stop the affected edit.
- Public disclosure risk: stop and report the boundary.
