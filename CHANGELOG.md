# Changelog

All notable changes to the Base Hunting Client. Format loosely follows [Keep a
Changelog](https://keepachangelog.com/); versions follow [Semantic Versioning](https://semver.org/).

## [2.4.0] — 2026-11-11

The command-terminal release. It adds an in-game command terminal: a real command line over the same
managers the GUI and `/bh` use, with argument parsing, Tab completion, history, configurable
shortcuts, per-command permissions and a confirmation step for anything that changes state. The
terminal is local-only — its input comes from a text box you type into, never from a chat message
another player sent — so it can offer the destructive commands the chat prefix system refuses by
default. `./gradlew build` is green (36 + 147 + 30 + 196 + 21 + 21 + 131 + 86 headless assertions).

### Added — the command terminal

- **`command-terminal` module.** Enable it and open `/bh gui`; the Terminal section is at the bottom.
  Output scrolls in a bounded buffer, input is a text box that runs a command on Enter, and the
  Help/Status/Clear/Settings buttons drive it without typing. `/bh terminal run <line>` runs one
  command from chat and echoes the output; `/bh terminal help` lists every command.
- **Nineteen command groups** cover the whole addon: `status`, `modules`, `tasks`, `storage`,
  `navigate`, `baritone`, `kit`, `elytra`, `echest`, `player`, `stash`, `route`, `explore`, `chat`,
  `config`, `database`, `history`, `clear` and `gui`. Each handler calls the same public method the GUI
  and `/bh` use, so the three entry points cannot drift apart.
- **Per-subcommand permissions.** A command declares its own level and each subcommand may be
  stricter, so `storage list` is read-only while `storage withdraw` is not — the registry checks the
  level of the subcommand actually being run, not the parent's.
- **Confirmation gate.** A command marked destructive asks for a second Enter before it runs; any
  intervening command disarms it, so a stray repeat cannot fire by accident.
- **Tab completion** for command names, subcommands and registered argument candidates, routed into
  Meteor's own completion popup through a text-box renderer. **History** with an Up/Down cursor that
  restores the line you were typing, consecutive-duplicate collapsing and a size cap.
- **Configurable shortcuts** (`aliases` setting, `s=status`, `w=withdraw`, …), applied before parsing
  and looked up case-insensitively. **Macros** (`macros` setting) name a sequence of commands
  (`name = step ; step`) that `macro run` replays through the same registry, so permissions and
  confirmation still apply to every step.

### Added — pure terminal core, headlessly tested

- `core/terminal/`: `CommandLine` (quote- and backslash-aware tokeniser, prefix stripping),
  `CommandRegistry` (dispatch, aliases, per-subcommand permission and confirmation, completion, help),
  `TerminalCommand` (metadata + builder), `TerminalBuffer` (bounded, typed lines),
  `CommandHistory` (cursor + draft), `CommandAliases` (shortcuts), `MacroStore` (named sequences).
- `TerminalLogicTest` — 86 assertions over the parser, history cursor, shortcut table, registry
  dispatch/aliases/permissions/confirmation/completion, the macro store and buffer bounds. Wired into
  `verifyAddon`.

## [2.3.0] — 2026-11-11

The stability-hardening release. Every change here is a bug that was found by reading the code: an
unbounded retry loop that would hammer a doomed takeoff forever, three collections that grew without
limit over a long flight, a backup that silently dropped three of the seven databases, an inventory
scan that could pick the chest slot, and a couple of null dereferences. Each fix has a regression test
that would fail if it came back. `./gradlew build` is green (36 + 147 + 30 + 196 + 21 + 21 + 131
headless assertions) and the addon loads in a real dev client.

### Fixed — the v2 autopilot retried a doomed takeoff forever

- **Bounded v2 deployment.** `BaseHunterV2` calls `FlightController.setWantsFlight(true)` on every tick
  while a route is active. `setWantsFlight` reset the launch retry policy on every call, so the launch
  budget was refilled 20 times a second and a takeoff that could never succeed (no viable elytra, a
  blocked launch, a hole in the ground) was retried forever — the same failure mode that was fixed for
  v1 in 2.2.0. The flag now only resets the budget on a genuine off→on transition, and a glide that
  actually starts replenishes it for the next interruption. The policy is extracted into a pure
  `DeploymentGovernor` so the "never retry forever" rule is asserted headlessly: a simulated 400-tick
  loop that re-asserts flight every tick issues exactly the configured number of launches and then
  stops.
- **Landing report is a one-shot.** `DeploymentGovernor` now clears the "just landed" flag on a repeat
  state, so a player standing on the ground is not reported as landing again every tick.

### Fixed — unbounded memory over a long session

- **`NewChunks` chunk sets.** `newChunks`, `oldChunks` and the `foundAt` timestamp map grew without
  bound; a multi-hour flight loads hundreds of thousands of chunks. They are now backed by a new
  `BoundedLongSet` (hard cap of 200k, oldest-first eviction) and the `foundAt` map is pruned by the
  same eviction, so a dropped key never leaves a stale timestamp. The age-based cleanup no longer
  leaves orphaned insertion-order entries behind.
- **`StashFinder.lastScanned`.** The map of already-scanned chunk keys never shrank, so it grew for
  every chunk ever scanned. It is now size-capped with oldest-first eviction and old entries are
  pruned when their rescan window has passed.

### Fixed — a backup could silently discard live data

- **`DatabaseIO` covered four of seven stores.** Export wrote only stashes, players, ender chests and
  exploration; the storage registry, storage workflows and kits were missing. Importing such a file
  left those three untouched on the exporting machine but wiped them on a fresh one — a backup that
  loses data is worse than no backup. All seven stores now round-trip, and a headless test pins the
  section list so a newly added store cannot fall out of the path unnoticed.

### Fixed — duplicate suppression and lost alerts

- **`Embed.fingerprint()` ignored the footer and thumbnail.** Two alerts that differed only in their
  footer or thumbnail shared a fingerprint and were collapsed by the 3s dedupe window. The fingerprint
  now covers every field that reaches Discord, and is asserted to be un-forgeable from field text.
- **A dropped webhook suppressed its own retry.** `WebhookManager.enqueue` recorded the dedupe key
  before the queue offer, so a message dropped on a full queue still marked the key as "recent" and the
  retry within the next 3s was discarded as a duplicate. The key is now rolled back when the offer
  fails.

### Fixed — wrong target and null dereferences

- **Elytra replacement could pick the chest slot.** `ElytraManager` scans bounded by
  `PlayerInventory.MAIN_SIZE` so an inventory index is never mapped onto an armour slot; a couple of
  lookups were not bounded and could count the worn elytra as a spare or select the chest slot as the
  replacement source.
- **`bestSpareRemaining()` null dereference.** Every sibling method guards `mc.player`, but this one
  dereferenced it directly and is called from the `/bh elytra status` command — a crash if the command
  is run before a world is joined.
- **Task-id collision.** `AutomationEngine` kept its own id counter while `TaskQueue` kept another, so
  task ids could collide and a `cancel(id)` could hit the wrong task. All id assignment now goes
  through the queue's single counter.
- **`BaseHunterV2` re-warning.** The "not flying" warning flag was never cleared on a resumed flight,
  so only the first interruption of a session was ever reported.

### Added

- **`BoundedLongSet`** — a tested, synchronized, size-capped set of packed chunk keys with an eviction
  callback, used by the chunk scanners.
- **`DeploymentGovernor`** — the pure deploy/recover policy behind `FlightController`, so the retry
  rules are unit-testable without a client.
- **`HardeningTest`** — a new headless suite (bounded collections, embed dedupe, database coverage),
  wired into `verifyAddon`.

## [2.2.0] — 2026-11-11

The Baritone hardening release. The v1 Baritone autopilot no longer re-issues its flight path every
tick, a launch that keeps failing is retried with backoff and then abandoned, and the whole Baritone
API surface the addon drives is now verified against the real jar by a headless contract test.
`./gradlew clean build` is green (36 + 147 + 184 + 30 + 21 + 131 headless assertions) and the addon
loads in a real dev client with Baritone present and no exceptions.

### Fixed — v1 autopilot no longer hammers a doomed launch

- **Bounded flight deployment.** `BaseHunter` (v1) previously called `BaritoneHelper.elytraPathTo`
  every client tick while not gliding, so a takeoff that could never succeed (no firework, a blocked
  launch, a spent elytra) was retried 20 times a second forever. It now classifies the player's
  physical state (`FlightState`) and drives launches through a `RetryPolicy`: a failed launch is
  retried with exponential backoff (1s → 20s) and, after `deploy-retries` attempts (default 6), the
  reason is reported once and the route stops cleanly. Swimming or climbing waits without spending an
  attempt, and a new leg resets the budget.
- **Baritone health, not just presence.** `BaritoneHelper.AVAILABLE` only proved the classes were on
  the classpath. Baritone bundles its elytra pathfinder as a nested jar; when that fails to load,
  `BaritoneAPI`'s static initialiser throws and every call into it throws too. The helper now probes
  with the class *not* initialised and resolves the primary baritone once, caching the outcome and the
  reason. `usable()` and `health()` let callers degrade to "not usable" instead of crashing, and every
  guard in `BaseHunter` and `NavigationManager` uses them.
- **One owner of elytra replacement.** v1's `applyBaritoneSettings` set `elytraAutoSwap = true`,
  racing `ElytraManager` (the addon's single owner) to swap the same chest slot. It is now `false`.

### Added — navigation, status and verification

- **Elytra travel in `NavigationManager`.** Long-range navigation requests can now fly instead of only
  walking, via `start(owner, destination, arrivalDistance, fly)`. Arrival and interruption detection
  use `isMoving()` (path or elytra) rather than `isPathing()`, so an elytra leg is not mistaken for a
  finished path.
- **`/bh baritone` command group** — `status` (availability, version, primary instance, pathing and
  flight state), `terms` (accept Baritone's elytra terms), and `stop` (cancel Baritone pathing).
- **Baritone HUD line** — an optional `show-baritone` element reports availability, version and
  whether Baritone is currently moving.
- **`baritoneContractTest`** — a headless test that reads the real Baritone jar and asserts every
  interface, process, goal, command-manager and elytra setting the addon drives still exists (30
  assertions). It is wired into `verifyAddon`, so a Baritone rename is caught before release rather
  than mid-flight on a user's machine.

## [2.1.0] — 2026-11-11

The automation release. Adds a priority task engine, a declarative rule engine, an in-chat command
runner, server-kit claiming and exact-quantity storage transfer, and wires all of it through one
shared engine so nothing drives the player twice. `./gradlew clean build` is green (36 + 147 + 175 +
21 + 131 headless assertions) and the addon loads in a real dev client with no exceptions.

### Added — automation engine and tasks

- **`AutomationEngine`** — a single priority task queue (`TaskQueue`) that owns every long-running
  automation. Tasks are submitted, deduplicated by name and category, run one at a time, retried up to
  `maxAttempts`, and reported as completed / partial / failed / cancelled. Registered per-category
  factories build the runner lazily, so a task's work is described by data and executed by the engine.
- **`Task` / `TaskQueue` / `TaskState`** — a serialisable unit of work with priority, category,
  progress, retry accounting and a terminal-state guard. A failed-but-retryable task is requeued
  rather than silently dropped; a terminal task cannot be resurrected by a late state write.
- **`AutomationBrain`** (`automation-brain`) — a declarative rule engine that reacts to events and
  facts instead of modules calling each other. Rules are edge-triggered (they fire on a rising edge
  only), rate-limited by cooldown, and expressed over `PlayerFacts` (`health`, `hunger`,
  `elytra_durability`, `free_slots`, `totems`, `nearby_players`, `task_queue`, ...). Built-in
  reactions: replace a failing elytra, pause exploration when the inventory fills or health drops,
  note player sightings, and observe task completion.
- **`NavigationManager`** — a single owner of "go to a position", so travel requests from storage,
  ender chest and kit flows never fight over movement.

### Added — chat commands and kits

- **`ChatCommands`** (`chat-commands`) — runs addon commands typed into chat behind a configurable
  prefix (`!`, `.bh`, ...). Only a line the local player typed is ever executed; a received message is
  data and is never parsed. Destructive commands are refused unless allowed through
  `ChatPermissions`. Commands: `help`, `status`, `whoami`, `tasks`, `elytra`, `kits`, `stash`,
  `storage`, `withdraw`, `deposit`, `kit-claim`, `cancel`.
- **`KitBot`** (`kit-bot`) — claims server kits and tracks their cooldowns, per server. Cooldowns are
  remembered locally so a restart does not spam a claim the server will reject, and a server-side
  cooldown reply is adopted rather than ignored. `KitBotStore` persists kits and cooldown state.
- **`KitRegistry` / `KitResponses`** — pure kit bookkeeping (ready/cooldown, describe, status) and a
  classifier that reads the server's reply to decide whether a claim succeeded, is on cooldown, or was
  refused.

### Added — storage automation

- **`AutoStorage`** (`auto-storage`) — exact-quantity withdrawals, deposits and restocks submitted as
  engine tasks rather than inline click loops, so they are serialised, interruptible and report a real
  outcome. Chooses the open container, or the nearest registered container that actually holds the
  item (travelling to it when `travel-to-containers` is on).
- **`QuantityPlanner` / `ItemFilter` / `ProtectionGuard` / `ProtectionRules`** — pure logic for
  planning an exact-quantity move, matching an item by id / category / free-text term, and refusing a
  move into or out of a protected slot or a protected item. Protection is namespace-insensitive
  (`minecraft:elytra` and `elytra` behave identically) and is enforced in the operation layer, not
  trusted from the caller.
- **`SortPlanner.Kind.TRANSFER`** — a partial-slot move (pick up, place exactly N, put the remainder
  back) modelled click-for-click in `InventorySimulator` so a partial transfer can be verified like
  any other move.

### Added — GUI, HUD and notifications

- The management screen gains an **Automation** section: engine status, brain / auto-storage / kit
  toggles, active and queued task counts, a **Claim Kits** button, **Cancel Tasks**, and a **Reactions**
  view. Every button calls the same public method the commands use.
- The HUD gains a `show-automation` toggle that prints the active task, the queue depth and the kit
  status.
- `webhook-notifier` gains `notify-tasks` and `notify-chat-commands` categories with per-type
  cooldowns, and titles / descriptions / colours for the task, kit and chat event types. The existing
  async queue, rate limiting and deduplication are unchanged.

### Added — commands

- New `/bh` groups: `automation status|start|stop|reactions`, `tasks status`, `withdraw <item>
  [amount]`, `deposit <item> [amount]`, `kit status|list|claim <name>|all`, and `chat run <line>`.

### Tests

- **`AutomationLogicTest`** — 147 headless assertions covering the task lifecycle and queue ordering,
  retry and cancel, exact-quantity planning and simulation, item matching, protection, the chat router
  and permissions, the kit registry and response classifier, and the rule engine. `verifyAddon` now
  runs it alongside the existing suites.

### Fixed

- A failed-but-retryable task is now genuinely re-queued instead of being left terminal.
- A rule whose condition stays true through a cooldown now fires once the cooldown expires instead of
  losing the edge.
- Protected-item matching is namespace-insensitive everywhere, so `minecraft:elytra` in the protected
  list refuses a move of `elytra` and vice versa.
- `ItemFilter` implements `equals` / `hashCode`, so parsed filters can be compared and used in sets.

## [2.0.0] — 2026-11-11

The storage release. Adds a complete, safe storage and inventory subsystem and bumps the mod to 2.0.0.
`./gradlew clean build` is green (21 + 175 + 131 + 36 headless assertions) and the addon loads in a real
dev client with no exceptions.

### Added — storage and inventory

- **Inventory Manager** (`inventory-manager`) — sorts the inventory into a stable category order, on
  activate, after closing a container, or on an interval. Honours a hotbar reserve, the selected slot and
  a protected-item list; never splits a stack.
- **Chest Sorter** (`chest-sorter`) — sorts any container and deposits/withdraws only the categories you
  select, keeping a survival reserve back. Registers every container you open into the storage network.
- **Storage Network** (`storage-network`) — a per-server, per-dimension directory of your containers with
  their last-observed contents, and travel to one on request (Baritone when available, a clear refusal
  when not).
- **Shulker Manager** (`shulker-manager`) — fills, empties and sorts shulker boxes, and searches carried
  boxes without opening them by reading the item `CONTAINER` component. A box whose contents were never
  seen is reported as *unknown*, never empty.
- **Restock Manager** (`restock-manager`) — tops up travel supplies from registered storage, walking to
  the storage when `travel-to-storage` is on and Baritone is loaded.
- **Storage Monitor** (`storage-monitor`) — runs named workflows (`quick deposit`, `full cleanup`,
  `pre-flight`, `storage maintenance`) and is the single storage status view.

### Added — coordination and safety

- **`ContainerInteractionManager`** — the single owner of slot clicks. Every operation holds the
  `INVENTORY` lease, is spaced by a tick delay, is verified against a simulated expected result, and
  fails with a reason on a wrong settled state, a missing slot, or a timeout. It self-subscribes its
  tick and cancels everything on disconnect.
- **`StorageOperations`** — bridges a live `ScreenHandler` to the pure planners, bounding every
  main-inventory scan with `PlayerInventory.MAIN_SIZE`.
- **`StorageRegistry` / `WorkflowManager`** — persisted per server through `RecordCodec`, so a corrupt
  record is skipped rather than fatal.
- **`CONTAINER_OP_FAILED`** automation event so a failed operation is visible in chat, the GUI and
  Discord.

### Added — pure, headlessly-tested logic

`StorageCategory`, `ItemClassifier`, `ContainerLayout`, `SortPlanner`, `InventorySimulator`,
`TransferPlanner`, `RestockPlanner`, `ShulkerInventory`, `ProtectionRules`, `ItemRoutingEngine`,
`OperationState`, `WorkflowEngine`, `CategoryParser`.

### Added — integration

- Storage & Inventory section in the management screen (`/bh gui`).
- Storage HUD lines (`show-storage`, `show-shulkers`).
- Commands: `/bh storage …`, `/bh shulker …`, `/bh workflow …`, `/bh restock …`.
- Storage notification categories and per-event cooldowns in the webhook notifier.

### Added — tests

- `storageLogicTest` — 131 headless assertions covering classification, layout, sorting, simulated sort
  correctness, protection rules, transfers, routing, restock needs, shulker contents, operation state,
  workflows and category parsing.

### Docs

- `AGENTS.md` — a "Storage subsystem" section: the one-clicker rule, simulation verification, the
  three-click move shape, and the classification, slot-id and shulker-unknown gotchas.
- `README.md` and `docs/GUIDE.md` — module tables, a storage walkthrough, the category reference and the
  limitations.

## [1.1.0]

The autonomous exploration and equipment release: Base Hunter V2 (firework-free elytra autopilot),
automatic elytra management, the Mending manager, the ender chest manager, adaptive exploration, stash
and new-chunk detection, player tracking, the survival manager, the shared event bus and priority
scheduler, and Discord webhook notifications.
