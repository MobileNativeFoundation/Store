# Store plugin

Skills for developers using Store.

This package is an Agent Plugin directory: `plugin.json` at the plugin root and skills as immediate children of `skills/`.

## Skills

- `building-a-store6-data-layer`: designing a new Store 6 data layer (keys, freshness, persistence, platform consumption) when there is no Store 4/5 code to migrate.
- `migrating-to-store6`: translating Store 4 / Store 5 code to the Store 6 API.

## Maintenance

Skill content derives from this repository's source and documentation. Every API spelling is verified against a named commit, recorded in the "Last verified" line at the bottom of each SKILL.md. When `main` moves in ways that touch a documented surface, re-verify, update the stamp, and bump the version in `plugin.json`.
