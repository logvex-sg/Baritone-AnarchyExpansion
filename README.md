# Base Hunting Client

An autonomous base-hunting and equipment-management addon for Meteor Client, built for anarchy
servers. A firework-free elytra autopilot that manages its own elytra, flies itself, remembers every
place it has explored, finds stashes, tracks players, organises ender chests, watches your survival
numbers and pushes all of it to Discord.

Built for Minecraft 1.21.11 (Fabric). **Full manual: [docs/GUIDE.md](docs/GUIDE.md).**

## What it does

- **Base Hunter V2** — the recommended autopilot. Flies itself by recasting the elytra, so a trip is
  limited only by elytra durability, not by rockets. Grid, spiral, adaptive, heading and custom-route
  exploration, terrain following, target prioritisation and a frontier push when the current region
  goes quiet. Any durability of elytra works; spares are swapped in automatically.
- **Base Hunter (v1)** — the original Baritone-powered elytra pathfinder, for when you already run
  Baritone and want point-to-point pathing with fireworks.
- **Automatic elytra management** — equipping, deployment, redeployment after landing, recovery from a
  failed launch, broken-elytra handling, dimension-change recovery and journey validation.
- **Mending manager** — repairs damaged Mending gear, but only when XP is genuinely available.
- **Ender chest manager** — detects, navigates to and organises ender chests using normal client
  interaction, with a preview of every planned move and category-based deposits/withdrawals.
- **New Chunks** — scores chunks on inhabited time, missing light data and flowing liquids to find
  terrain nobody has ever visited.
- **Stash Finder** — weighted multi-indicator scoring of storage concentrations, with classification
  from "possible stash" up to "major storage location".
- **Player Logger** — records where players are seen and remembers the exact spot they logged out at,
  with a searchable history.
- **Survival Manager** — centralised health/hunger/equipment monitoring with configurable thresholds
  that warn, and pause the route rather than stopping it.
- **Auto Log** — disconnects on low health, a nearby player, a totem pop, a breaking elytra or an
  empty firework supply.
- **Webhook Notifier** — Discord alerts for everything the system does, with per-category filtering
  and cooldowns, @everyone / role pings and per-server filtering.
- **Inventory Manager** — keeps your inventory in a stable category order using real slot clicks, and
  re-sorts it when you close a container.
- **Chest Sorter** — sorts any container, deposits and withdraws selected categories, and registers
  every container you open into the storage network.
- **Storage Network** — a per-server directory of your containers with contents, and travel to one on
  request using Baritone (optional).
- **Shulker Manager** — fills, empties and sorts shulker boxes, and searches a carried box's contents
  without opening it.
- **Restock Manager** — tops up travel supplies from your registered storage before a trip.
- **Storage Monitor** — runs named storage workflows ("sort, deposit, restock, report") and is the
  single status view for the whole storage system.

