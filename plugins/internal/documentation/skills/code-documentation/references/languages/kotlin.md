# Kotlin documentation fallback

## Repository precedence

Use repository-local format, tooling, generators, and coherent repository
conventions first. When coherent repository conventions conflict with this
adapter, the repository wins. Preserve the local KDoc convention and
configured Dokka behavior. Treat this adapter as a fallback, not a house style.

## Public surface

Document only intended public declarations and respect multiplatform source-set
boundaries. Cover parameters, return values, types, and nullability semantics
beyond the type when they affect callers; do not restate declarations.

## Native format

Use KDoc in native placement and native syntax recognized by configured Dokka.
Use declaration links according to local convention and keep documentation
attached to the declaration for the correct source set.

## Errors and lifecycle

Describe coroutines, cancellation, flows, collection and completion behavior,
lifecycle, cleanup, and public errors when callers must respond. Do not invent
threading, dispatcher, or exception guarantees.

## Examples

Prefer repository examples for the relevant platform and source set. Verify
examples with the configured compilation or test task before presenting them
as portable.

## Links and cross-references

Use KDoc declaration links and repository cross-references. Resolve every
target in the applicable source set and generated documentation.

## Generated documentation

Establish generated ownership before editing Dokka output. Change authoritative
source inputs, run the repository's regeneration workflow, and inspect output
for platform or source-set omissions.

## Protected directives and semantic comments

Protected directives and semantic comments are not ordinary documentation
edits. Preserve annotations, declarations, source-set directives, suppression
controls, and treat unknown annotation effects as protected. Edit
repository-owned documentation metadata only when repository policy classifies
it as documentation and applicable checks establish no runtime effect;
otherwise it is protected.

## Fixture cases

- **Allowed:** Clarify flow cancellation behavior on an intended public
  declaration without changing its type or source set.
- **Disallowed:** Reword an annotation or source-set directive as KDoc.
- **Conditional:** Change repository-owned documentation metadata only when
  repository policy classifies it as documentation and applicable checks
  establish no runtime effect.

## Documentation-only verification

Run the relevant Dokka task, compilation, configured documentation lint when
present, and focused tests for affected source sets. Inspect platform output.
If the repository lacks a mechanical token/AST/trivia-equivalence proof,
report the boundary as `not mechanically proven` even if checks pass.
