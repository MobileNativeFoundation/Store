# Rust documentation fallback

## Repository precedence

Use repository-local format, tooling, generators, and coherent repository
conventions first. When coherent repository conventions conflict with this
adapter, the repository wins. Preserve the local rustdoc convention and lint
configuration. Treat this adapter as a fallback, not a house style.

## Public surface

Document only intended public declarations. Cover parameters, return values,
types, lifetimes, ownership, and caller-visible invariants without narrating
the signature.

## Native format

Use rustdoc in native placement and native syntax. `///`, `//!`, `/**`, and
`/*!` become documentation attributes. When an item is passed to declarative
or procedural macros, those attributes are macro input and may affect generated
code. Doctests compile or run; preserve item versus module placement and test
behavior.

## Errors and lifecycle

Document panics, errors, safety requirements, ownership lifecycle, feature
gates, platform behavior, and intra-doc links when users must account for them.
Keep claims conditional where `cfg` or features alter behavior.

## Examples

Prefer repository examples and the local convention for hidden setup. Verify
examples through rustdoc doctests and show only the code readers need.

## Links and cross-references

Use rustdoc intra-doc links and repository cross-references. Resolve every
target under the relevant features and avoid guessed item paths.

## Generated documentation

Establish generated ownership before editing rustdoc output. Change
authoritative source inputs, run the repository's regeneration workflow, and
inspect feature-dependent output.

## Protected directives and semantic comments

Protected directives and semantic comments are not ordinary documentation
edits. Preserve non-doc attributes, lint controls, feature gates, and macro
input. Treat doc comments and explicit `#[doc = "..."]` identically on macro
input: edit them only when repository convention classifies both forms as
documentation and macro-aware compile, expansion, and generated-output checks
establish no generated-code or runtime effect; otherwise both forms are
protected. Ordinary Rust doc comments remain editable when the macro-sensitive
condition is absent and documentation-only proof is established.

## Fixture cases

- **Allowed:** Clarify an ordinary public function's doc comment when the
  macro-sensitive condition is absent and documentation-only proof exists.
- **Disallowed:** Rewrite a lint attribute or macro-visible doc attribute
  without macro-aware generated-code checks.
- **Conditional:** Change doc comments and explicit `#[doc = "..."]` on macro
  input only when repository convention classifies both as documentation and
  macro-aware compile, expansion, and generated-output checks establish no
  generated-code or runtime effect.

## Documentation-only verification

Run configured lints, `cargo doc --no-deps`, `cargo test --doc`, and focused
tests under relevant features. For macro input, run configured macro-aware
compile or expansion checks and inspect generated output for code or runtime
changes. Inspect generated links and warnings. If the repository lacks a
mechanical token/AST/trivia-equivalence proof, report the boundary as
`not mechanically proven` even if checks pass.
