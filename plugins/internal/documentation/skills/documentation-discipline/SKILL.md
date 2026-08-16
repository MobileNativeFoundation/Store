---
name: documentation-discipline
description: Use when the user wants to create, revise, or audit READMEs, interface or implementation documentation, docstrings, doc comments, or inline comments, or wants focused wording-only work inside an already selected technical-documentation scope.
---

# Documentation Discipline

## Master test

Keep an element only when the reader needs it to use, change, operate, or
reason about the documented system correctly.

## Workflow

1. Identify the reader, task, publication boundary, and protected technical
   content.
2. Read [references/discipline-rules.md](references/discipline-rules.md)
   completely.
3. Preserve exact contracts before changing prose.
4. Make the smallest complete clarity change.
5. Run the three-pass review.

## Rules that never defer

- Use exact technical terms and define them plainly on first use.
- Treat concision as absence of waste, not absence of needed contract detail.
- State confirmed facts, label uncertainty, and omit unsupported claims.
- Preserve identifiers, signatures, commands, paths, URLs, versions,
  measurements, schemas, errors, and behavioral guarantees.
- Cut throat-clearing, hype, vague benefits, decorative language, obvious code
  narration, and invented precision.
- Follow repository conventions before bundled defaults.

## Protected technical content

A style pass cannot alter code blocks, commands, inline code, identifiers,
signatures, paths, URLs, versions, numbers, units, schema fields, error names,
compatibility statements, behavioral guarantees, evidence classifications,
semantic directives, behavior-bearing comments, or tool/runtime configuration.
Comment syntax does not make behavior-bearing content editable.

## Standalone boundary

When invoked alone, audit or revise wording only inside the already selected
technical-documentation scope. Do not expand the artifact, invent missing
sections, select a new documentation surface, or change executable code.

## Three-pass review

Run separate passes for accuracy, warrant, and reader utility.
