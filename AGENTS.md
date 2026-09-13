# Agent instructions

These instructions govern any agent working in this repository. They apply to every
documentation surface: READMEs, KDoc and doc comments, inline comments, workflow YAML
comments, commit messages, and pull-request bodies.

## Documentation discipline

The full rules are embedded in this repository as skills:
`plugins/internal/documentation/skills/documentation-discipline/` (governs every sentence) and
`plugins/internal/documentation/skills/code-documentation/` (governs evidence, artifact shape, mutation, and
verification). It composes with the discipline skill. Agents with skill support invoke them by
name before documentation work. Agents without it read the `SKILL.md` files and their
`references/` directly. The core rules below are the load-bearing summary and apply either
way.

### The master test

Keep a documentation element only when the reader needs it to use, change, operate, or
reason about the documented system correctly. Cut throat-clearing, hype, vague benefits,
decorative language, narration of visible syntax or control flow, and invented precision.

### Protected technical content

A wording pass never alters identifiers, signatures, commands, paths, URLs, versions,
numbers, units, measurements, schema fields, error names, compatibility statements,
behavioral guarantees, or evidence classifications. Semantic directives and behavior-bearing
comments (suppressions, build constraints, generator markers, tool configuration) are
protected even though they are syntactically comments. If a requested change requires
altering a protected token, stop and report it as a technical change needing its own
authorization. Do not fold it into a style pass silently.

### No internal organizational context in code surfaces

Code documentation must be self-contained and durable. Do not put issue-tracker IDs,
internal project or initiative names, ruling or approval shorthand, landing status, team
shorthand, or internal revision labels into source comments, workflow comments, or step
names. State the technical fact with a durable attribution instead. For example,
"measured 2h40m-3h13m on hosted runners at the current suite" rather than a tracker
reference. Pull-request bodies and commit messages may reference issues and process records.
Source files may not.

### Evidence before claims

State confirmed facts. Label uncertainty explicitly. Verify a claim before writing it. A
coverage or completeness claim ("every X now does Y") requires an actual sweep, not an
extrapolation from the files you happened to touch. Never present a failed command or
example as working.

### Repository comment conventions

Some comment blocks are deliberately byte-identical across sibling files (for example the
test-deadline wrapper comment that appears with the shared `runTest` shim in test files).
Match the established shape exactly when extending such a pattern. Do not reword one copy.
When editing near an existing comment, preserve it unless it is wrong. Revise only the
evidenced deficiency.

### Three-pass review

Before finishing documentation work, run three separate passes:

1. **Accuracy.** Every protected token, contract, and classification unchanged and correct
   against the source.
2. **Warrant.** Every remaining claim supported by evidence or labeled as uncertain.
3. **Reader utility.** The intended reader can complete their task without missing
   prerequisites, boundaries, units, risks, or operational consequences.