The whole system is coordinated through a shared event bus and a priority scheduler, so no two
automations ever fight over the player. See [How the automation decides
things](docs/GUIDE.md#how-the-automation-decides-things).

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Put [Meteor Client](https://meteorclient.com/) for 1.21.11 in your `mods` folder.
3. Optionally add [Baritone for Meteor](https://meteorclient.com/) — only Base Hunter v1 needs it.
4. Drop `basehunting-2.0.0.jar` into `mods`.
5. Launch the game and look for the **Base Hunting** category in Meteor's GUI.

Baritone is a `recommends`, not a `depends`, so the addon still loads if you skip it. `baritone` is
resolved reflectively at runtime; commands that need pathing report a clear error instead of crashing.

## Quick start

```
/bh gui          # open the management screen
/bh start        # start Base Hunter V2 in adaptive mode
/bh status       # server, dimension, position, counts, autopilot state
/bh events       # what the automation just did
```

Then enable `stash-finder`, `new-chunks` and `player-logger` so discoveries are recorded as you fly,
and `webhook-notifier` if you want Discord alerts.

## Commands

All commands are under `/basehunt` (alias `/bh`). Running `/bh` with no argument prints status.

| Command | What it does |
| --- | --- |
| `/bh` or `/bh status` | Server, dimension, position, Baritone, chunk/stash/player counts, autopilot state |
| `/bh gui` | Open the management screen |
| `/bh events` | Show the most recent automation events |
| `/bh start` | Enable and start Base Hunter V2 in adaptive mode |
| `/bh stop` | Stop the autopilot, or cancel Baritone pathing |
| `/bh pause` / `/bh resume` | Pause / resume the active route |
| `/bh fly <x> <z>` | Enable Base Hunter v1 and start a route to those coordinates |
| `/bh explore grid` / `spiral` / `adaptive` / `heading` | Start V2 in that sweep mode |
| `/bh explore status` | Explored chunks, review queue, waypoints, V2 state |
| `/bh v2` / `v2 start` / `v2 stop` / `v2 refresh` | Base Hunter V2 status and lifecycle |
| `/bh stash list` / `nearby` / `search <q>` / `export` / `clear` | Stash database |
| `/bh player list` / `search <q>` / `history <q>` / `expire <h>` / `clear` | Player records |
| `/bh elytra status` | Elytra, spare count and best spare durability |
| `/bh echest status` / `open` / `organise` / `goto` / `list` | Ender chest management |
| `/bh inventory status` | Free inventory slots and survival status |
| `/bh storage status` / `list` / `register` / `goto <id>` / `sort` / `deposit` / `withdraw` / `clear` | Storage network: register the container you are looking at, travel to one, sort, deposit, withdraw |
| `/bh shulker status` / `search <item>` / `fill` / `empty` / `sort` | Shulker box management (fill/empty/sort need a shulker open) |
| `/bh workflow status` / `list` / `run <name>` / `stop` | Named storage workflows |
| `/bh restock preview` / `start` / `stop` | Travel-supply restocking from registered storage |
| `/bh route list` / `add` / `add-at <x> <z>` / `clear` | Route waypoints |
| `/bh database status` / `export` / `import` / `copy` / `paste` | Persistence and backups |
| `/bh webhook test` | Send a test message to the configured webhook |

## Webhook setup

1. In Discord: **Server Settings → Integrations → Webhooks → New Webhook**, then copy the URL.
2. Enable the `webhook-notifier` module in Meteor.
3. Paste the URL into `webhook-url`.
4. Run `/bh webhook test` to confirm it works.

Each module that can notify has its own `Webhook` settings group. The common options are:

- `webhook-enabled` — turn notifications on for that module.
- `webhook-url` — the Discord webhook URL. **Stored in plain text** in your Meteor config folder, so
  treat it like a password and do not share your config folder.
- `webhook-username` — display name override.
- `mention-everyone` — prepend `@everyone`.
- `mention-role-id` — ping a specific role by ID.
- `only-on-servers` / `server-whitelist` — restrict sending to servers whose address contains one of
  the listed strings.

Mentions are applied to embed messages as well as plain text, so `@everyone` and role pings work for
every alert type.

## Modules

All under the **Base Hunting** category in Meteor's module list.

| Module | Default | Purpose |
| --- | --- | --- |
| `base-hunter` | off | Baritone-powered elytra autopilot |
| `base-hunter-v2` | off | Firework-free elytra autopilot; adaptive targets, any elytra durability |
| `new-chunks` | off | Detects chunks generated after world creation |
| `stash-finder` | off | Counts storage containers to find stashes |
| `player-logger` | off | Records player positions and logout spots |
| `auto-log` | off | Disconnects when in danger |
| `mending-manager` | off | Repairs damaged Mending gear when XP is available |
| `ender-chest-manager` | off | Opens and organises ender chests |
| `survival-manager` | off | Health/hunger/equipment monitoring and safety thresholds |
| `webhook-notifier` | off | Discord alerts for session and automation events |
| `inventory-manager` | off | Sorts your inventory into a category order |
| `chest-sorter` | off | Sorts containers, deposits/withdraws categories, registers storage |
| `storage-network` | off | Container directory and travel to a container |
| `shulker-manager` | off | Fills, empties, sorts and searches shulker boxes |
| `restock-manager` | off | Tops up travel supplies from storage |
| `storage-monitor` | off | Runs storage workflows and reports storage status |

There is also a **Base Hunting** HUD element with individual toggles for server, position, dimension,
flight state, new-chunk/stash/player counts, autopilot target and distance, elytra durability, mending
status, fireworks, free slots, survival warning, exploration progress and control ownership.

## Configuration notes

Everything is configurable from Meteor's module screen; there is no separate config file. A few
settings are worth understanding:

- **Base Hunter V2 → explore-mode** — `Grid` covers a bounded area methodically, `Spiral` expands
  outward from the centre, `Adaptive` follows new-chunk detections, `Heading` flies a bearing, and
  `Custom` flies your saved route waypoints.
- **Base Hunter V2 → elytra-replace-durability** — swaps in your best spare elytra when the worn one
  drops to this much durability. `0` disables. This is what makes any durability of elytra usable.
- **Base Hunter V2 → elytra-minimum-durability** — the floor below which an elytra is never used.
- **Base Hunter V2 → max-deploy-attempts** — how many times a failed launch is retried before the
  controller gives up and reports why, instead of looping forever.
- **Base Hunter V2 → cruise-y / terrain-follow / clearance** — preferred altitude, whether to raise it
  over higher ground, and how much clearance to keep.
- **Base Hunter V2 → cluster-radius / interest-radius** — how close new chunks must be to count as one
  target, and how near a known stash or logout spot earns a target a priority bonus.
- **Base Hunter V2 → frontier-after-seconds / frontier-step** — how long to hunt without finding
  anything before pushing a waypoint outward, and how far out to push. `0` disables the push.
- **Base Hunter V2 → target-aggression** — how far the planner will travel for a marginal target.
- **New Chunks → min-confidence** — how many of the three signals must agree before a chunk is
  reported. Raise it if you get false positives.
- **New Chunks → min-distance-from-spawn** — ignores chunks near spawn, where everything looks new.
- **Stash Finder → min-score** — the threshold for recording a chunk as a stash. Tune per server.
- **Mending Manager → use-xp-bottles / xp-orb-radius** — Mending only acts when XP is genuinely
  available; it never assumes XP will appear.
- **Ender Chest Manager → deposit-* / withdraw-* / reserve-*** — which categories may move, and how
  much is kept back. Deposits never touch a category you did not select.
- **Survival Manager → min-*** — thresholds that trigger warnings and (optionally) pause the route.
- **Player Logger → expire-hours** — how long logout spots are remembered.
- **Auto Log → player-names** — only disconnect for specific players. Empty means anyone.
- **Inventory Manager → preset / sort-hotbar / hotbar-reserve / protected-items** — the category order to
  sort into, whether the hotbar is included, how many hotbar slots to leave alone, and item ids that must
  never move.
- **Chest Sorter → deposit-categories / withdraw-categories / sort-on-open** — which categories may move
  when a container opens, and whether it sorts automatically. Nothing moves unless the matching
  `*-on-open` option is on.
- **Storage Network → travel-mode / arrival-distance** — walk or fly to a container, and how close to get.
- **Shulker Manager → fill-categories / empty-categories** — which categories move into or out of an open
  shulker.
- **Restock Manager → profile / travel-to-storage** — which restock profile to satisfy and whether to
  walk to storage that is out of reach.
- **Storage Monitor → workflow / run-on-activate / step-delay** — which workflow runs and how long to
  wait between steps.

## Storage and inventory

Six modules share one safe pipeline. Every storage action is planned by pure logic, queued to a single
clicker, and only reported as done once the live container actually matches the planned result.

1. **Register storage.** Look at a chest and run `/bh storage register` (or enable `chest-sorter` with
   `register-on-open`, which records every container you open). `/bh storage list` shows what is known,
   with its contents and whether it is in reach.
2. **Sort.** `/bh storage sort` (or `inventory-manager` with `sort-on-activate`) orders your inventory by
   category; `chest-sorter` sorts a container. The hotbar reserve, the selected slot and anything on the
   `protected-items` list are never touched, and stacks are never split.
3. **Deposit / withdraw.** With a container open, `/bh storage deposit` and `/bh storage withdraw` move
   only the categories you selected. `/bh storage deposit` keeps the `reserve-stacks` of food and potions
   back. A preview of every planned move is logged before the first click.
4. **Restock.** `/bh restock preview` shows what is missing for the `travel` profile; `/bh restock start`
   walks to a container that holds it (if `travel-to-storage` is on and Baritone is loaded) and pulls it.
5. **Shulkers.** With a shulker open, `/bh shulker fill`, `empty` or `sort`. `/bh shulker search <item>`
   searches carried boxes without opening them — a box whose contents have never been seen is reported as
   unknown, never as empty.
6. **Workflows.** `/bh workflow list` shows the built-in workflows (`quick deposit`, `full cleanup`,
   `pre-flight`, `storage maintenance`); `/bh workflow run <name>` runs one, sequencing each step behind
   the last.

Nothing clicks a slot unless a module is enabled and you asked it to act. The single clicker
(`ContainerInteractionManager`) holds the inventory for the whole of an operation, so two features can
never click at once, and a failed or timed-out operation is reported as a failure with a reason rather
than pretending it worked.

## Persistence

Six stores persist per server address under Meteor's config folder: stashes, players, ender chests,
exploration (visited chunks + route waypoints), the storage registry and storage workflows. Each is
versioned and reads its records tolerantly, so a single corrupted record is skipped rather than losing
the whole database — the skipped count is reported by `/bh database status`. `/bh database export`
writes every store to one file; import applies each section it finds and never wipes a store whose
section is missing.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). The current release is **2.0.0** — the storage release.

## Building from source

Requires JDK 21.

```bash
./gradlew build          # jar -> build/libs/basehunting-2.0.0.jar
./gradlew verifyAddon    # headless logic + API contract tests (no display needed)
./gradlew runClient      # dev client with Meteor + addon loaded
```

`verifyAddon` is wired into `check`, so `./gradlew build` runs it too. See [AGENTS.md](AGENTS.md) for
the architecture and how to run the client headlessly.

## Project layout

```
src/main/java/dev/basehunt/bhclient/
├── BaseHuntingAddon.java        addon entrypoint, registers everything
├── commands/BaseHuntCommand.java
├── gui/BaseHuntingScreen.java   management screen (/bh gui)
├── hud/BaseHuntingHud.java
├── core/                        shared runtime coordination
│   ├── AutomationManager / AutomationBus / AutomationEvent
│   ├── TaskScheduler / Priority / ControlResource / Lease
│   └── logic/                   pure, headlessly testable decision logic
├── modules/                     the ten user-facing modules
├── systems/                     StashManager, PlayerTracker, EnderChestStore,
│                                ExplorationStore (persisted state), DatabaseIO
└── utils/                       BaritoneHelper, ElytraController, ElytraManager,
                                 FlightController, EquipmentScanner, FlightPlanner,
                                 WebhookManager, ServerUtils, WebhookSettings

src/test/java/dev/basehunt/bhclient/
├── PlannerTest.java             FlightPlanner maths, no display needed
├── CoreLogicTest.java           pure-logic suite (elytra, scoring, scheduler, bus, recovery)
└── ApiContractTest.java         asserts the Meteor/Minecraft members the addon drives still exist
```

## License

See `LICENSE`.
