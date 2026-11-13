# Baritone Anarchy Expansion — full guide

This is the complete manual for the addon: what every module does, how the pieces fit together, how
to configure them for an anarchy server, and how to read the data it stores. It documents release
**2.5.0**.

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
16. [6b6t server tools](#6b6t-server-tools)
17. [Anarchy toolkit](#anarchy-toolkit)
18. [The command terminal](#the-command-terminal)
19. [Discord webhooks](#discord-webhooks)
20. [Commands](#commands)
21. [Data, persistence and backups](#data-persistence-and-backups)
22. [Troubleshooting](#troubleshooting)
23. [Building from source](#building-from-source)

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
4. Drop `baritone-anarchy-expansion-2.9.0.jar` into `mods`.
5. Launch the game and look for the **Baritone Anarchy Expansion** category in Meteor's module list.

Baritone is declared as a `recommends`, not a `depends`, so the addon loads whether or not it is
present. Every Baritone call is compiled against it but guarded by a runtime availability check in
`BaritoneHelper`; anything that needs pathing reports a clear chat error when Baritone is missing or
unusable instead of crashing the client. Run `/bh baritone status` to see what the addon thinks of your
Baritone install.

## Quick start

1. Enable `base-hunter-v2`. It will validate your elytra situation and refuse to start if you have no
   usable elytra at all.
2. Open the management screen with `/bh gui` (or the module's settings) and press **Start V2**.
3. Watch the **Baritone Anarchy Expansion** HUD element for mode, target, elytra durability and flight state.
4. Enable `stash-finder`, `new-chunks` and `player-logger` so discoveries are recorded as you fly.
5. Enable `webhook-notifier` and paste a Discord webhook URL if you want alerts.

Everything the addon does is also reachable from chat under `/basehunt` (alias `/bh`). See
[Commands](#commands).

## The modules

All modules live under the **Baritone Anarchy Expansion** category in Meteor's module list.

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
| `kit-bot` | off | Claims server kits, tracks their cooldowns, and can act as a 6b6t kit maker. |
| `auto-storage` | off | Exact-quantity withdrawals, deposits and restocks as queued tasks. |
| `command-terminal` | off | In-game command terminal over every manager, with Tab completion, history and macros. |
| `6b6t-homes` | off | Tracks 6b6t homes and warns before the server's home limit. |
| `6b6t-tpa` | off | Sends, cancels and auto-answers 6b6t teleport requests. |
| `6b6t-tps` | off | Polls 6b6t's `/tps` and warns when the server or your link is lagging. |
| `6b6t-hotspot` | off | Tracks the current 6b6t Hotspot and can teleport you to it. |
| `6b6t-players` | off | Records 6b6t player sightings with a searchable per-server history. |
| `baritone-control` | off | Runs Baritone jobs (mine, follow, explore, goto, farm, build, tunnel) and reports completion. |
| `death-recovery` | off | Records where you died and what you carried, and can fly you back to recover it. |
| `player-radar` | off | Tracks nearby players and warns when one is approaching. |
| `smooth-look` | off | Turns the camera smoothly toward a target instead of snapping to it. |
| `human-behavior` | off | Idles with the small, irregular movements of a real player. |
| `behavior-monitor` | off | Scores how human the recent movement and camera look. |

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

The **Baritone Anarchy Expansion** HUD element shows, with individual toggles for each line: server, position,
dimension, flight state, new-chunk count, stash count, player count, v1 destination and distance, v2
mode/target/distance/queue, elytra status and spare count, mending status, firework count, free
inventory slots, survival warning, explored/review chunk counts, current control ownership, storage
status (`show-storage`), carried shulker counts (`show-shulkers`) and the active automation task, queue
depth and kit status (`show-automation`), the running Baritone job (`show-job`), the nearest player
(`show-radar`), the nearest un-recovered death (`show-deaths`), the current look target (`show-look`) and
the human-likeness score (`show-human`).

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
- **6b6t** — kit-delivery status and a button to open the **6b6t menu**, which carries a status line
  and the controls for each 6b6t module, a loadout editor, and the server's command catalogue.
- **Terminal** — the in-game command line: scrollable output, an input box with Tab completion and
  history, and Help / Status / Clear / Settings buttons.

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

#### Kit maker (6b6t delivery)

On 6b6t the server does not hand out kits at all — players do. A kit maker watches chat for a request
like `>lxkit pvp`, teleports to the requester with `/tpa`, drops the items, and kills itself to reset.
`kit-bot` can be that bot.

Enable `kit-bot → delivery-enabled`. It is off by default because the role teleports you to a stranger
and then kills you, which nobody should get by accident. Then build the kits it may hand out as
**loadouts**: `/bh kit loadout create pvp`, `/bh kit loadout add pvp diamond_sword 1`, `/bh kit loadout
add pvp golden_apple 8`, or open the 6b6t menu and use *Edit* on a kit — *Add Held Item* takes the item
in your hand, which is the quickest way to record a loadout. Loadouts are unlimited: add as many kits,
and as many item lines per kit, as you like.

##### Who may use it — the whitelist

A kit bot's request prefix is public, so **the bot is closed by default**: nobody is served until you
add them. Manage it from `/bh kit whitelist …` (or `kit whitelist …` in the terminal, or the **Kit
Access** section of the 6b6t menu):

| Command | Effect |
| --- | --- |
| `/bh kit whitelist` / `… list` | Show the allow-list, block-list and open/closed state. |
| `/bh kit whitelist add <player>` | Let a player request kits. |
| `/bh kit whitelist remove <player>` | Remove them from the allow-list. |
| `/bh kit whitelist block <player>` | Always refuse a player, even when open. |
| `/bh kit whitelist unblock <player>` | Remove a block. |
| `/bh kit whitelist open` / `close` | Serve anyone (block-list still applies) / return to whitelist-only. |

The same three settings (`whitelist`, `blacklist`, `allow-anyone`) are on the module, and the menu
exposes them as buttons. A blocked player is refused before the allow-list is consulted, so a griefer
can be shut out without closing the bot.

##### The request prefix

`request-prefixes` defaults to `>lxkit`, so `>lxkit totem` and `>lxkit kit totem` both work (a prefix
ending in `kit` implies the keyword; `bare-requests` controls the general case). Add more prefixes as a
list to recognise a server's own bot syntax.

##### Replies

Every reply is a private message (`/msg <player> …`) and every one is editable from
`kit-bot → messages` (or the **Kit Messages** section of the menu, or `kit message <kind> <text>` in
the terminal). The kinds are:

| Kind | Default | When |
| --- | --- | --- |
| `grabbing` | `Grabbing the kit, please wait.` | The request was accepted; the bot is fetching the kit. |
| `not_whitelisted` | `Not whitelisted (contact logvex).` | The requester is not on the allow-list (or is blocked). |
| `unknown_kit` | `Kit doesnt exist.` | No loadout is registered under that name. |
| `cooldown` | `Error, {seconds} minute cooldown active.` | The requester asked again inside the cooldown. |
| `queue_full` | `Error, too many kits (max {max}).` | The queue is full. |
| `queued` | `You are in the queue, please wait. You are in place {place}.` | Accepted, with their queue position. |
| `delivered` | `Kit delivered.` | Sent after the confirmation window. |
| `failed` | `Error, the delivery could not be completed.` | The delivery failed (timeout, requester left). |

Templates may use `{player}`, `{kit}`, `{place}`, `{seconds}` and `{max}`; an unknown placeholder is
left as-is rather than breaking the reply. Edit one with `/bh kit message cooldown Error, wait {seconds}
minutes.` — the token is the kind name and the rest of the line is the template.

##### Storage and the delivery

A delivery is only started when the kit can actually be sourced (`require-stock`): the bot looks for
the loadout in your own inventory and in nearby registered containers within `chest-radius` blocks
(`/bh kit source pvp` reports where, or the shortfall). Minecraft does not send a chest's contents to
the client until that chest has been opened, so a container the bot has never opened reads as empty and
cannot be searched — the bot reports "not stocked" rather than pretending. Open your storage once (the
addon remembers it) and it can be used from then on.

Each request enters a bounded queue (`queue-capacity`, default 3) with a per-player cooldown
(`request-cooldown`, default 600s = 10 minutes), so a spammer cannot flood it and cannot be served
twice. The delivery is driven by a pure state machine with explicit failure paths — a refused teleport,
a timeout, or a requester who leaves — and it never loops a failed teleport. After the items are on the
ground the bot holds position for `confirm-delay` seconds (default 5) so the requester can pick the kit
up, and only then says "Kit delivered." and runs `/kill`. `/bh kit delivery` shows the state and the
queue, `/bh kit queue clear` empties it, and `/bh kit deliver <player> <kit>` starts one by hand.

## 6b6t server tools

[6b6t](https://www.6b6t.org/en) is a no-rules anarchy server with its own command set. Five modules are
wired for it; they work on any server but assume 6b6t's syntax.

- **`6b6t-homes`** — `/sethome`, `/home`, `/delhome`. The server never tells the client how many homes
  your rank allows, so `home-limit` is a setting; set it to match your rank and the module warns before
  a `/sethome` is refused. It also notices a home the server confirms in chat, so homes set without the
  addon are still tracked. `/bh 6b6t home set base`, `… home goto base`, `… home list`.
- **`6b6t-tpa`** — `/tpa`, `/tpcancel`, `/tpy`, `/tpn`. `/bh 6b6t tpa send Steve` requests a teleport;
  an unanswered request is withdrawn automatically after `request-timeout`. `auto-answer` replies to
  incoming requests, but only from the `allow-list` unless `accept-anyone` is on, so a stranger cannot
  pull you across the map.
- **`6b6t-tps`** — polls `/tps` every `poll-seconds` and warns below `low-threshold`. 6b6t reports both
  the server's TPS and yours; the module shows both, because a healthy server with a bad personal TPS
  means your connection is the problem, not the server.
- **`6b6t-hotspot`** — tracks the current Hotspot, notifies once when one goes live, and can teleport
  you in (`/bh 6b6t hotspot join`). `auto-join` is off: on an anarchy server a Hotspot is a fight, and
  the module says so when one appears.
- **`6b6t-players`** — records every player seen, with the dimension, coordinates and time. `/bh 6b6t
  players` lists them for the current server. The store is bounded and evicts the oldest, so a long
  session cannot grow the file without limit.

Open the **6b6t menu** with `/bh 6b6t gui`, or the *Open 6b6t Menu* button in the Baritone Anarchy Expansion screen.
It gives each module a status line and its controls, and lists 6b6t's command catalogue (`/bh 6b6t
commands` filters it). The terminal exposes the same tools under `sixbt …` and the enlarged `kit …`.

## Anarchy toolkit

Modules that cover the parts of an anarchy session that are not base hunting: gathering and travel
through Baritone, surviving a death with your loot, knowing when someone is closing in, and — just as
important — *moving like a person* so an automated client does not stand out.

### Baritone jobs (`baritone-control`)

Baritone knows how to mine a block, follow a player, explore chunks and build a schematic, but it has no
single place to start a job, remember it is running, notice when it ends and report that. This module is
that place. A request is parsed by a strict catalogue first, so a typo is refused with a reason instead of
being handed to Baritone as a job that silently does nothing.

| Command | What it does |
| --- | --- |
| `/bh job mine diamond_ore 64` | Mine 64 diamond ore. A bare `mine diamond_ore` mines until nothing is left. |
| `/bh job goto 100 64 -200` | Walk to an exact block. |
| `/bh job follow Steve` | Follow a player until cancelled. |
| `/bh job explore 1000 2000` | Run Baritone's chunk explorer around a point. |
| `/bh job farm 32` | Harvest crops within 32 blocks. |
| `/bh job tunnel` | Dig a straight 1×1 tunnel. |
| `/bh job build house.schem 10 70 10` | Build a schematic at an origin. |
| `/bh job stop` | Cancel the job and every Baritone process. |

The terminal exposes the same as `job status|stop|mine|goto|follow|explore|farm|tunnel|build|run`, and
`job run <line>` passes a line through to the same parser. Every job publishes `BARITONE_TASK_STARTED`,
`BARITONE_TASK_DONE`, `BARITONE_TASK_FAILED` or `BARITONE_TASK_CANCELLED` on the automation bus, so a
workflow or the automation brain can chain on it. A job that stops moving for `idle-grace-ticks` without
being cancelled is reported as finished. `auto-pickup` walks over the drops while mining and
`stop-on-death` cancels the job when you die.

### Death recovery (`death-recovery`)

The moment that matters most on an anarchy server is the moment you die, because that is where your loot
is, and vanilla forgets it the instant you respawn. This module records the death position, the dimension
and how much you were carrying, keeps it per server across restarts, warns you in chat, and (with
`auto-recover`) flies you back with Baritone after you respawn. A death is marked recovered once you are
within `recovered-radius` of it. The list is bounded, so a long session cannot grow the file without limit.

| Command | What it does |
| --- | --- |
| `/bh death list` | Every death recorded on this server, newest first. |
| `/bh death near` | The nearest un-recovered death in your current dimension. |
| `/bh death recover` | Fly back to that death. |
| `/bh death recover-id 7` | Fly back to a specific death. |
| `/bh death clear` | Forget every death. |

The terminal exposes the same as `death list|near|recover|recover-id|clear`. Deaths in another dimension
are never offered as "nearest", because a Nether death is not reachable from the Overworld.

### Player radar (`player-radar`)

"There is a player 80 blocks away" is far less useful than "a player is closing on you". This module
tracks every nearby player's distance and reports when one enters your range, when one is *approaching*
(not merely present) and when one leaves, pushing `PLAYER_NEARBY`, `PLAYER_APPROACHING` and
`PLAYER_LEFT_RANGE` onto the automation bus. It only observes — it never attacks anyone. `/bh radar` and
the terminal's `radar` list the live contacts, nearest first.

### Realistic movement (`smooth-look`, `human-behavior`, `behavior-monitor`)

A client that snaps its camera to a target and then stands perfectly still is the easiest bot to spot.
These three modules make the movement read as human, and then measure whether it does.

**`smooth-look`** turns the camera the way a wrist turns a mouse. `LookSmoother` approaches a target along
the shortest arc at a bounded per-tick speed, eases in, and settles with a small seeded jitter that fades
as the aim arrives. Yaw takes the short way across the ±180° seam (170° → −170° is 20°, not 340°) and pitch
is clamped to ±90°. It holds the `ROTATION` control lease while turning, so it never fights another module
for the camera. `face-player` keeps a continuous, smoothly-tracking aim on the nearest player.

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
player would and forges no packets. `/bh human on|off|status` toggles and inspects it.

**`behavior-monitor`** closes the loop. `BehaviorProfiler` watches a rolling window of yaw and position
samples and reduces it to how much the turn rate varies, how often you pause, and how straight the path is,
combined into a 0–100 human-likeness score. A flat 0 ("this looks like a bot") is visible on the HUD and in
`/bh human profile` instead of being taken on faith. It is read-only, so it runs alongside anything.

The terminal exposes `look status|player|at|yaw|clear` and `human status|on|off|profile`.

## The command terminal

The terminal is a command line over the whole addon, built into the management screen. Enable
`command-terminal` and open `/bh gui`; the **Terminal** section is at the bottom. It is not a second
implementation of anything: every command calls the same manager method the GUI button and the `/bh`
command call, so the three entry points can never disagree about what an action does.

Type a line and press Enter. **Tab** completes the command name, then its subcommands, then any
registered argument values; **Up/Down** walk the history, and Up from the newest line restores
whatever you had half-typed. Output scrolls in a bounded buffer (the oldest lines fall off), and the
Help / Status / Clear / Settings buttons drive it without typing. `/bh terminal run <line>` runs one
command from chat and echoes the output, and `/bh terminal help` lists every command.

### Safety

The terminal's input comes from a text box you type into — never from a chat message — so unlike the
chat prefix system it can offer the commands that change state. Three rules still apply:

- **Every command declares a permission level**, and a subcommand can be stricter than its parent.
  `storage list` is read-only; `storage withdraw` is not. The registry checks the level of the
  subcommand actually being run, not the parent's.
- **Anything that changes state asks for confirmation.** A destructive command prints what it is about
  to do and needs a second Enter. Any other command you run in between disarms it, so a stray repeat
  cannot fire by accident.
- **A macro is only ever as powerful as its steps.** Macros replay through the same registry, so
  permissions and confirmation still apply to every step, and nesting is capped.

The terminal has no shell, no filesystem access and no arbitrary code execution. It can only do what
the addon can already do.

### Shortcuts and macros

`aliases` (in the module's settings) maps a short name to a command: `s=status`, `w=withdraw`. Lookup
is case-insensitive and happens before parsing, so `s` runs `status`.

`macros` names a sequence of commands as `name = step ; step ; step`. The default defines
`preflight = elytra status ; storage restock ; survival status`. `macro list` shows them and
`macro run <name>` replays one.

### Command reference

```
help [command]          status                    modules [list|search|enable|disable]
tasks [list|stop|clear] storage [list|search|withdraw|deposit|register|goto|restock]
navigate <x> <y> <z>    baritone [status|stop]    kit [list|status|claim <name>]
job [status|stop|mine|goto|follow|explore|farm|tunnel|build|run]
death [list|near|recover|recover-id|clear]        radar [status|list]
look [status|player <name>|at <x> <y> <z>|yaw <deg> <pitch>|clear]
human [status|on|off|profile]
elytra [status|replace] echest [status|open|organise|goto]   player [list|search <q>]
stash [list|nearby|search <q>]   route [list|add|clear|start|stop]
explore [grid|spiral|adaptive|heading|custom|stop|status]
chat [status|prefixes|commands|run <line>]   config [list|get|set]
database [status|export|import]   history [clear]   macro [list|run <name>]   clear   gui
```

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
| `/bh kit delivery` / `queue` | The kit-maker delivery state and queue |
| `/bh kit queue clear` | Empty the delivery queue and abandon the current delivery |
| `/bh kit deliver <player> <kit>` | Deliver a loadout to a player by hand |
| `/bh kit loadout` / `list` | List kit loadouts |
| `/bh kit loadout show <kit>` | Show one loadout's items |
| `/bh kit loadout add <kit> <item> [count]` | Add an item to a loadout |
| `/bh kit loadout remove <kit> <item>` | Remove an item from a loadout |
| `/bh kit loadout create` / `delete <kit>` | Create or delete a loadout |
| `/bh kit restock <kit>` | Show what is missing to deliver a loadout |
| `/bh kit gui` | Open the 6b6t menu |
| `/bh chat` | List the chat commands |
| `/bh chat run <line>` | Run a chat command line as if you had typed it |
| `/bh terminal` | Open the management screen, whose Terminal section is at the bottom |
| `/bh terminal run <line>` | Run one terminal command and echo its output to chat |
| `/bh terminal help` | List every terminal command |

### 6b6t

| Command | What it does |
| --- | --- |
| `/bh 6b6t` / `status` | The 6b6t module status in one place |
| `/bh 6b6t gui` | Open the 6b6t menu |
| `/bh 6b6t home list` | The homes in the local book |
| `/bh 6b6t home set [name]` | Set a home here (`/sethome`) |
| `/bh 6b6t home goto <name>` | Teleport to a home (`/home`) |
| `/bh 6b6t home del <name>` | Delete a home (`/delhome`) |
| `/bh 6b6t home cancel` | Cancel a home teleport (`/homecancel`) |
| `/bh 6b6t tpa send <player>` | Request a teleport (`/tpa`) |
| `/bh 6b6t tpa cancel` | Withdraw the request (`/tpcancel`) |
| `/bh 6b6t tpa accept` / `decline <player>` | Answer a request (`/tpy`, `/tpn`) |
| `/bh 6b6t tps` | The last TPS reading and the average |
| `/bh 6b6t tps poll` | Ask the server for its TPS now |
| `/bh 6b6t hotspot` | The current Hotspot status |
| `/bh 6b6t hotspot join` / `create` / `cancel` / `info` | Drive the Hotspot |
| `/bh 6b6t players` | Players seen on this server |
| `/bh 6b6t commands [filter]` | 6b6t's command catalogue |

### Anarchy toolkit

| Command | What it does |
| --- | --- |
| `/bh job mine <block> [qty]` | Mine a block with Baritone until the quantity is reached |
| `/bh job goto <x> <y> <z>` | Walk to an exact block |
| `/bh job follow <player>` | Follow a player until cancelled |
| `/bh job explore [x z]` / `farm [range]` / `tunnel` / `build <file> <x> <y> <z>` | The other Baritone jobs |
| `/bh job stop` | Cancel the running job and every Baritone process |
| `/bh death list` / `near` / `recover` / `recover-id <id>` / `clear` | Death memory and fly-back recovery |
| `/bh radar` | The players currently near you, nearest first |
| `/bh look player <name>` / `at <x> <y> <z>` / `yaw <deg> <pitch>` / `clear` / `status` | Turn the camera smoothly toward a target |
| `/bh human on` / `off` / `status` / `profile` | Toggle the idle gestures; `profile` prints the human-likeness score |

## Data, persistence and backups

State is stored per server address, so travelling between anarchy servers never mixes coordinates up.
Eleven stores persist to NBT under Meteor's config folder:

| Store | File | Contents |
| --- | --- | --- |
| `StashManager` | `base-hunting-stashes.nbt` | Every stash/base/structure record |
| `PlayerTracker` | `base-hunting-players.nbt` | Player entries and their sightings |
| `EnderChestStore` | `base-hunting-ender-chests.nbt` | Recorded ender chest locations |
| `ExplorationStore` | `base-hunting-exploration.nbt` | Visited chunks and route waypoints |
| `StorageRegistry` | `base-hunting-storage.nbt` | Registered containers and their last contents |
| `WorkflowManager` | `base-hunting-workflows.nbt` | Custom storage workflow definitions |
| `KitBotStore` | `base-hunting-kits.nbt` | Kit definitions and their last-claim cooldowns |
| `KitLoadoutStore` | `base-hunting-kit-loadouts.nbt` | 6b6t kit-maker loadouts, per server |
| `SixbtServerStore` | `base-hunting-6b6t.nbt` | The 6b6t home book, per server |
| `SixbtPlayerStore` | `base-hunting-6b6t-players.nbt` | 6b6t player sightings, bounded and evicting |
| `DeathStore` | `base-hunting-deaths.nbt` | Death positions and carried-item counts, per server, bounded |

All eleven are versioned. Each store reads its records tolerantly: **a single corrupted record is
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
./gradlew build          # jar -> build/libs/baritone-anarchy-expansion-2.9.0.jar
./gradlew compileJava    # fast syntax/type check
./gradlew verifyAddon    # headless logic + API contract tests (no display needed)
./gradlew runClient      # dev client with Meteor + addon loaded
```

`verifyAddon` is the fast pre-flight check and is wired into `check`, so `./gradlew build` runs it too.
It runs eleven headless suites: `plannerTest` (flight-planner maths), `coreLogicTest` (elytra,
deployment — including the bounded-retry `DeploymentGovernor` regression — mending, inventory, stash
scoring, chunk dedup, target ranking, persistence recovery, scheduler and event bus), `storageLogicTest`,
`automationLogicTest`, `hardeningTest` (bounded collections, embed dedupe, database coverage),
`terminalLogicTest` (the terminal parser, history, shortcuts, registry dispatch/permissions/
confirmation/completion and macros), `sixbtLogicTest` (the 6b6t command/chat/kit/delivery logic),
`anarchyToolkitTest` (the Baritone job parser, death memory and proximity radar), `smoothLookTest`
(the smooth-look camera, human-behavior gestures and the behavior profiler), `apiContractTest` (asserts
the Meteor/Minecraft members the addon drives still exist) and `baritoneContractTest` (asserts the
Baritone members the addon drives still exist).

See `AGENTS.md` for the architecture and the development constraints, including how to run the client
headlessly.

## License

See `LICENSE`.
