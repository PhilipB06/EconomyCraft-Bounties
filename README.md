# EconomyCraft Bounties

A bounty and PvP payout addon for EconomyCraft.
Requires EconomyCraft 1.10+.

---

## Commands

| Command                         | Description                                                            |
|---------------------------------|------------------------------------------------------------------------|
| `/bounty`                       | Opens the bounty board with the 10 largest bounties.                   |
| `/bounty add <player> <amount>` | Places money on a player's head.                                       |
| `/bounty info [player]`         | Shows a bounty's total and its contributors.                           |
| `/bounty config`                | Opens the settings menu. Also reachable from the board's Admin button. |
| `/bounty config <key> <value>`  | Changes one setting directly.                                          |

Multiple players can contribute to the same target. Killing a player pays out their full bounty and clears it.

Separately, every PvP kill can take a share of the victim's balance for the killer, per `pvp_balance_loss_percentage` below.

---

## Permissions

Any admin node not set by a permission plugin falls back to OP; any command node not set falls back to allowed for everyone.

| Node                                   | Grants                                             |
|----------------------------------------|----------------------------------------------------|
| `economycraft_bounties.admin`          | All admin nodes                                    |
| `economycraft_bounties.admin.config`   | `/bounty config` and the Admin button on the board |
| `economycraft_bounties.command.bounty` | `/bounty`                                          |
| `economycraft_bounties.command.add`    | `/bounty add`                                      |
| `economycraft_bounties.command.info`   | `/bounty info`                                     |

---

## Config

Stored in `config/economycraft_bounties/config.json` on a server, or `saves/<world>/economycraft_bounties/config.json` in singleplayer. Every setting can also be changed in-game with `/bounty config`, no restart needed.

| Key                           | Default | Description                                                                           |
|-------------------------------|---------|---------------------------------------------------------------------------------------|
| `minimum_bounty`              | `1`     | Smallest amount a single `/bounty add` can place.                                     |
| `allow_self_bounties`         | `true`  | Whether a player can place a bounty on themselves.                                    |
| `announce_bounty_placement`   | `true`  | Broadcast a message when a bounty is placed.                                          |
| `announce_bounty_claim`       | `true`  | Broadcast a message when a bounty is claimed.                                         |
| `pvp_balance_loss_percentage` | `0.10`  | Share of a balance the killer takes on any PvP death, bounty or not. `0` disables it. |

`pvp_balance_loss_percentage` used to be an EconomyCraft setting; on first start it's moved here automatically. It's a `0`-`1` fraction, but values up to `100` are read as a percentage (`25` becomes `0.25`). Higher or negative values disable it.

---
