# Inline Documentation

## Reader job

Help a reader understand a non-obvious local constraint without leaving the
code or mistaking narration for a contract.

## Inspect

Read the local code and adjacent tests around the proposed edit. Identify
existing comments, repository conventions, protected semantic directives, and
the authorized hunk scope before changing text.

## Include when warranted

Use inline documentation only when clearer code cannot express:

- Non-obvious rationale for a durable local choice.
- An invariant or precondition that the surrounding code must preserve.
- A compatibility trap that makes the natural-looking change unsafe.
- A unit, domain, time, or coordinate convention needed to interpret a value.
- A security or privacy boundary enforced at this location.
- Intentionally surprising behavior that is nevertheless correct.
- An algorithmic choice whose material tradeoff matters to future changes.
- An external constraint imposed by a protocol, platform, or dependency.
- Preserve complete current comments when they remain accurate and useful;
  revise only the evidenced deficiency.
- A TODO must be self-contained and name a concrete technical completion
  condition.
- A TODO must not rely on or cite private Linear or tracker IDs, an internal
  owner or channel, project or rollout status, landing or approval status, or
  team shorthand.
- A repository-public durable issue URL may accompany the TODO only when
  repository convention requires it, but it must never replace the completion
  condition.
- This TODO rule overrides any older issue- or owner-only convention.

## Exclude

- Next-line narration that predicts the immediately following statement.
- Name narration that restates an identifier.
- Control-flow narration already clear from the code.
- Speculative narration about future behavior or unsupported intent.
- Decorative narration that adds tone without technical value.
- A comment where a clearer name or extracted function would make the same
  fact evident.

## Verification

Re-read the local code and adjacent tests after editing. Confirm protected
semantic directives are byte-for-byte intact, complete current comments remain
complete, and the hunk scope contains only authorized documentation changes.
Run applicable repository checks, then inspect the diff for accidental
executable or formatting changes.
