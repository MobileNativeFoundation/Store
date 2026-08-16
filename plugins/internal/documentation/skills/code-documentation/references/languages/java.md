# Java documentation fallback

## Repository precedence

Use repository-local format, tooling, generators, and coherent repository
conventions first. When coherent repository conventions conflict with this
adapter, the repository wins. Preserve the local Javadoc convention and
configured tooling. Treat this adapter as a fallback, not a house style.

## Public surface

Document only intended public declarations and public and protected members
that form the intended API. Cover parameters, return values, types, type
parameters, and caller-visible contracts without narrating the signature.

## Native format

Use Javadoc in native placement and native syntax recognized by the configured
doclint and documentation task. Keep tags attached to the declaration they
describe and follow local tag ordering.

## Errors and lifecycle

Document checked exceptions, relevant unchecked exceptions, ownership,
nullability, lifecycle, cleanup, and thread safety when callers must depend on
them. Do not infer guarantees from annotations alone.

## Examples

Prefer small repository examples that compile against the intended API.
Verify examples with the configured build or test harness before presenting
them as working.

## Links and cross-references

Use Javadoc links and cross-references according to repository convention.
Resolve every type, member, package, and external target under configured
documentation generation.

## Generated documentation

Establish generated ownership before editing Javadoc output. Change
authoritative source inputs, run the repository's regeneration workflow, and
review generated pages for missing members or broken links.

## Protected directives and semantic comments

Protected directives and semantic comments are not ordinary documentation
edits. Preserve annotations, signatures, suppression controls, and
processor-sensitive comments. Edit a repository-owned documentation annotation
only when repository policy classifies it as documentation and compile,
reflection, and configured generation checks establish no runtime effect;
otherwise it is protected.

## Fixture cases

- **Allowed:** Add a missing checked-exception contract to an intended API
  method without changing its signature.
- **Disallowed:** Replace a suppression control or processor-sensitive comment
  with Javadoc.
- **Conditional:** Change a repository-owned documentation annotation only
  when repository policy classifies it as documentation and compile,
  reflection, and configured generation checks establish no runtime effect.

## Documentation-only verification

Run configured Javadoc and doclint, compile the affected source, and run
focused tests. Inspect generated links and public members. If the repository
lacks a mechanical token/AST/trivia-equivalence proof, report the boundary as
`not mechanically proven` even if checks pass.
