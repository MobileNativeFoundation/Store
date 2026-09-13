# TypeScript and JavaScript documentation fallback

## Repository precedence

Use repository-local format, tooling, generators, and coherent repository
conventions first. When coherent repository conventions conflict with this
adapter, the repository wins. Preserve the local TSDoc, JSDoc, TypeDoc, or
other local convention. Treat this adapter as a fallback, not a house style.

## Public surface

Document only intended public declarations and intended exports; verify barrel
ownership before treating a symbol as public. Cover parameters, return values,
types, generics, and caller-visible behavior, but do not duplicate signature
types in prose.

## Native format

Use doc comments in native placement and native syntax for the repository's
JavaScript or TypeScript toolchain. Keep comments attached to the declaration
the configured parser and documentation generator actually consume.

## Errors and lifecycle

Describe async resolution, rejection, cancellation, thrown errors, lifecycle,
side effects, and resource ownership when callers must act on them. Do not
invent guarantees about scheduling or cleanup.

## Examples

Prefer repository examples that use supported imports and configuration.
Verify examples with the configured runner or typechecker when they are
executable.

## Links and cross-references

Use configured links and cross-references for exports, symbols, and guides.
Resolve every target in the generated documentation and avoid guessed barrel
paths or anchors.

## Generated documentation

Establish generated ownership before editing declaration output or documentation
generator output. Change authoritative source inputs, run the repository's
regeneration workflow, and inspect declarations for contract drift.

## Protected directives and semantic comments

Protected directives and semantic comments are not ordinary documentation
edits. Preserve triple-slash references, `@ts-check`, `@ts-ignore`,
`@ts-expect-error`, ESLint and formatter controls, bundler magic comments,
source-map comments, and other tool pragmas. Treat type-bearing JSDoc as
protected: `@type`, typed `@param`, `@returns`, `@template`, `@typedef`, and
similar type or contract tags can affect checking, emitted declarations, or
public contracts. Edit prose-only JSDoc metadata only when repository policy
classifies it as repository-owned documentation and declaration generation and
applicable checks establish no emitted type, signature, or contract change and
no runtime effect; otherwise it is protected.

## Fixture cases

- **Allowed:** Clarify prose about an intended export's resource ownership
  without changing type-bearing JSDoc or its signature.
- **Disallowed:** Change `@type`, typed `@param`, `@returns`, `@template`,
  `@typedef`, `@ts-expect-error`, or a bundler magic comment as ordinary prose.
- **Conditional:** Change prose-only JSDoc metadata only when repository policy
  classifies it as repository-owned documentation and declaration generation
  and applicable checks establish no emitted type, signature, or contract
  change and no runtime effect.

## Documentation-only verification

Run configured lint, typecheck, declaration generation, TypeDoc or other doc
generation, and focused tests. Use parser/trivia comparison when available,
then inspect the emitted declaration and documentation diff. If the repository
lacks a mechanical token/AST/trivia-equivalence proof, report the boundary as
`not mechanically proven` even if checks pass.
