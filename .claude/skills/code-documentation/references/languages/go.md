# Go documentation fallback

## Repository precedence

Use repository-local format, tooling, generators, and coherent repository
conventions first. When coherent repository conventions conflict with this
adapter, the repository wins. Preserve package comments, exported declaration
comments, and the local lint convention. Treat this adapter as a fallback, not
a house style.

## Public surface

Document only intended public declarations and exported names that form the
supported API. Cover parameters, return values, result types, ownership, and
caller-visible contracts without repeating the declaration.

## Native format

Use comments in native placement and native syntax for packages and exported
declarations. Begin declaration comments according to repository and Go
tooling convention.

## Errors and lifecycle

Document blocking, concurrency ownership, cancellation, errors, resource
lifecycle, and cleanup when callers must coordinate them. Do not invent
goroutine, ordering, or retry guarantees.

## Examples

Use runnable `Example...` conventions when the repository uses example tests.
Verify examples with `go test` and keep setup focused on the documented
behavior.

## Links and cross-references

Use repository-supported links and cross-references for packages, declarations,
and guides. Resolve every target in `go doc` or the configured documentation
renderer.

## Generated documentation

Establish generated ownership before editing generated comments or reference
output. Change authoritative source inputs, run the repository's regeneration
workflow, and inspect the generated diff.

## Protected directives and semantic comments

Protected directives and semantic comments are not ordinary documentation
edits. Preserve build tags, `//go:` directives, `//line` and `/*line` line
directives, cgo preambles, cgo `//export` directives, lint directives, the
exact `Deprecated:` doc marker, and runnable Example function bodies. Line
directives alter source positions, `//export` alters cgo exports, and
`Deprecated:` changes tooling deprecation behavior. They are not ordinary
wording edits. Struct tags are runtime-visible metadata, not documentation-only;
treat unknown metadata as protected.

## Fixture cases

- **Allowed:** Clarify cancellation ownership on an exported function without
  changing its declaration.
- **Disallowed:** Reword a build tag, `//go:` directive, `//line`, `/*line`,
  cgo `//export`, exact `Deprecated:` marker, struct tag, or runnable Example
  body as documentation.

## Documentation-only verification

Run configured documentation lint, `go doc`, `go test`, focused tests, and
`go vet` when the repository uses those commands. Inspect every changed hunk.
If the repository lacks a mechanical token/AST/trivia-equivalence proof,
report the boundary as `not mechanically proven` even if checks pass.
