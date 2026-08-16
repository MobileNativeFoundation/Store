# Store plugin

Skills for applications using [Store](https://github.com/MobileNativeFoundation/Store) (`org.mobilenativefoundation.store`). They are consumer-facing: an app team installs them into the codebase that uses Store. The skills in `.claude/skills/` at the repository root are the contributor-facing counterpart and apply only to work inside this repository.

## Skills

- `building-a-store6-data-layer`: designing a new Store 6 data layer (keys, freshness, persistence, platform consumption) when there is no Store 4/5 code to migrate.
- `migrating-to-store6`: translating Store 4 / Store 5 code to the Store 6 API.

## Maintenance

Skill content derives from this repository's source and documentation. Every API spelling is verified against a named commit, recorded in the "Last verified" line at the bottom of each SKILL.md. When `main` moves in ways that touch a documented surface, re-verify, update the stamp, and bump the version in `.claude-plugin/plugin.json`.
