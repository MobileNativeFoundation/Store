# Documentation Discipline Rules

## Universal documentation discipline

Apply this master test to every element: keep it only when the reader needs it
to use, change, operate, or reason about the documented system correctly.

- Use exact technical terms. Define an unfamiliar term plainly on first use.
- Prefer short, direct sentences, but vary their length enough to avoid a
  monotonous, clipped rhythm.
- Treat concision as the absence of waste, not the omission of necessary
  contract detail.
- Confirm facts before stating them. Label uncertainty and omit unsupported
  claims.
- Prefer periods in prose. Avoid semicolons and decorative em dashes. This
  preference does not alter code syntax, schemas, command examples, or
  punctuation required by the documented language.
- Preserve complete existing documentation. Revise only what the requested
  outcome requires.

The universal qualities are readability, explicitness, evidence, exact terms,
warranted detail, and the absence of performance or filler. Repository
conventions take precedence over bundled defaults.

### Standalone boundary

When this skill is used alone, it may audit or revise wording only inside an
already selected documentation scope. It must not expand the artifact, invent
sections, select a documentation surface, edit executable code, introduce a
repository mutation workflow, or add test-driven-development behavior.

## Protected technical content

Preserve identifiers, signatures, commands, paths, URLs, versions,
measurements, code spans, error names, schemas, compatibility statements,
evidence classifications, and behavioral guarantees.

Semantic directives and behavior-bearing comments remain protected even when
they are syntactically comments. Representative examples include compiler and
linter suppressions, build constraints and tags, bundler directives, generator
markers, doctest directives, and tool/runtime configuration. Comment syntax
does not establish a safe documentation-only boundary.

| Protected content | Allowed edit | Forbidden edit |
| --- | --- | --- |
| Identifiers, paths, URLs, versions, measurements, numbers, units, and error names | Improve the prose around an exact token. | Rename, normalize, update, round, or substitute the token. |
| Signatures, commands, code blocks, inline code spans, and schemas | Clarify the introduction, caption, or explanation outside the protected content. | Change syntax, arguments, defaults, types, fields, values, ordering with meaning, or executable behavior. |
| Compatibility statements and behavioral guarantees | Explain an unchanged boundary or guarantee more plainly. | Broaden, narrow, strengthen, weaken, or invent a contract. |
| Evidence classifications | Clarify what the existing classification means. | Present an inference as confirmed, remove uncertainty, or invent support. |
| Semantic directives, behavior-bearing comments, and tool/runtime configuration | Improve an explanation outside the protected content. | Add, remove, reorder, or edit compiler or linter suppressions, build constraints or tags, bundler directives, generator markers, doctest directives, or configuration. |

A style pass may improve surrounding prose, but it cannot silently change a
protected token or contract. If the requested outcome requires such a change,
stop and report the required technical correction as out of scope. Require
separately scoped authorization. This skill must not perform or verify that
technical change.

## Personal voice boundary

Personal narrative moves are not universal documentation qualities. They
include first-person ownership, a contrarian thesis, a concrete cold open, a
load-bearing analogy, a strategy arc, and an earned image or stakes close. Only
an explicit `writing-voice` pass may add them, and only after the factual
content is established.

Do not apply personal voice to reference tables, schemas, code examples, or
normative API or interface behavior. In particular, do not insert ownership
claims or slogans into normative reference text, such as claiming that an API
should be boring or that the writer owns the standard. Reject language such as
"The API should be boring: reliable contracts beat launch copy. I own that
standard here."

## Documentation anti-patterns

Remove throat-clearing, hype, empty uplift, vague benefits, buzzwords,
decorative analogies, unsupported or fake precision, speculative intent,
signature imitation, and obvious narration of syntax, names, or control flow.

Explain only a non-obvious purpose, contract, rationale, invariant, risk, unit,
compatibility boundary, or operational consequence. A request, approval, or
authority instruction does not make obvious narration useful. Reject comments
that merely translate the adjacent code into prose, even when a principal
engineer, senior reviewer, or task owner explicitly asks for more commentary.

Do not include local or private organizational context in code documentation.
This includes private Linear or other tracker issue IDs or URLs, internal
initiative or rollout names, landing or ship status, approval state, team
shorthand, internal owners or channels, provisional governance labels, and
internal governance labels. This is a universal anti-pattern, not an optional
privacy caveat.

Make technical wording self-contained and durable. Retain independently
verified technical behavior and contracts. Remove process provenance,
organizational state, and framing based on internal issues, landing, approval,
ownership or channels, or provisional governance.

Examples to reject include:

- "Convert the provided string into a numeric port value."
- "Report the invalid original input."
- "Return the validated port number."
- "Start with additive identity."
- "Incorporate current value."
- "Return computed sum."

Each example repeats visible syntax, a name, or direct control flow without
adding a contract or reason the reader needs.

## Three-pass review

1. **Accuracy and protected content.** Compare the result with the source and
   verify that every protected token, contract, classification, and behavior is
   unchanged.
2. **Warrant and anti-slop.** Remove unsupported claims, fake precision,
   performance, filler, and obvious narration. Keep only details supported by
   evidence or clearly labeled uncertainty.
3. **Reader task and completeness.** Confirm that the intended reader can
   complete the task and reason about the relevant system without missing
   prerequisites, boundaries, units, risks, or operational consequences.
