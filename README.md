# Baritone Anarchy Expansion

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
- **Automation Brain** — reacts to events and player facts (low health, a filling inventory, a failing
  elytra, a player sighting) and turns them into queued tasks, so no two features fight over the
  player.
- **Chat Commands** — run addon commands from Minecraft chat behind a prefix (`!help`, `.bh tasks`),
  with only locally typed lines ever executed and destructive commands gated behind permissions.
- **Kit Bot** — claims server kits and remembers their cooldowns per server, adopting a server-side
  cooldown instead of spamming a claim it will reject.
- **Auto Storage** — exact-quantity withdrawals, deposits and restocks run as queued tasks, so
  "withdraw exactly 64 ender pearls" either moves 64 or tells you why it could not.

The whole system is coordinated through a shared event bus, a priority scheduler and a single task
engine, so no two automations ever fight over the player. See [How the automation decides
things](docs/GUIDE.md#how-the-automation-decides-things).

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Put [Meteor Client](https://meteorclient.com/) for 1.21.11 in your `mods` folder.
3. Optionally add [Baritone for Meteor](https://meteorclient.com/) — only Base Hunter v1 needs it.
4. Drop `baritone-anarchy-expansion-2.10.0.jar` into `mods`.
5. Launch the game and look for the **Baritone Anarchy Expansion** category in Meteor's GUI.

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
| `/bh baritone` / `status` / `terms` / `stop` | Baritone availability, version and flight state; accept its elytra terms; cancel pathing |
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
| `/bh automation` / `start` / `stop` / `reactions` | Automation brain status, enable, disable, recent reactions |
| `/bh tasks` / `tasks status` | The active and queued automation tasks |
| `/bh withdraw <item> [amount]` / `deposit <item> [amount]` | Exact-quantity transfer, as a queued task |
| `/bh kit status` / `list` / `claim <name>` / `all` | Server kit status and claiming |
| `/bh threat` / `status` / `list` / `retreat` | Scored nearby threats, and arm the panic retreat |
| `/bh objective` / `list` / `next` / `done <id>` / `add <label>` / `clear` | The ranked anarchy to-do list |
| `/bh chat run <line>` | Run a chat command line as if you had typed it |
| `/bh terminal` | Open the management screen, whose Terminal section is at the bottom |
| `/bh terminal run <line>` | Run one terminal command and echo its output to chat |
| `/bh terminal help` | List every terminal command |

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

All under the **Baritone Anarchy Expansion** category in Meteor's module list.

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
| `automation-brain` | off | Reacts to events/facts and queues automation tasks |
| `chat-commands` | off | Runs addon commands typed in chat behind a prefix |
| `kit-bot` | off | Claims server kits and tracks their cooldowns; optional 6b6t kit-maker delivery role |
| `auto-storage` | off | Exact-quantity withdrawals, deposits and restocks as tasks |
| `command-terminal` | off | In-game command terminal over every manager, with Tab completion and history |
| `6b6t-homes` | off | Tracks 6b6t homes and warns before the home limit is reached |
| `6b6t-tpa` | off | Sends, cancels and auto-answers 6b6t teleport requests |
| `6b6t-tps` | off | Polls 6b6t's `/tps` and warns when the server or your link is lagging |
| `6b6t-hotspot` | off | Tracks the current 6b6t Hotspot and can teleport you to it |
| `6b6t-players` | off | Records 6b6t player sightings with a searchable per-server history |
| `baritone-control` | off | Runs Baritone jobs (mine, follow, explore, goto, farm, build, tunnel) and reports them |
| `death-recovery` | off | Records where you died and what you carried, and can fly you back to recover it |
| `player-radar` | off | Tracks nearby players and warns when one is approaching |
| `smooth-look` | off | Turns the camera smoothly toward a target instead of snapping to it |
| `human-behavior` | off | Idles with the small, irregular movements of a real player |
| `behavior-monitor` | off | Scores how human the recent movement and camera look |
| `threat-monitor` | off | Scores nearby players by danger and raises the alarm when it matters |
| `panic-retreat` | off | Retreats to the safest nearby cover when a threat is called |
| `objective-queue` | off | Keeps a ranked to-do list of anarchy errands so loot is not forgotten |

There is also a **Baritone Anarchy Expansion** HUD element with individual toggles for server, position, dimension,
flight state, new-chunk/stash/player counts, autopilot target and distance, elytra durability, mending
status, fireworks, free slots, survival warning, exploration progress, control ownership, the active
automation task, the kit status, the running Baritone job, the nearest player, the nearest
un-recovered death, the look target and the human-likeness score, the worst nearby threat and the
panic-retreat state, the next objective, and the 6b6t lines (server TPS, hotspot, homes and player count).

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
- **Auto Storage → protected-items / allow-partial / travel-to-containers / prefer-nearest** — items
  automation may never move, whether a short transfer succeeds partially, whether to walk to a
  registered container, and whether to choose the nearest one holding the item.
- **Kit Bot → kits / claim-on-join / join-delay / recheck-cooldown / claim-delay** — the kits
  (`name:command:cooldown`), whether to claim on join, how long to wait after joining, whether to
  claim a kit as soon as its cooldown expires, and how long to wait between claims.
- **Chat Commands → prefixes / intercept-chat / allowed-destructive / allow-all-destructive** — the
  chat prefixes, whether the line is cancelled before the server sees it, and which destructive
  commands are allowed.
- **Automation Brain → react-to-elytra / react-to-inventory-full / react-to-low-health /
  react-to-players** — which reactions are enabled.

## Automation engine and tasks

Everything long-running the addon does — a withdrawal, a restock, a journey — is a **task** on one
priority queue, not an inline loop. This is what makes the automation safe to combine: tasks run one
at a time in priority order, a task is deduplicated by name and category, a task that fails but still
has attempts left is re-queued, and every task ends in a definite state (completed, partly completed,
failed with a reason, or cancelled) rather than being forgotten.

Priorities run from critical survival down to background discovery. A task describes its work; the
engine builds the runner lazily from a per-category factory and holds the control leases the work
needs, so two tasks can never click the same slot or steer the same flight.

The **Automation Brain** is how the modules talk to each other without calling each other. It watches
a small set of facts — health, hunger, elytra durability, free slots, totems, nearby players, queue
depth — and a rule fires on a *rising edge* only, subject to a cooldown, so a condition that stays
true does not spam an action. Built-in reactions replace a failing elytra, pause exploration when the
inventory fills or health drops, and note player sightings. Everything a reaction does goes through
the engine or an existing module's public method, never a private shortcut.

Watch it with `/bh automation reactions`, `/bh tasks`, or the **Automation** section of the
management screen.

## Chat commands

Enable `chat-commands` and type a prefixed line in Minecraft chat — `!help`, `.bh tasks`, `!kits`.
Only a line the local player typed is ever executed: a message received from another player is data
and is never parsed, and the addon's own output is ignored. Destructive commands (movement, control)
are refused unless you list them under `allowed-destructive` or turn on `allow-all-destructive`.

The same commands are reachable from `/bh chat run <line>` and from `/bh` directly.

## Terminal

Enable `command-terminal` and open `/bh gui`; the Terminal section sits at the bottom of the screen.
It is a real command line over the same managers the GUI and `/bh` use, so nothing it can do is a
special case. Type a line and press Enter, press Tab to complete a command, subcommand or argument,
and press Up/Down to walk the history.

The command set is grouped:

```
help [command]          status                    modules [list|search|enable|disable]
tasks [list|stop|clear] storage [list|search|withdraw|deposit|register|goto|restock]
navigate <x> <y> <z>    baritone [status|stop]    kit [list|status|claim <name>]
elytra [status|replace] echest [status|open|organise|goto]   player [list|search <q>]
stash [list|nearby|search <q>]   route [list|add|clear|start|stop]
explore [grid|spiral|adaptive|heading|custom|stop|status]
chat [status|prefixes|commands|run <line>]   config [list|get|set]
database [status|export|import]   history [clear]   macro [list|run <name>]   clear   gui
threat [status|list|retreat]   objective [list|next|done <id>|add <label>|clear]
```

Shortcuts are configurable in the module's `aliases` setting (`s=status`, `w=withdraw`, …). Each
command declares its own permission level, and a subcommand can be stricter than its parent — so
`storage list` is read-only while `storage withdraw` is not. Commands that change things ask for a
second Enter before they run. The terminal only ever sees input you type; chat messages from other
players never reach it. `/bh terminal run <line>` runs one command from chat and echoes the output.

Macros are named command sequences, written in the module's `macros` setting as
`name = step ; step ; step` (for example `preflight = elytra status ; storage restock ; survival
status`). `macro list` shows them and `macro run <name>` replays one; every step goes through the same
registry, so permissions and confirmation still apply and a macro can never do more than typing the
steps by hand.

## Kits

Enable `kit-bot` and define your kits in `kit-lines` as `name:command:cooldown` (for example
`travel:/kit travel:3600`). `/bh kit list` shows each kit and its remaining cooldown; `/bh kit claim
<name>` claims one, `/bh kit all` claims every ready kit. A server-side cooldown reply is adopted, so
the client stops trying instead of spamming a claim the server will reject.

The same module can act as a **6b6t kit maker**. On 6b6t, kits are handed out by players rather than by
the server: someone types `!kit pvp` in chat, a delivery bot teleports to them, hands over the items and
kills itself to reset. Turn on `kit-bot → delivery-enabled` to be that bot. The delivery role is off by
default because it teleports you to strangers and then kills you. Configure the kits it can deliver as
**loadouts** (`/bh kit loadout add pvp diamond_sword 1`), and it will refuse to deliver a loadout you are
not carrying unless `require-stock` is off. `/bh kit delivery` shows the queue and the state machine.

## 6b6t server tools

The addon ships five modules for [6b6t](https://www.6b6t.org/en), a no-rules anarchy server whose command
set (`/home`, `/tpa`, `/tps`, `/hotspot`, `/kill`, `/gc`) differs from vanilla. They are useful on any
server but are wired for 6b6t's syntax.

| Module | What it does |
| --- | --- |
| `6b6t-homes` | Keeps a book of your homes, warns before the server's home limit, and sets/visits/deletes them (`/bh 6b6t home set base`). The server never reports the limit, so set `home-limit` to match your rank. |
| `6b6t-tpa` | Sends (`/bh 6b6t tpa send Steve`), cancels and auto-answers teleport requests. Auto-answer is allow-list based so a stranger cannot yank you across the map. |
| `6b6t-tps` | Polls `/tps` and warns when the server drops below a threshold. It reports server *and* personal TPS, because a healthy server with a bad personal TPS means your link is the problem. |
| `6b6t-hotspot` | Tracks the current Hotspot (`/hotspot`), notifies once when one goes live, and can teleport you in. On an anarchy server a Hotspot is a fight, so `auto-join` is off by default. |
| `6b6t-players` | Records every player seen, where and when, with a searchable per-server history (`/bh 6b6t players`). It is bounded and evicting, so a long session cannot grow the file without limit. |

Everything is also reachable from the **6b6t menu**: `/bh 6b6t gui`, or the *Open 6b6t Menu* button in
the Baritone Anarchy Expansion screen. The menu lists the server's command catalogue and gives each module a status
line and its controls. The terminal exposes the same tools as `sixbt status|home|tpa|tps|hotspot|players|server|gui`.

## Anarchy toolkit

Modules that cover the parts of an anarchy session that are not base hunting: gathering and travel
through Baritone, surviving a death with your loot, knowing when someone is closing in, and — just as
important — *moving like a person* so an automated client does not stand out.

### Baritone jobs — `baritone-control`

Baritone already knows how to mine a block, follow a player, explore chunks and build a schematic. What it
lacks is one place to start a job, remember it is running, notice when it ends and report that as an event.
`baritone-control` is that place. Every request is parsed by a strict catalogue first, so a typo is refused
with a reason instead of being handed to Baritone as a job that silently does nothing.

| Command | What it does |
| --- | --- |
| `/bh job mine diamond_ore 64` | Mine 64 diamond ore (a bare `mine diamond_ore` mines until nothing is left) |
| `/bh job goto 100 64 -200` | Walk to an exact block |
| `/bh job follow Steve` | Follow a player until cancelled |
| `/bh job explore 1000 2000` | Run Baritone's chunk explorer around a point |
| `/bh job farm 32` | Harvest crops within 32 blocks |
| `/bh job tunnel` | Dig a straight 1×1 tunnel |
| `/bh job build house.schem 10 70 10` | Build a schematic at an origin |
| `/bh job stop` | Cancel the job and every Baritone process |

The terminal exposes the same as `job status|stop|mine|goto|follow|explore|farm|tunnel|build|run`. Each job
publishes `BARITONE_TASK_STARTED`, `BARITONE_TASK_DONE`, `BARITONE_TASK_FAILED` or `BARITONE_TASK_CANCELLED`
on the automation bus, so a workflow or the automation brain can chain on it. A job that stops moving for
`idle-grace-ticks` without being cancelled is reported as finished; `auto-pickup` also walks over the drops
while mining.

### Death recovery — `death-recovery`

The moment that matters most on an anarchy server is the moment you die, because that is where your loot
is, and vanilla forgets it the instant you respawn. `death-recovery` records the death position, the
dimension and how much you were carrying, persists it per server, warns you in chat, and (with
`auto-recover`) flies you back with Baritone after you respawn. A death is marked recovered once you are
within `recovered-radius` of it, and the list is bounded so a long session cannot grow the file without
limit.

- `/bh death list` — every death recorded on this server, newest first, with its distance.
- `/bh death near` — the nearest un-recovered death in your current dimension.
- `/bh death recover` — fly back to that death.
- `/bh death recover-id 7` — fly back to a specific death.
- `/bh death clear` — forget every death.

The terminal exposes the same as `death list|near|recover|recover-id|clear`. Deaths in another dimension
are not offered as "nearest" — a Nether death is not reachable from the Overworld, so its coordinates would
mean nothing.

### Player radar — `player-radar`

"There is a player 80 blocks away" is far less useful than "a player is closing on you". `player-radar`
tracks every nearby player's distance and reports when one enters your range, when one is *approaching*
(not merely present) and when one leaves, pushing `PLAYER_NEARBY`, `PLAYER_APPROACHING` and
`PLAYER_LEFT_RANGE` onto the automation bus. It only observes — it never attacks anyone.
`/bh radar` and the terminal's `radar` list the live contacts, nearest first.

### Realistic movement — `smooth-look`, `human-behavior`, `behavior-monitor`

A client that snaps its camera to a target and then stands perfectly still is the easiest bot to spot. These
three modules make the movement read as human, and then measure whether it does.

**`smooth-look`** turns the camera the way a wrist turns a mouse. `LookSmoother` approaches a target along
the shortest arc at a bounded per-tick speed, eases in, and settles with a small seeded jitter that fades as
the aim arrives. Yaw takes the short way across the ±180° seam (170° → −170° is 20°, not 340°) and pitch is
clamped to ±90°. It holds the `ROTATION` control lease while turning, so it never fights another module for
the camera.

| Command | What it does |
| --- | --- |
| `/bh look player Steve` | Turn smoothly toward a nearby player |
| `/bh look at 100 70 -200` | Turn smoothly toward a block position |
| `/bh look yaw 90 -20` | Turn smoothly to a raw yaw/pitch |
| `/bh look clear` | Abandon the current look target |
| `/bh look status` | Show the current look target |

**`human-behavior`** adds the small, purposeless movement a person makes while idle: a glance, a tiny
strafe, a jump, a brief sneak, a look down at the hotbar — each short and bounded, separated by random
pauses, never twice the same gesture in a row. It refuses to gesture while another automation owns movement
and takes the lowest priority, so it can never preempt anything that matters. It presses the same keys a
player would and forges no packets.

**`behavior-monitor`** closes the loop. `BehaviorProfiler` watches a rolling window of yaw and position
samples and reduces it to how much the turn rate varies, how often you pause, and how straight the path is,
combined into a 0–100 human-likeness score. A flat 0 ("this looks like a bot") is visible on the HUD and in
`/bh human profile` instead of being taken on faith. It is read-only.

The terminal exposes `look status|player|at|yaw|clear` and `human status|on|off|profile`.

### Threat assessment and panic retreat — `threat-monitor`, `panic-retreat`

A radar that only reports distance is nearly useless on an anarchy server: someone flying straight at you
from 60 blocks is a bigger problem than someone loitering 30 blocks away in leather. **`threat-monitor`**
scores each nearby player with `ThreatAssessment` — distance, how fast they are closing, and what they are
wearing and holding (armour, a weapon, a totem, an elytra) — into a 0–100 score and a level: calm, wary,
danger or flee. The weighting is monotonic and capped, so a bigger danger always scores higher and no single
input turns a quiet moment into a false alarm. Only danger and flee raise the alarm; each player has a
cooldown so the chat is not spammed.

**`panic-retreat`** reacts to that alarm instead of freezing in the open. It samples the blocks around the
player and ranks them with `SafeSpotFinder` — cover, a roof overhead, darkness and nearby water score well;
lava and fire are disqualifying, and a long drop is penalised — then walks (via Baritone) to the best spot
worth using. It takes the `MOVEMENT` lease while retreating and gives up rather than looping when no spot is
good enough. Neither module attacks or builds.

Read it with `/bh threat`, `/bh threat retreat` or the terminal `threat status|list|retreat`.

### Objective queue — `objective-queue`

A stash run is more than the task it was started for. **`objective-queue`** watches for the errands you would
otherwise forget — notable item drops you pass, and the last-seen spot of a tracked player who logged out —
and queues them through `ObjectivePlanner`, which keeps a bounded, de-duplicated list ranked by worth, minus
distance, minus a saturating staleness cost. The list persists per server (`ObjectiveStore`), so it survives
a restart. The queue only records and ranks; `/bh objective next` (or terminal `objective next`) sends the
player to the best one via Baritone, keeping the single-owner rule intact — the queue decides *what* is worth
doing, never *who* drives.

Work it with `/bh objective [list|next|done <id>|add <label>|clear]` or the terminal `objective …`.

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

Twelve stores persist per server address under Meteor's config folder: stashes, players, ender chests,
exploration (visited chunks + route waypoints), the storage registry, storage workflows, kits, kit
loadouts, the 6b6t server record, the 6b6t player log, remembered deaths and the objective queue. Each is
versioned and reads its records tolerantly, so a single corrupted record is skipped rather than losing
the whole database — the skipped count is reported by `/bh database status`. `/bh database export`
writes every store to one file; import applies each section it finds and never wipes a store whose
section is missing. The section list is pinned by a headless test, so a newly added store cannot fall
out of the export/import path unnoticed.

Long sessions are bounded: the chunk scanners cap their tracked-chunk sets (oldest-first eviction) and
the player tracker caps its per-server entries and sightings, so a multi-hour flight cannot grow the
process or the saved file without limit.

## Changelog

See [CHANGELOG.md](CHANGELOG.md). The current release is **2.5.0** — the 6b6t release.

## Building from source

Requires JDK 21.

```bash
./gradlew build          # jar -> build/libs/baritone-anarchy-expansion-2.10.0.jar
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
│   ├── chat/                     chat prefix commands (parsed from local chat only)
│   ├── terminal/                 in-game command terminal (parser, registry, history, aliases)
│   └── logic/                   pure, headlessly testable decision logic
│       ├── DeploymentGovernor   bounded deploy/recover policy (FlightController)
│       ├── BoundedLongSet       size-capped packed-chunk-key set
│       └── ...                  scoring, planning, classification, codecs
├── modules/                     the user-facing modules (hunters, storage, 6b6t, anarchy toolkit)
├── systems/                     StashManager, PlayerTracker, EnderChestStore,
│                                ExplorationStore (persisted state), DatabaseIO
└── utils/                       BaritoneHelper, ElytraController, ElytraManager,
                                 FlightController, EquipmentScanner, FlightPlanner,
                                 WebhookManager, ServerUtils, WebhookSettings

src/test/java/dev/basehunt/bhclient/
├── PlannerTest.java             FlightPlanner maths, no display needed
├── CoreLogicTest.java           pure-logic suite (elytra, scoring, scheduler, bus, recovery)
├── HardeningTest.java           regressions for the stability fixes (bounds, dedupe, DB coverage)
├── TerminalLogicTest.java       terminal parser, history, aliases, registry, permissions, completion
└── ApiContractTest.java         asserts the Meteor/Minecraft members the addon drives still exist
```

## License

See `LICENSE`.
