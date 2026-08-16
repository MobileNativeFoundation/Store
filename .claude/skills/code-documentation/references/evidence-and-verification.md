# Evidence and Verification

## Evidence precedence

Use this order unless repository instructions override it:

1. Repository instructions and configured generators.
2. Public interfaces, schemas, manifests, configuration, and source.
3. Contract and behavior tests.
4. Current build, release, and deployment configuration.
5. Existing documentation that agrees with the live repository.
6. Explicitly labeled inference or uncertainty.

Tests show observed behavior. They do not automatically prove intent, public
support, or historical rationale.

Local or private organizational context is not publication content or
provenance. Under the mandatory `documentation-discipline` rule, do not expose
or cite private Linear or other tracker identifiers, internal project names or
labels, internal initiative or rollout state, landing or ship status, approval
state, team shorthand, internal owners or channels, or provisional governance
labels. Accessibility does not grant publication authority. Make documentation
self-contained and durable. Retain only independently verified technical
behavior and contracts.

## Finding classes

Classify each candidate finding before reporting or changing it:

| Class | Meaning |
| --- | --- |
| Missing | Reader-required documentation is absent. |
| Stale | The documentation no longer matches the live repository. |
| Contradictory | Two claims or a claim and the implementation disagree. |
| Duplicated | Repeated content creates avoidable maintenance or drift risk. |
| Unverifiable | Available evidence cannot establish the claim. |
| Misleading | Individually true wording creates an incorrect reader conclusion. |
| Unnecessary | The content does not help the reader use, change, operate, or reason about the system. |
| Sufficient | The content is accurate, warranted, and complete for the reader task. |

In audits, prioritize incorrect contracts, unsafe commands, disclosure
problems, and broken onboarding before style.

## Generated documentation ownership

Classify each target as hand-authored, generated, or mixed. Authoritative source
inputs govern generated output. Edit those inputs instead of their derived
artifacts.

For mixed artifacts, preserve repository-defined safe boundaries between
manually authored and generated content. Do not move text across or blur those
boundaries without established repository authority.

Regenerate only when the generator is available, the action is in scope, and
the resulting diff is reviewable. Otherwise, report the missing prerequisite
and affected artifact. Never hand-patch generated output and call it complete.

## Documentation-only source mutation

Authorized mutations are docstrings, doc comments, and ordinary inline
comments. Documentation annotations or metadata are authorized only when
repository policy classifies them as documentation and applicable repository
checks establish no runtime effect. They are otherwise protected.

Protected content includes executable statements or expressions, control flow,
declarations or signatures, types, imports, runtime-effect configuration,
generated runtime code, and executable formatting changes. Semantic directives
and behavior-bearing comments are protected, not ordinary comment edits.
Language adapters identify their language-specific forms.

In a dirty worktree, preserve unrelated edits. Separate pre-existing or user
edits from agent-created hunks. Inspect every introduced hunk, use
language-aware tooling when available, and run relevant repository checks.
Passing tests alone is not proof of a documentation-only boundary. Report the
proof level.

If an executable-token change appears, stop the affected edit. Revert only the
agent-created hunk when that is safe; otherwise, report the unresolved boundary.

## Verification evidence levels

- **Mechanically proven:** language-aware tooling establishes that introduced
  changes affect only the authorized documentation surface.
- **Repository checks plus hunk review:** relevant checks pass and every
  introduced hunk has been inspected, but no mechanical boundary proof is
  available.
- **Not established:** tooling, checks, or review cannot establish the claimed
  documentation-only boundary.

Use the strongest level the evidence actually supports. Do not upgrade the
classification because tests pass.

## Completion report

Report:

- reviewed and changed files;
- evidence used for claims;
- commands and results;
- unresolved uncertainty;
- claims omitted for lack of evidence; and
- actual proof strength.
