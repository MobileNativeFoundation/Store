# Interface Documentation

## Reader job

Help a caller use a public symbol or external contract correctly without
depending on private implementation behavior.

## Inspect

Inspect intended public status, authoritative public declarations, types,
generated ownership, tests, examples, compatibility policy, and deprecation
policy. Separate what the caller can observe and rely on from what the
implementation merely does today.

## Include when warranted

Document only caller-visible contracts:

- Purpose beyond the obvious name.
- Inputs and outputs, including units, defaults, and nullability.
- Errors, exceptions, panic behavior, and failure results callers must handle.
- Side effects, resource ownership, and lifecycle.
- Ordering, concurrency, and idempotency.
- Compatibility and deprecation.
- Examples that clarify correct use or a consequential edge case.

## Exclude

- Private internals.
- Implementation choices that callers cannot rely on.
- Guessed guarantees or precision not established by evidence.
- Signature narration that repeats names and types without adding use
  semantics.
- Documenting every visible symbol without checking intended public status.
- Any wording that blurs the caller-versus-implementation boundary.

## Verification

Compare the documentation with public declarations, tests, and examples.
Exercise examples when tooling permits. Check every caller-visible guarantee
against authoritative behavior, and label or omit anything not established.
