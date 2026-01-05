# FoliaChallenges

A Minecraft challenge plugin only for Folia servers for challenges (like Random Item Battle) designed for many players to spread out.

## Features

- **Challenges**: Challenges that are designed for many players to spread out.
- **Timer Management**: Start, stop, and set countdown timers.
- **Item Blacklist**: Configurable blacklist for items that should not be assigned.
- **World Reset**: Reset the world with a new seed and clean up old worlds.
- **Visual Indicators**: Bossbar for current task, actionbar for timer, and floating item displays above players.

## Roadmap

Currently, as I write this (January 5th, 2026), only the "Random Item Battle" challenge is available. I plan to add more challenges of this type, such as "Random Mob Battle". For more ideas: please open a GitHub issue.

🟢 = Random Item Battle

🔴 = **Jokers, Double targets and whether the item ends up in the inventory when a joker is used** for Random Item Battle configurable via a settings GUI

🔴 = Random Mob Battle

`🟢 = Available`
`🔴 = coming soon and/or WIP`

## Installation

1. Download the latest `FoliaChallenges.jar` from the [Modrinth page](https://modrinth.com/project/foliachallenges).
2. Place the JAR file in your server's `plugins` folder.
3. Restart the server. The plugin will generate default configuration files.
4. Configure `messages.yml`, `items-blacklist.yml`, and `config.yml` as needed.

### Requirements

- **Server**:  Made for Folia 1.21.8+
- **Java**: 17 or higher

## Configuration

The plugin generates the following configuration files in `plugins/FoliaChallenges/`:

### messages.yml
Contains all user-facing messages. Customize colors, text, and placeholders (e.g., `%player%`, `%item%`, `%time%`).
Default text is generated in english.
Other languages ​​for copy-pasting may be available in the [messages.yml languages](https://github.com/dinushay/FoliaChallenges/tree/main/messages.yml%20languages) ​​folder.

German [messages.yml](https://github.com/dinushay/FoliaChallenges/blob/main/messages.yml%20languages/German.yml)

English [messages.yml](https://github.com/dinushay/FoliaChallenges/blob/main/messages.yml%20languages/English.yml)


### items-blacklist.yml
List of items to exclude from random assignment. Add items under `blacklisted-items`:
```yaml
blacklisted-items:
  - BEDROCK
  - BARRIER
```

### config.yml
General settings.

## Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/challenges randomitembattle listitems` | List assigned items for all players | `foliachallenges.admin` |
| `/challenges randomitembattle listpoints` | Show player points and leaderboard | `foliachallenges.admin` |
| `/challenges randomitembattle blockitem <item>` | Add an item to the blacklist | `foliachallenges.admin` |
| `/challenges reload` | Reload configuration and messages | `foliachallenges.admin` |
| `/timer start` | Start the challenge timer | `foliachallenges.admin` |
| `/timer stop` | Stop the challenge timer | `foliachallenges.admin` |
| `/timer set <minutes>` | Set the timer duration | `foliachallenges.admin` |
| `/start` | Start the challenge timer | `foliachallenges.admin` |
| `/reset confirm` | Reset the world (irreversible!) | `foliachallenges.admin` |

### Permissions

- `foliachallenges.admin`: Required for all administrative commands. Defaults to OP or players with this permission.

## Contributing

Feel free to submit issues or pull requests on Github. I don't plan to include challenges that aren't designed for where the players are spread out.

## License

This project is licensed under the MIT License. See `LICENSE` for details.
