# Python documentation fallback

## Repository precedence

Use repository-local format, tooling, generators, and coherent repository
conventions first. When coherent repository conventions conflict with this
adapter, the repository wins. Preserve a coherent Google, NumPy, Sphinx, or
local style; use PEP 257 only as the fallback. Treat this adapter as a fallback,
not a house style.

## Public surface

Document only intended public declarations at module, class, function, method,
property, and attribute boundaries. Cover parameters, return values, types,
and caller-visible contracts without repeating annotations; use semantic prose
only where annotations do not express the behavior.

## Native format

Use docstrings in native placement and native syntax. Docstrings are
runtime-visible through `__doc__`, so preserve placement and inspect consumers.
Treat doctests as executable documentation and run them.

## Errors and lifecycle

Describe caller-relevant errors, generators, completion and yielding behavior,
async cancellation and ordering, context managers, cleanup, and resource
ownership. Do not promise exceptions or lifecycle guarantees that source and
tests do not establish.

## Examples

Prefer the repository's example style and the smallest case that clarifies the
contract. Verify examples, and run doctests when the repository treats them as
executable.

## Links and cross-references

Use the repository's links and cross-references for modules, symbols, and
long-form guides. Resolve every target and avoid inventing import paths or
generated anchors.

## Generated documentation

Establish generated ownership before editing. Change authoritative source
inputs and use the repository's regeneration workflow; review generated
outputs for unexpected public-surface changes.

## Protected directives and semantic comments

Protected directives and semantic comments are not ordinary documentation
edits. Preserve the shebang, encoding cookie, type comments, `# noqa`,
`# type: ignore`, `# pyright:`, formatter controls, coverage directives, and
other tool-facing comments. Edit repository-owned documentation metadata only
when repository policy classifies it as documentation and applicable checks
establish no runtime effect; otherwise it is protected.

## Fixture cases

- **Allowed:** Clarify an intended public method's ownership rule without
  changing its signature or annotations.
- **Disallowed:** Reword `# type: ignore` as prose or move an executable
  doctest without separately scoped authorization.
- **Conditional:** Change repository-owned documentation metadata only when
  repository policy classifies it as documentation and applicable checks
  establish no runtime effect.

## Documentation-only verification

Run the configured docstring lint, `python3 -m compileall`, doctests, and
focused tests. Use AST/token comparison only if repository tooling supports
it, and inspect every changed hunk. If the repository lacks a mechanical
token/AST/trivia-equivalence proof, report the boundary as
`not mechanically proven` even if checks pass.
