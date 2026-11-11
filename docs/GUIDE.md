# Base Hunting Client — full guide

This is the complete manual for the addon: what every module does, how the pieces fit together, how
to configure them for an anarchy server, and how to read the data it stores.

If you only read one section, read [Quick start](#quick-start) and
[How the automation decides things](#how-the-automation-decides-things).

## Contents

1. [What this is](#what-this-is)
2. [Install](#install)
3. [Quick start](#quick-start)
4. [The modules](#the-modules)
5. [Automatic elytra management](#automatic-elytra-management)
6. [Mending and equipment](#mending-and-equipment)
7. [Ender chest and inventory](#ender-chest-and-inventory)
8. [Storage, sorting and restocking](#storage-sorting-and-restocking)
9. [Exploration and stash detection](#exploration-and-stash-detection)
10. [Survival guardrails](#survival-guardrails)
11. [Player tracking](#player-tracking)
12. [HUD and the management screen](#hud-and-the-management-screen)
13. [How the automation decides things](#how-the-automation-decides-things)
14. [Automation engine, tasks and the brain](#automation-engine-tasks-and-the-brain)
15. [Chat commands and kits](#chat-commands-and-kits)
16. [Discord webhooks](#discord-webhooks)
17. [Commands](#commands)
18. [Data, persistence and backups](#data-persistence-and-backups)
19. [Troubleshooting](#troubleshooting)
20. [Building from source](#building-from-source)

## What this is

A Meteor Client addon for anarchy servers. It is an **autonomous exploration and equipment-management
system**, not a pile of disconnected switches: a base-hunting autopilot that flies itself without
fireworks, watches its own elytra, organises ender chests, watches your survival numbers, and records
everything it finds so the next session starts where the last one ended.

Two autopilots exist and they share the same supporting machinery:

- **Base Hunter (v1)** uses Baritone's elytra pathfinder. It needs Baritone installed and it uses
  fireworks. Use it if you already run Baritone and want point-to-point pathing.
- **Base Hunter V2** flies itself by driving Meteor's ElytraFly in Bounce mode. It needs no
  fireworks, so a trip is limited only by elytra durability, and it swaps in fresh elytras as it goes.
  This is the recommended autopilot.

Both are supported by the same core: a shared event bus, a priority scheduler, persistent databases
and a set of automatic managers (elytra, mending, ender chest, survival).

## Install

1. Install Fabric Loader for Minecraft 1.21.11.
2. Put [Meteor Client](https://meteorclient.com/) for 1.21.11 in your `mods` folder.
3. Optionally add Baritone for Meteor. Only Base Hunter v1 needs it; everything else works without it.
4. Drop `basehunting-2.3.0.jar` into `mods`.
5. Launch the game and look for the **Base Hunting** category in Meteor's module list.

Baritone is declared as a `recommends`, not a `depends`, so the addon loads whether or not it is
present. Every Baritone call is compiled against it but guarded by a runtime availability check in
`BaritoneHelper`; anything that needs pathing reports a clear chat error when Baritone is missing or
unusable instead of crashing the client. Run `/bh baritone status` to see what the addon thinks of your
Baritone install.

## Quick start

1. Enable `base-hunter-v2`. It will validate your elytra situation and refuse to start if you have no
   usable elytra at all.
2. Open the management screen with `/bh gui` (or the module's settings) and press **Start V2**.
3. Watch the **Base Hunting** HUD element for mode, target, elytra durability and flight state.
4. Enable `stash-finder`, `new-chunks` and `player-logger` so discoveries are recorded as you fly.
5. Enable `webhook-notifier` and paste a Discord webhook URL if you want alerts.

Everything the addon does is also reachable from chat under `/basehunt` (alias `/bh`). See
[Commands](#commands).

## The modules

All modules live under the **Base Hunting** category in Meteor's module list.

| Module | Default | Purpose |
| --- | --- | --- |
| `base-hunter` | off | Baritone-powered elytra autopilot (v1). Needs Baritone + fireworks. |
| `base-hunter-v2` | off | Firework-free elytra autopilot. Adaptive targets, any elytra durability. **Recommended.** |
| `new-chunks` | off | Detects chunks generated after world creation — where player activity starts. |
| `stash-finder` | off | Scores chunks on storage-block concentrations and records stashes. |
| `player-logger` | off | Records where players are seen and remembers their logout spots. |
| `auto-log` | off | Disconnects when in danger (low health, nearby player, totem pop, broken elytra). |
| `mending-manager` | off | Repairs damaged Mending gear when XP is actually available. |
| `ender-chest-manager` | off | Opens and organises ender chests using normal client interaction. |
| `survival-manager` | off | Centralised health/hunger/equipment monitoring with safety thresholds. |
| `webhook-notifier` | off | Discord alerts for everything the other modules report. |
| `inventory-manager` | off | Sorts your inventory into a stable category order. |
| `chest-sorter` | off | Sorts containers, deposits/withdraws categories and registers storage. |
| `storage-network` | off | Per-server container directory and travel to a container. |
| `shulker-manager` | off | Fills, empties, sorts and searches shulker boxes. |
| `restock-manager` | off | Tops up travel supplies from your registered storage. |
| `storage-monitor` | off | Runs storage workflows and is the storage status view. |
| `automation-brain` | off | Reacts to events and player facts and queues automation tasks. |
| `chat-commands` | off | Runs addon commands typed in chat behind a configurable prefix. |
| `kit-bot` | off | Claims server kits and tracks their cooldowns per server. |
| `auto-storage` | off | Exact-quantity withdrawals, deposits and restocks as queued tasks. |

## Automatic elytra management

`ElytraManager` is the single owner of elytra replacement — nothing else writes the chest slot. The
`FlightController` decides *when* a swap is needed and asks `ElytraManager` to do it.

What it does for you:

- **Automatic equipping.** If you have no elytra on but a spare in your inventory, one is put on.
- **Automatic deployment.** When a route is active and you are grounded or falling, the flight is
  relaunched. It understands the difference between grounded, falling, swimming, climbing, airborne
  and gliding — it will not try to launch while you are swimming, for example.
- **Automatic redeployment after a landing.** A landing from a glide onto solid ground is relaunched;
  an interruption in mid-air is reported and handled separately.
- **Recovery from a failed launch.** Failed launches are retried with exponential backoff up to
  `max-deploy-attempts`, then reported once (chat + webhook) instead of hammering a doomed takeoff
  forever.
- **Broken-elytra handling.** If the worn elytra breaks mid-air, the best spare is equipped.
- **Dimension-change recovery.** Changing dimension tears the flight down and saves the route target;
  it resumes when you are back.
- **Journey validation.** Before a route starts, the total elytra durability you carry is compared
  against the distance to fly. If it is not enough, the route is refused with a clear reason rather
  than failing somewhere over an ocean.

### Durability decisions

- `elytra-replace-durability` — swap the worn elytra for your best spare once it drops to this much
  remaining durability, while it is still healthy. `0` disables early swaps.
- `elytra-minimum-durability` — the floor below which an elytra is considered unusable. Spares below
  this are never selected.
- The healthiest available spare always wins.

If the worn elytra is spent and there is nothing better to swap to, the addon reports an equipment
problem rather than pretending the flight can continue.

## Mending and equipment

`mending-manager` repairs damaged Mending gear, but only when XP is genuinely available.

- It **detects Mending** on equipped and carried gear.
- It **never assumes XP will appear.** It only moves a damaged item into the offhand when experience
  orbs are actually nearby (`xp-orb-radius`) or when XP bottles are carried and `use-xp-bottles` is
  on. This is the opposite of Meteor's own AutoMend, which shuffles items around regardless.
- It **prioritises the most damaged item** first, and avoids wasting XP on gear that is already full.
- It **restores whatever was in your offhand** when it finishes.
- `repair-elytra` / `repair-armor` / `repair-carried` choose what is eligible; `carried-priority`
  ranks carried gear; `keep-totem` keeps a totem in the offhand and will not displace it.

## Ender chest and inventory

`ender-chest-manager` opens ender chests and organises their contents **using ordinary client
interaction** — it looks at the block, calls `interactBlock`, and moves items with shift-clicks on the
resulting container screen. No packets are forged and no server behaviour is worked around. If a
server refuses to open the chest, the module reports `lastFailure()` rather than pretending it worked.

Key behaviours:

- **Detects ender chests** in loaded surroundings and records their coordinates (`remember-chests`).
- **Navigates to a known chest** with `/bh echest goto`.
- **Opens what you are looking at** with `/bh echest open`.
- **Organises on demand** with `/bh echest organise`, or automatically on open (`auto-organise`).
- **Previews every planned move** in chat before the first click, so an accidental activation cannot
  silently shuffle your inventory.
- **Deposits never touch a category you did not select.** Elytras, totems and other precious items
  stay with you unless you explicitly ask for them to be deposited.

Categories you can deposit or withdraw independently: elytra, armor, tools, fireworks, food, totems,
XP supplies, building blocks, utility items and valuables. Reserves (`reserve-*`) keep a minimum
number of fireworks, food, totems and elytras out of any deposit.

`tick-delay` spaces the moves out so the server can keep up; interrupted operations are abandoned
cleanly rather than retried blindly.

## Storage, sorting and restocking

Six modules — `inventory-manager`, `chest-sorter`, `storage-network`, `shulker-manager`,
`restock-manager` and `storage-monitor` — share one pipeline. The design goal is that a storage action
either happens correctly or is reported as failed with a reason; it never half-happens silently.

### The one-clicker rule

Exactly one piece of code clicks inventory slots: `ContainerInteractionManager`. Every feature plans
its moves as pure data and submits them there. An operation holds the `INVENTORY` lease for its whole
life and only one runs at a time, so two features can never click concurrently. Before the first click
the manager simulates the plan against the live container and records the expected result; after the
clicks it waits for the container to actually match that result before reporting success. A wrong
settled state, a slot that vanished, or a timeout is a failure — not a success. Moves are spaced by
`tick-delay` so a burst of clicks cannot outrun the server, and an operation gives up after a bounded
time instead of clicking forever.

### Inventory Manager

Keeps your inventory in a stable category order. Categories (in the default order): tools, combat,
armour, food, potions, ores, redstone, nether, end, farming, building, shulker, rare, misc.

- `sort-on-activate` sorts once when enabled.
- `sort-after-closing` re-sorts when a container screen closes — the usual moment an inventory is left
  messy.
- `rescan-interval` re-sorts periodically (`0` disables).
- `preset` chooses the category order; `merge-stacks` combines partial stacks first.
- `sort-hotbar`, `hotbar-reserve`, `protect-selected` and `protected-items` decide what is left alone.
  The selected slot, the leftmost `hotbar-reserve` slots and anything on `protected-items` never move,
  and a stack is never split.

`/bh storage sort` does the same thing from chat.

### Chest Sorter

Works with any container screen. When a container opens it records the container into the storage
network (`register-on-open`), and it can sort it, deposit into it and withdraw from it. Nothing moves
unless the matching option is on:

- `sort-on-open` sorts the container's contents by category.
- `deposit-on-open` + `deposit-categories` moves only the listed categories into the container.
- `withdraw-on-open` + `withdraw-categories` pulls only the listed categories out.
- `protected-items` and `preserve-reserve` / `reserve-stacks` keep the precious and survival items back.

A deposit never touches a category you did not select, which is what keeps elytras, totems and shulkers
with you.

### Storage Network

A per-server, per-dimension directory of your containers.

- `/bh storage register` records the container you are looking at (or `chest-sorter`'s
  `register-on-open` records every one you open).
- `/bh storage list` shows each container with its last-observed contents and whether it is in reach.
- `/bh storage goto <id>` travels to a container. With Baritone loaded it walks there
  (`travel-mode` `Walk` or `Elytra`); without Baritone only containers already in reach can be used, and
  the module says so rather than failing silently.

### Shulker Manager

- With a shulker open: `/bh shulker fill`, `empty` or `sort`, or the `fill-on-open` / `empty-on-open`
  settings with their category lists.
- `/bh shulker search <item>` searches your carried boxes without opening them, by reading each box's
  item `CONTAINER` component. A box whose contents the client has never seen is reported as **unknown**,
  never as empty — the search tells you how many boxes it could not inspect.

### Restock Manager

`/bh restock preview` shows what the current `profile` needs (`travel` by default) and what you already
carry; `/bh restock start` finds a registered container that holds a needed item, walks to it if
`travel-to-storage` is on and Baritone is loaded, opens it and pulls the needed stacks. The plan is
built from the registry's observations, so it never promises items that are not in storage you have
seen.

### Storage Monitor

Runs named workflows and is the status view. A workflow is an ordered sequence of steps, each of which
runs on its own module and completes before the next begins. Built-in workflows:

- `quick deposit` — sort inventory, deposit, report.
- `full cleanup` — sort inventory, deposit, restock, report.
- `pre-flight` — organise hotbar, restock travel supplies, sort inventory, report.
- `storage maintenance` — sort container, sort shulker, report.

`/bh workflow list`, `/bh workflow run <name>`, `/bh workflow stop`. `/bh storage status` and the HUD
`show-storage` line read the container count, the current operation and the workflow state from here.

### Categories

The category lists accept the enum name or the display name, case-insensitively, with a few aliases:
`tools`, `weapons`, `armor`/`armour`, `food`, `potions`, `ores`, `redstone`, `nether`, `end`, `farming`,
`building`, `shulker`, `rare`, `misc`. An unrecognised entry is ignored rather than failing the whole
list, so one typo cannot silently disable a deposit.

### Limitations

Everything here is normal client interaction: the player looks at a block, `interactBlock` is called,
and items are moved with slot clicks on the resulting screen. No packets are forged. If a server
restricts a container, or a chest is protected, the operation fails and reports why. If the inventory
fills mid-transfer the operation stops and says so. None of this works with a container you cannot
open, and none of it bypasses server-side permission.

## Exploration and stash detection

### New chunks

`new-chunks` scores each chunk on signals that indicate terrain nobody has visited: inhabited time,
missing light data and flowing liquids. `min-confidence` controls how many signals must agree. Set
`min-distance-from-spawn` to ignore the region near spawn, where everything looks new.

### Stash detection

`stash-finder` counts storage blocks, spawners and shulker boxes as chunks stream in and computes a
**weighted confidence score** from several independent indicators — container counts, container type,
storage density and suspicious block arrangements. A single chest is not a base: an isolated chest
scores low and only a real concentration reaches the confirmed range.

Every discovery is classified:

- `unconfirmed` — seen but below any reporting threshold
- `possible stash` — a couple of containers
- `small stash`, `medium stash`, `large stash`
- `major storage location` — almost certainly an organised base
- `interesting structure` — not storage, but worth a look

Each record stores coordinates, dimension, discovery time, chunk coordinates, confidence score,
detected block counts, last-observed time, associated player sightings and discovery method.

### The exploration engine

Base Hunter V2 is a proper exploration controller, not a straight-line flyer:

- **Grid** — a boustrophedon sweep over a bounded area.
- **Spiral** — expands outward from the centre.
- **Adaptive** — follows new-chunk detections and re-ranks targets as it goes.
- **Heading** — flies a single compass bearing, offsetting later legs sideways to cover a band.
- **Custom** — flies your saved route waypoints.

It remembers where it has been. `ExplorationStore` keeps the set of visited chunks (packed so it
stays small), which is what stops the client re-flying over empty territory it already crossed.
Targets are prioritised by confidence, storage density, distance, whether they were seen before,
nearby player activity and route efficiency. `target-aggression` tunes how far the planner will
travel for a marginal target.

When the current region stops producing discoveries (`expand-when-stalled` after `stall-minutes`),
the engine pushes a frontier waypoint outward (`frontier-step`) and continues, instead of sweeping the
same dead area forever.

### Target prioritisation

Every candidate target is scored and ranked. A revisit penalty demotes locations already discovered,
and `skip-explored-targets` keeps the autopilot off chunks you have already covered. `nearest-first`
switches the tour between nearest-neighbour and score order.

## Survival guardrails

`survival-manager` is the single place that watches your survival numbers and warns you before they
become a problem. It monitors health, hunger, armour durability, elytra durability, totem availability,
food inventory, firework inventory, equipment availability, dimension, flight state and inventory
capacity.

Configurable thresholds (`min-health`, `min-food`, `min-elytra-durability`, `min-spare-elytras`,
`min-totems`, `min-food-items`, `min-fireworks`, `min-free-slots`) trigger warnings, and
`pause-route-on-critical` pauses the route (keeping its target) rather than stopping it, so the route
resumes once the danger passes. `warning-cooldown-seconds` stops a flapping condition from spamming
chat.

This is deliberately integrated with `auto-log` rather than duplicating it: `auto-log` disconnects on
hard danger, `survival-manager` warns and pauses. They do not fight over the player because they act
through the shared scheduler.

## Player tracking

`player-logger` records players as you see them and remembers the exact spot they logged out at.

Each record stores the name, UUID, first- and last-seen timestamps, last coordinates, dimension,
sighting count and logout spot. `PlayerTracker` keeps a bounded log of individual sightings, so you
can view a player's history (`/bh player history <name>`) and see where they have been over time.

Historical positions are kept clearly separate from confirmed current positions: a sighting is a
historical fact with a timestamp, not a claim about where a player is right now.

`expire-hours` (module) and `/bh player expire <hours>` prune old records; `/bh player clear` forgets
the current server's records entirely.

## HUD and the management screen

### HUD

The **Base Hunting** HUD element shows, with individual toggles for each line: server, position,
dimension, flight state, new-chunk count, stash count, player count, v1 destination and distance, v2
mode/target/distance/queue, elytra status and spare count, mending status, firework count, free
inventory slots, survival warning, explored/review chunk counts, current control ownership, storage
status (`show-storage`), carried shulker counts (`show-shulkers`) and the active automation task, queue
depth and kit status (`show-automation`).

### Management screen

`/bh gui` opens the management screen. It has functional controls in these sections:

- **Status** — server, position, elytra, survival; Start / Pause / Resume / Stop.
- **Automation** — engine status, brain / auto-storage / kit toggles, the active and queued task
  counts, and buttons to Claim Kits, Cancel Tasks and list recent reactions.
- **Exploration** — explored and review counts; sweep-pattern buttons; reset explored.
- **Route** — waypoint count; add-here, fly-route, clear-route.
- **Recent activity** — the last few automation events, so you can see what the system just did.
- **Stash database** — recorded stashes with classification and score; clear server.
- **Players** — searchable table of recorded players and their logout spots.
- **Equipment** — elytra status; equip-best button; shortcuts to Mending and Ender Chest settings.
- **Storage & Inventory** — container counts (observed, in reach), the current operation and queue,
  workflow state, free slots and shulker counts; Sort Inventory, Register Container, Run Workflow and
  Restock buttons; shortcuts to the six storage modules and a list of registered containers.
- **Survival** — current issues; enable/disable; threshold settings.
- **Notifications** — webhook status; send-test.
- **Database** — record counts, integrity report (including any records skipped on load) and
  export/import/copy.

Every button calls the same public method the corresponding command does, so the GUI and the command
line can never drift apart.

## How the automation decides things

Modules never call each other directly. There are exactly two coupling paths, and understanding them
explains the whole design.

**Events.** A module publishes what happened — a stash was found, an elytra was replaced, the
inventory is full — onto a shared bus, and any module that cares subscribes. `webhook-notifier` is a
single global subscriber that turns events into Discord alerts, so adding a new event type needs no
new webhook code. If a listener throws, the bus converts the crash into a `SYSTEM_ERROR` event rather
than letting it escape into the tick loop.

**Leases.** Anything that touches the player — movement, rotation, inventory slots, equipment,
interaction — must take a lease from the priority scheduler first. A resource has exactly one owner at
a time, and a higher priority preempts a lower one. This is what stops two automations fighting over
the same controls. Priorities run from critical survival down to background discovery:

1. Critical survival
2. Equipment-failure recovery
3. Flight safety
4. Inventory management
5. Active exploration
6. Target investigation
7. Background discovery

A module that is refused a lease must back off for the tick rather than acting anyway. Leases carry a
short timeout, so a module that stops ticking (disabled, disconnected) releases control on its own
instead of leaving the resource stuck.

**Tasks.** Anything that takes more than a tick is not run inline; it is submitted to the
`AutomationEngine` as a `Task`. See the next section.

## Automation engine, tasks and the brain

### Tasks

Every long-running action — an exact-quantity withdrawal, a restock, a journey — is a **`Task`** on one
priority queue (`TaskQueue`), owned by the `AutomationEngine`. A task carries a name, a category, a
priority, an optional requested/attained quantity, an attempt budget and a state. The engine runs one
task at a time, in priority order, and reports each transition on the event bus (`TASK_QUEUED`,
`TASK_STARTED`, `TASK_COMPLETED`, `TASK_PARTIAL`, `TASK_FAILED`, `TASK_CANCELLED`).

Why it matters:

- **Serialised.** Two tasks can never drive the player at once, and a task holds the control leases its
  work needs.
- **Deduplicated.** Submitting a task whose name and category match one already active or queued is
  refused, so a repeated command cannot stack duplicate work.
- **Recoverable.** A task that fails but still has attempts left is re-queued rather than dropped. A
  task that runs out of attempts stays failed with a reason.
- **Definite.** A task ends completed, partly completed (with the shortfall), failed (with a reason) or
  cancelled — never silently forgotten. A terminal task cannot be resurrected by a late state write.
- **Described by data.** The engine builds a task's runner lazily from a per-category factory
  (`Task.Category` → `Supplier<TaskRunner>`), so the queue holds descriptions, not live objects.

The engine is also the single place the GUI, the commands and the chat router submit work, so all three
entry points share one queue.

### The brain

`automation-brain` is how the modules react to each other without calling each other. It watches a
small set of **facts** from `PlayerFacts` — `health`, `max_health`, `hunger`, `absorption`, `air`,
`xp_level`, `on_ground`, `falling`, `gliding`, `in_water`, `elytra_durability`, `elytra_durability_pct`,
`spare_elytra`, `free_slots`, `inventory_used_pct`, `totems`, `nearby_players`,
`nearest_player_distance`, `task_queue` — and evaluates a set of `Rule`s against them each tick, plus
on subscribed events.

A rule fires on a **rising edge** only: it must be false, then true, to fire. That, plus a per-rule
cooldown, is what stops a condition that stays true from spamming an action. A rule that is still true
when its cooldown expires fires then, rather than losing the edge. Each rule's action either calls an
existing module's public method (pause exploration, replace the elytra) or submits a task; it never
takes a private shortcut into another module's internals.

The built-in reactions are:

- **replace-elytra** — when `elytra_durability` is critically low, ask `ElytraManager` for a swap.
- **inventory-full** — when `free_slots < 1`, pause exploration and report it.
- **low-health** — when `health < 8`, stop exploration and report it.
- **player-nearby** — note a `PLAYER_SIGHTED` event.
- **inventory-freed** — observe `TASK_COMPLETED` so a pause can be lifted.

See the reactions with `/bh automation reactions`, and the queue with `/bh tasks`.

## Chat commands and kits

### Chat commands

`chat-commands` lets you run addon commands straight from Minecraft chat. The prefix list defaults to
`!` and `.bh`, so `!help`, `.bh tasks` and `!kits` all work.

The safety property is *where the line came from*. `ChatRouter` only ever parses a line whose origin is
`LOCAL_PLAYER` — a message received from another player is data and is never executed, and the addon's
own output is ignored. On top of that, `ChatPermissions` refuses any command above `READ_ONLY` unless
you have allowed it: a destructive command (one that moves the player or changes control) needs its
name in `allowed-destructive`, or `allow-all-destructive` on. That is what stops a mistyped `!withdraw
all` from emptying a chest by accident.

`intercept-chat` (default on) cancels the message before the server sees it, so the server never
receives the prefixed line. Turn it off if the server has its own commands behind the same prefix and
you want the line to go through as well.

The available chat commands are `help`, `status`, `whoami`, `tasks`, `elytra`, `kits`, `stash`,
`storage`, `withdraw`, `deposit`, `kit-claim` and `cancel`. The same commands are reachable from
`/bh chat run <line>`.

### Kits

`kit-bot` claims server kits for you. Define each kit in `kits` as `name:command:cooldown` — for
example `travel:/kit travel:3600`. A missing command defaults to `/kit <name>`, and a missing cooldown
defaults to an hour.

The server owns the real cooldown; the client only remembers what it last saw, so a restart does not
make it spam a claim the server will reject. A cooldown is tracked per server, because two servers
rarely share a kit layout. When the server replies that a kit is on cooldown, `KitBot` adopts that
cooldown rather than ignoring it.

`claim-on-join` claims every ready kit shortly after you join (`join-delay`); `recheck-cooldown` claims
a kit as soon as its cooldown elapses. `/bh kit list` shows each kit and its remaining cooldown, `/bh
kit claim <name>` claims one and `/bh kit all` claims every ready kit. Kit state is persisted by
`KitBotStore`, so cooldowns survive a restart.

## Discord webhooks

Enable `webhook-notifier` and paste a Discord webhook URL into `webhook-url` (there is one per module
that can notify). Test it with `/bh webhook test`.

Notifications are available for: new stash discoveries, major storage discoveries, interesting
structures, new player sightings, exploration milestones, elytra replacement, mending status,
equipment problems, route completion, route interruption, ender chest operations, emergency
conditions, system errors, **queued/finished automation tasks**, **kit claims** and **chat
commands**. Coordinates, dimension, timestamps and confidence scores are included where relevant.

Each category has its own on/off switch in the `Automation` settings group — including `notify-tasks`
and `notify-chat-commands` for the automation events — and there is a per-type cooldown
(`event-cooldown-seconds`) so a flapping condition cannot flood the channel. Sending is asynchronous on
a single daemon thread with a bounded queue, and duplicate messages are collapsed within a short
window.

Per-module webhook options: `webhook-enabled`, `webhook-url`, `webhook-username`,
`mention-everyone`, `mention-role-id`, `only-on-servers` / `server-whitelist`.

**Treat the webhook URL like a password** — it is stored in plain text in your Meteor config folder.

## Commands

All commands are under `/basehunt` (alias `/bh`). Running `/bh` with no argument prints status.

### Control

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

### Exploration

| Command | What it does |
| --- | --- |
| `/bh explore` or `/bh explore status` | Explored chunk count, review count, route waypoints, V2 state |
| `/bh explore grid` | Start V2 in grid mode |
| `/bh explore spiral` | Start V2 in spiral mode |
| `/bh explore adaptive` | Start V2 in adaptive mode |
| `/bh explore heading` | Start V2 in heading mode |
| `/bh v2` | V2 status, elytra, current target, queue, blocks flown |
| `/bh v2 start` / `stop` / `pause` / `resume` | V2 lifecycle |
| `/bh v2 refresh` | Rebuild the target queue from current detections |

### Stashes and players

| Command | What it does |
| --- | --- |
| `/bh stash list` | List stashes recorded for this server |
| `/bh stash nearby` | Stashes within 2048 blocks, with distance and score |
| `/bh stash search <query>` | Search by classification or block counts |
| `/bh stash export` | Export the database to a file |
| `/bh stash clear` | Forget this server's stashes |
| `/bh player list` | Players seen on this server and their logout spots |
| `/bh player search <query>` | Case-insensitive name search |
| `/bh player history <query>` | Recent sightings for the first match |
| `/bh player expire <hours>` | Prune records older than N hours |
| `/bh player clear` | Forget this server's players |

### Equipment, inventory and routes

| Command | What it does |
| --- | --- |
| `/bh elytra status` | Elytra, spare count and best spare durability |
| `/bh echest status` | Ender chest manager state and recorded chests |
| `/bh echest open` | Open the ender chest you are looking at |
| `/bh echest organise` | Plan and apply deposits/withdrawals on the open chest |
| `/bh echest goto` | Navigate to the nearest recorded ender chest |
| `/bh echest list` | List recorded ender chests |
| `/bh inventory status` | Free inventory slots and survival status |
| `/bh storage status` | Containers, operation state, queue and workflow state |
| `/bh storage list` | List registered containers with contents and reachability |
| `/bh storage register` | Register the container you are looking at |
| `/bh storage goto <id>` | Travel to a registered container |
| `/bh storage sort` | Sort your inventory into category order |
| `/bh storage deposit` / `withdraw` | Deposit/withdraw selected categories (needs a container open) |
| `/bh storage clear` | Forget this server's containers |
| `/bh shulker status` | Carried shulker boxes and their known contents |
| `/bh shulker search <item>` | Search carried boxes without opening them |
| `/bh shulker fill` / `empty` / `sort` | Work on an open shulker box |
| `/bh workflow status` / `list` / `run <name>` / `stop` | Storage workflows |
| `/bh restock preview` / `start` / `stop` | Travel-supply restocking |
| `/bh route list` | List route waypoints |
| `/bh route add` | Add your current position as a waypoint |
| `/bh route add-at <x> <z>` | Add a specific position as a waypoint |
| `/bh route clear` | Remove all waypoints |

### Database and webhooks

| Command | What it does |
| --- | --- |
| `/bh database status` | Record counts and integrity (records skipped on load) |
| `/bh database export` | Export every store to a file |
| `/bh database import` | Import an export file |
| `/bh database copy` / `paste` | Export/import through the clipboard |
| `/bh webhook test` | Send a test message to the configured webhook |

### Automation, tasks, kits and chat

| Command | What it does |
| --- | --- |
| `/bh automation` / `automation status` | Automation engine and brain status |
| `/bh automation start` / `stop` | Enable / disable the automation brain |
| `/bh automation reactions` | The most recent rule reactions |
| `/bh tasks` / `tasks status` | The active and queued automation tasks |
| `/bh withdraw <item> [amount]` | Withdraw an exact quantity as a queued task |
| `/bh deposit <item> [amount]` | Deposit an exact quantity as a queued task |
| `/bh kit status` / `list` | Kits and their remaining cooldowns |
| `/bh kit claim <name>` | Claim one kit |
| `/bh kit all` | Claim every ready kit |
| `/bh chat` | List the chat commands |
| `/bh chat run <line>` | Run a chat command line as if you had typed it |

## Data, persistence and backups

State is stored per server address, so travelling between anarchy servers never mixes coordinates up.
Seven stores persist to NBT under Meteor's config folder:

| Store | File | Contents |
| --- | --- | --- |
| `StashManager` | `base-hunting-stashes.nbt` | Every stash/base/structure record |
| `PlayerTracker` | `base-hunting-players.nbt` | Player entries and their sightings |
| `EnderChestStore` | `base-hunting-enderchests.nbt` | Recorded ender chest locations |
| `ExplorationStore` | `base-hunting-exploration.nbt` | Visited chunks and route waypoints |
| `StorageRegistry` | `base-hunting-storage.nbt` | Registered containers and their last contents |
| `WorkflowManager` | `base-hunting-workflows.nbt` | Custom storage workflow definitions |
| `KitBotStore` | `base-hunting-kits.nbt` | Kit definitions and their last-claim cooldowns |

All seven are versioned. Each store reads its records tolerantly: **a single corrupted record is
skipped, never fatal** — one bad entry cannot cost you the rest of the database. The number of records
skipped on the last load is reported by `/bh database status` and in the management screen's Database
section, and logged at startup. Meteor itself also writes a timestamped `.backup.nbt` if a whole file
fails to parse.

In-memory growth is bounded: the chunk scanners cap their tracked-chunk sets (200k keys, oldest-first
eviction) and the player tracker caps its per-server entries and sightings, so a very long session
cannot grow memory — or the saved file — without limit.

`/bh database export` writes every store into one file; `/bh database import` applies each section it
finds and leaves stores whose section is missing untouched, so an older or partial export cannot wipe
data it does not describe. Each store is applied in its own try/catch, so one malformed section cannot
abort the whole import. The section list is pinned by a headless test, so a store added later cannot
be left out of export/import unnoticed.

## Troubleshooting

**"Baritone is not usable: ..."** — Base Hunter v1 needs Baritone. Use Base Hunter V2, which does not.
If Baritone is installed but reported unusable, its bundled elytra pathfinder (a nested jar) failed to
load; run `/bh baritone status` for the reason.

**"Meteor's Elytra Fly module is unavailable, cannot fly."** — ElytraFly was renamed or removed in your
Meteor build. V2 cannot fly without it.

**The route will not start.** — Usually no usable elytra. Run `/bh elytra status`; the journey validator
refuses to start if the total elytra durability you carry cannot cover the distance.

**The client takes off, lands, and stops.** — Check the launch retry limit (`max-deploy-attempts`). The
controller gives up after a bounded number of failures and reports why rather than looping forever.

**The ender chest "did not open".** — The server refused it (protected chest, plugin, no permission).
The module reports the failure rather than pretending. Nothing was moved.

**Mending is not repairing.** — There is no XP source. `MendingManager` only acts when experience orbs
are nearby or you carry XP bottles with `use-xp-bottles` on.

**A stash was not recorded.** — Its score was below `min-score` / `min-storage-count`, or it was inside
`min-distance-from-spawn`. Lower the thresholds for that server.

**`/bh database status` reports skipped records.** — The file was partly corrupt; the readable records
loaded and the rest were dropped. Import a good export or a Meteor backup to restore them.

**The client crashes or hangs on launch in a headless test.** — That is a test-environment issue, not
the addon. See the build notes in `AGENTS.md` about Xvfb and software GL.

## Building from source

Requires JDK 21.

```bash
./gradlew build          # jar -> build/libs/basehunting-2.3.0.jar
./gradlew compileJava    # fast syntax/type check
./gradlew verifyAddon    # headless logic + API contract tests (no display needed)
./gradlew runClient      # dev client with Meteor + addon loaded
```

`verifyAddon` is the fast pre-flight check and is wired into `check`, so `./gradlew build` runs it too.
It runs seven headless suites: `plannerTest` (flight-planner maths), `coreLogicTest` (elytra,
deployment — including the bounded-retry `DeploymentGovernor` regression — mending, inventory, stash
scoring, chunk dedup, target ranking, persistence recovery, scheduler and event bus), `storageLogicTest`,
`automationLogicTest`, `hardeningTest` (bounded collections, embed dedupe, database coverage),
`apiContractTest` (asserts the Meteor/Minecraft members the addon drives still exist) and
`baritoneContractTest` (asserts the Baritone members the addon drives still exist).

See `AGENTS.md` for the architecture and the development constraints, including how to run the client
headlessly.

## License

See `LICENSE`.
