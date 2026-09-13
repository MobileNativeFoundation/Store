# Package README

## Reader job

Help a reader install and use one distributable package without confusing the
package contract with the whole repository.

## Inspect

Inspect the existing README before writing. Check package metadata, release
metadata, authoritative build metadata, shipped artifacts, supported runtimes,
public exports, tests, examples, configuration, failure behavior, migrations,
and generated documentation ownership. Preserve it unchanged when it is
already sufficient for the reader job; revise only an evidenced gap.

## Include when warranted

- The package promise and package boundary.
- Authoritative compatibility derived from package and build metadata.
- A verified install command for the actual distribution channel.
- A minimal example that reaches the package's first useful result.
- Public entry points and the path to deeper interface documentation.
- Configuration and failure behavior that callers must handle.
- Migration links for supported upgrade paths.
- A link back to repository-level contribution material rather than duplicate
  it.

## Exclude

- Repository-wide contribution material owned by the repository README or
  contributor guide.
- Irrelevant internals that do not affect package users.
- An invented support matrix or compatibility inferred from convention.
- Copied generated API reference that will drift from its authority.

## Verification

Run the install command against the intended package artifact and run the
minimal example in the supported environment. Check compatibility against
authoritative metadata, public entry points against the shipped package, and
links against real current targets. Qualify any command or behavior that was
not mechanically exercised.
