# Implementation Documentation

## Reader job

Help a maintainer or operator change or operate internals safely, with enough
context to predict consequences.

## Inspect

Inspect source, tests, configuration, operational entry points, generated
ownership, and repository-public design records. Identify the actual component
boundary and the evidence available for the artifact's publication boundary.

## Include when warranted

- Ownership and boundaries between components.
- Data, event, or request flow through relevant entry points.
- Invariants and state transitions that changes must preserve.
- Concurrency and consistency behavior.
- Failure and recovery paths.
- Extension points and change hazards.
- Operational entry points needed to diagnose or run the system.
- Operational constraints that bound safe change or operation.
- Troubleshooting entry points that lead from a symptom to evidence.
- Evidenced rationale that explains a durable constraint or tradeoff.

## Exclude

- Repeated public reference that belongs in interface documentation.
- Invented or speculative history.
- Unsupported broad architecture claims.
- Private operational or organizational context in public artifacts.
- Detail that does not help a maintainer or operator make a safe decision.

## Verification

Trace flow, invariants, failure behavior, and rationale to source, tests,
configuration, or publication-safe operational evidence. Exercise relevant
checks or entry points when authorized. Distinguish mechanically verified
behavior from review-backed explanation and unresolved uncertainty.
