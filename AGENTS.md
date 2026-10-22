# Project workflows

For requests to research Minecraft mods and add/update this site's catalogue, read
`.agents/skills/research-minecraft-mods/SKILL.md` and use `scripts/mod_research.py`.
Keep full records in working files; prefer compact receipts and selected fields in tool output.
The skill is scoped to requested catalogue research, not automatic deployment or scheduling.

Repository guides live in `docs/`; catalogue imports and synthetic examples live in
`catalogue/`. Keep executable research/deployment entry points stable, update callers
when moving fixtures, and put temporary work under ignored `build/`.
