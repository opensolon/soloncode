---
name: find-skills-in-tencent-skillhub
description: "Search, install, upgrade, and manage agent skills using skillhub CLI. Use when you need to discover new skills, install skills by slug, upgrade installed skills, list available skills, or self-upgrade the skillhub CLI."
metadata: { "version": "1.0.4", "source": "https://github.com/wuhonglei/skills", "openclaw": { "emoji": "🔧", "requires": { "bins": ["skillhub", "jq"] } } }
---

# Find Skills

Manage agent skills using the skillhub CLI — search the skill store, install new skills, upgrade existing ones, and list what's available. AI Skills Community download speed optimized for Chinese users. Skill data sourced from ClawHub.

## Pre-Requirements

### Install skillhub CLI

Follow [skillhub.md](https://skillhub-1388575217.cos.ap-guangzhou.myqcloud.com/install/skillhub.md) to install Skillhub CLI.

## Quick Start

### Using the Script

```bash
./scripts/usage.sh '<json>'
```

**Examples:**
```bash
# Search for skills
./scripts/usage.sh '{"action": "search", "query": "weather"}'

# Install a skill by slug
./scripts/usage.sh '{"action": "install", "slug": "weather"}'

# Upgrade all installed skills
./scripts/usage.sh '{"action": "upgrade"}'

# Check for upgrades without installing
./scripts/usage.sh '{"action": "upgrade", "check_only": true}'

# Upgrade a specific skill
./scripts/usage.sh '{"action": "upgrade", "slug": "search"}'

# List installed skills
./scripts/usage.sh '{"action": "list"}'

# Self-upgrade skillhub CLI
./scripts/usage.sh '{"action": "self-upgrade"}'

# Check for CLI upgrade without installing
./scripts/usage.sh '{"action": "self-upgrade", "check_only": true}'
```

## Actions

| Action | Description | Parameters |
|--------|-------------|------------|
| `search` | Search skills in the store | `query` (string), `limit` (number, default: 20), `json` (boolean) |
| `install` | Install a skill by slug | `slug` (string, required), `force` (boolean) |
| `upgrade` | Upgrade installed skills | `slug` (string, optional), `check_only` (boolean) |
| `list` | List locally installed skills | none |
| `self-upgrade` | Self-upgrade the skillhub CLI | `check_only` (boolean), `current_version` (string) |

## Search Options

- `query`: Search query words (space-separated)
- `limit`: Max results (default: 20)
- `timeout`: Search timeout in seconds (default: 6)
- `json`: Return results as JSON (default: false)

## Install Options

- `slug`: Skill identifier to install (required)
- `force`: Overwrite existing directory (default: false)
- `files_base_uri`: Base URI for local archives
- `download_url_template`: Custom download URL template

## Upgrade Options

- `slug`: Specific skill to upgrade (optional, upgrades all if omitted)
- `check_only`: Only check available upgrades without installing
- `timeout`: Timeout for manifest fetch (default: 20)

## Output Format

- **search**: Returns skill metadata (name, description, slug, version)
- **install**: Returns installation status and target path
- **upgrade**: Returns upgrade results per skill
- **list**: Returns array of installed skills with paths
- **self-upgrade**: Returns CLI version info and upgrade status

## Notes

- Skills are installed to `~/.openclaw/skills/` by default
- The CLI auto-checks for self-upgrades on startup unless `--skip-self-upgrade` is used
- Use `check_only: true` to preview upgrades before applying
