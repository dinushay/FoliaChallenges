# FoliaChallenges

A Minecraft challenge plugin for Folia servers that assigns random items to players and manages a global timer for survival challenges.

## Features

- **Random Item Assignment**: Automatically assigns random items to players at regular intervals during challenges.
- **Timer Management**: Start, stop, and set countdown timers with global broadcasts.
- **Item Blacklist**: Configurable blacklist for items that should not be assigned, plus a hardcoded list of restricted items (e.g., AIR, BEDROCK, BARRIER, COMMAND_BLOCK).
- **World Reset**: Reset the world with a new seed and clean up old worlds on server restart.
- **Leaderboard & Points**: Track player points and display leaderboards.
- **Visual Indicators**: Bossbar for current item, actionbar for timer, and floating item displays above players.
- **Permissions**: Role-based access control for commands.

## Installation

1. Download the latest `FoliaChallenges.jar` from the [Releases](https://github.com/dinushay/FoliaChallenges/releases) page.
2. Place the JAR file in your server's `plugins` folder.
3. Restart the server. The plugin will generate default configuration files.
4. Configure `messages.yml`, `items-blacklist.yml`, and `config.yml` as needed.

### Requirements

- **Server**: Folia 1.21.x (or compatible Paper-based server)
- **Java**: 17 or higher

## Configuration

The plugin generates the following configuration files in `plugins/FoliaChallenges/`:

### messages.yml
Contains all user-facing messages. Customize colors, text, and placeholders (e.g., `%player%`, `%item%`, `%time%`).

### items-blacklist.yml
List of items to exclude from random assignment. Add items under `blacklisted-items`:
```yaml
blacklisted-items:
  - DIAMOND_SWORD
  - TNT
```

### config.yml
General settings, including the list of worlds to delete on startup (`worlds-to-delete`).

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
| `/reset confirm` | Reset the world (irreversible!) | `foliachallenges.admin` |

### Permissions

- `foliachallenges.admin`: Required for all administrative commands. Defaults to OP or players with this permission.

## How It Works

1. **Timer**: Use `/timer start` to begin a challenge. Players receive random items at intervals.
2. **Items**: Items are assigned from the pool of all valid Minecraft items, excluding blacklisted ones.
3. **Reset**: `/reset confirm` kicks all players, changes the world seed, and marks the old world for deletion on the next restart.
4. **Blacklist**: Items in `items-blacklist.yml` or the hardcoded list are never assigned.

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/dinushay/FoliaChallenges.git
   cd FoliaChallenges
   ```

2. Build with Gradle:
   ```bash
   ./gradlew build
   ```

3. The JAR will be in `app/build/libs/`.

## Contributing

Feel free to submit issues or pull requests. Ensure code follows the existing style and includes tests where applicable.

## License

This project is licensed under the MIT License. See `LICENSE` for details.

## Support

For issues or questions, open an issue on GitHub or contact the maintainer.