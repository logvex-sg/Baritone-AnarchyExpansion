# AGENTS.md

Meteor Client addon for Minecraft 1.21.11 (Fabric) providing an anarchy base-hunting toolkit. V1
(`BaseHunter`) plans with Baritone's elytra pathfinder; V2 (`BaseHunterV2`) flies itself by driving
Meteor's ElytraFly in Bounce mode, so it needs no fireworks and is limited only by elytra durability.

## Build

JDK 21 required. The Gradle wrapper is the only entry point.

```bash
./gradlew build          # jar -> build/libs/basehunting-2.5.0.jar
./gradlew compileJava    # fast syntax/type check
./gradlew verifyAddon    # headless planner + logic + contract tests (no display needed)
./gradlew runClient      # dev client with Meteor + addon loaded
```

`verifyAddon` is the fast pre-flight check. It runs nine plain `main` classes (not JUnit, which is
why `tasks.named<Test>("test") { failOnNoDiscoveredTests = false }` keeps the default test task from
failing the build), and is wired into `check`, so `./gradlew build` runs it too:

- `plannerTest` — `FlightPlanner` maths over packed chunk keys.
- `coreLogicTest` — the pure-logic suite: elytra durability, the deployment state machine, flight-state
  classification, mending priorities, item classification, inventory planning, stash scoring, chunk
  deduplication, persistence record recovery, target prioritisation, retry backoff (including the
  bounded-deployment loop and the `DeploymentGovernor` regression), the exploration planner, survival
  thresholds, the scheduler and the event bus. 196 assertions, no Minecraft classes, so it catches
  arithmetic and state-machine regressions that would otherwise only appear mid-flight.
- `storageLogicTest` — the storage suite: item classification, container layout, sort planning,
  simulated sort correctness, protection rules, deposit/withdraw/pull transfers, item routing, restock
  needs, shulker contents, operation state, workflows and category parsing. 131 assertions, again pure.
- `automationLogicTest` — the automation suite: the task lifecycle, queue ordering, retry and cancel,
  exact-quantity planning and simulation, item matching, protection, the chat router and permissions,
  the kit registry and response classifier, and the rule engine. 147 assertions, pure.
- `hardeningTest` — regressions for the stability fixes: `BoundedLongSet` size cap and oldest-first
  eviction (including the eviction callback and a million-insert run), `Embed.fingerprint` dedupe
  coverage, and `DatabaseIO.sectionKeys()` coverage of every persisted store. 21 assertions, pure.
- `terminalLogicTest` — the command-terminal suite: the quote- and backslash-aware line parser, the
  history cursor and draft restore, the shortcut table, registry dispatch/aliases, per-subcommand
  permission levels, the confirmation gate, tab completion and the macro store. 86 assertions, pure.
- `sixbtLogicTest` — the 6b6t suite: the server command catalogue and address check, the chat line
  parser (vanilla/ranked senders, command echo, tpa requests), kit-request parsing, the
  `KitDeliveryMachine` state machine and every failure path, the bounded `KitRequestQueue`, kit
  loadouts and their parsing, the restock comparison, the TPS and hotspot parsers, the `TpaPolicy` and
  the home book. 106 assertions, pure — which is what makes a bot that teleports and kills itself safe
  to change.
- `apiContractTest` — asserts the Meteor/Minecraft members the addon drives still exist, reading them
  out of the dependency jars.
- `baritoneContractTest` — asserts the Baritone members the addon drives still exist, reading them out
  of the real Baritone jar (30 assertions). Baritone is compile-only, so a rename would compile here
  and only fail at runtime; this catches it at build time.

`./gradlew runClient` needs a display. In a headless container install Xvfb and use software GL:

```bash
sudo apt-get install -y xvfb mesa-utils libgl1-mesa-dri
nohup setsid Xvfb :99 -screen 0 1280x720x24 -nolisten tcp </dev/null >/tmp/xvfb.log 2>&1 &
DISPLAY=:99 LIBGL_ALWAYS_SOFTWARE=1 ./gradlew runClient
```

Run Xvfb detached with `setsid`/`nohup`. If it is a plain background job of the agent shell it gets
stopped and GLFW hangs forever inside `glfwInit` with no error output. Client logs land in
`run/logs/latest.log` and `run/logs/debug.log`.

Expect these harmless errors on every headless run: `libflite.so not found`, `Failed to open OpenAL
device`, `401` on user properties, and the Realms `SignedJWT` failure. None are addon problems.

## Verification without a display

Minecraft's dev jar relies on fabric-loader for access widening, so classes cannot just be loaded
from a plain `URLClassLoader` — `net.minecraft.registry.SimpleRegistry` throws `IllegalAccessError`
on `RegistryEntry$Reference.setRegistryKey`. Two workable approaches:

1. **Real client** (preferred, exercises everything): `./gradlew runClient` as above. A successful
   load prints `Initializing Base Hunting Client` followed by `(Meteor Client) Loading`.
2. **Targeted unit test**: put the compiled classes, the loom-merged Minecraft jar, and the rest of
   the compile classpath on the *system* classpath, then call `net.minecraft.Bootstrap.initialize()`
   only if the code under test touches registries. `WebhookManager` does not, so it needs no bootstrap.

The loom-merged jar is at
`.gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/<version>/minecraft-merged-*.jar`.
Note that `./gradlew build` regenerates it; a stale path from a previous session will fail to load.

## Architecture

```
src/main/java/dev/basehunt/bhclient/
├── BaseHuntingAddon.java        MeteorAddon entrypoint; registers systems, modules, commands, HUD
├── commands/BaseHuntCommand.java  /basehunt (alias /bh), grouped subcommands
├── gui/BaseHuntingScreen.java   management screen (/bh gui): status, exploration, route, recent
│                                activity, stash, player, equipment, survival, notification and
│                                database sections
├── hud/BaseHuntingHud.java
├── core/                        shared runtime coordination (no Minecraft types except ClientThread)
│   ├── AutomationManager.java     hub: owns the bus + scheduler, ticks the scheduler
│   ├── AutomationBus.java         publish/subscribe + bounded history
│   ├── AutomationEvent.java       event record and Type enum
│   ├── TaskScheduler.java         priority arbiter, one owner per ControlResource
│   ├── Priority.java, ControlResource.java, Lease.java
│   ├── ClientThread.java          runs work on the Minecraft client thread
│   ├── logic/                     pure, headlessly testable decision logic:
│   │                              ElytraDurability, ElytraDeployment, FlightState, MendingPriorities,
│   │                              ItemCategory, InventoryPlan, StashScorer, StashClassification,
│   │                              ExploredRegions, ExplorationPlanner, TargetPlanner, RetryPolicy,
│   │                              DeploymentGovernor (bounded deploy/recover policy for
│   │                              FlightController), BoundedLongSet (size-capped chunk-key set),
│   │                              SurvivalStatus, RecordCodec (tolerant NBT list decoding),
│   │                              StorageCategory, ItemClassifier, ContainerLayout, SortPlanner,
│   │                              InventorySimulator, TransferPlanner, RestockPlanner, ShulkerInventory,
│   │                              ProtectionRules, ItemRoutingEngine, OperationState, WorkflowEngine,
│   │                              ItemFilter, QuantityPlanner, ProtectionGuard
│   ├── task/                      the automation work queue:
│   │                              Task (name/category/priority/progress/attempts/state),
│   │                              TaskQueue (one runner at a time, dedupe, retry, cancel),
│   │                              TaskState, TaskRunner
│   ├── rule/                      declarative reactions:
│   │                              Condition (parses "<fact> <op> <number>", inert when malformed),
│   │                              Rule (edge-triggered + cooldown), RuleSet
│   ├── chat/                      ChatRouter (parses only LOCAL_PLAYER lines), ChatPermissions
│   ├── terminal/                  in-game command terminal (pure): CommandLine (quote/escape-aware
│   │                              tokeniser), CommandRegistry (dispatch, aliases, per-subcommand
│   │                              permission + confirmation, completion, help), TerminalCommand
│   │                              (metadata + builder), TerminalBuffer (bounded, typed lines),
│   │                              CommandHistory (cursor + draft), CommandAliases (shortcuts),
│   │                              MacroStore (named command sequences replayed via the registry)
│   └── kit/                       Kit, KitRegistry, KitResponses (classify the server's reply)
│   └── sixbt/                     6b6t server logic (pure): SixbtCommand (command catalogue +
│                                  address check), SixbtChat (chat line + kit-request + tpa parsing),
│                                  KitDeliveryMachine (delivery state machine, actions as data),
│                                  KitRequestQueue (bounded, per-player cooldown), KitLoadout /
│                                  KitLoadoutRegistry / KitRestockPlan (kit-maker loadouts),
│                                  HomeBook (homes + limit), TpaPolicy, TpsMonitor, HotspotInfo
├── modules/                     BaseHunter (v1), BaseHunterV2, NewChunks, StashFinder,
│                                PlayerLogger, AutoLog, WebhookNotifier, MendingManager,
│                                EnderChestManager, SurvivalManager,
│                                InventoryManager, ChestSorter, StorageNetwork, ShulkerManager,
│                                RestockManager, StorageMonitor,
│                                AutomationBrain (rule reactions), ChatCommands, KitBot (claimer +
│                                6b6t kit maker), AutoStorage, CommandTerminal (the terminal
│                                runtime: registry ↔ managers),
│                                SixbtHomeManager, SixbtTpaManager, SixbtTpsMonitor,
│                                SixbtHotspotTracker, SixbtPlayerTracker (the 6b6t modules)
├── systems/                     StashManager, PlayerTracker, EnderChestStore, ExplorationStore,
│                                StorageRegistry, WorkflowManager, KitBotStore, KitLoadoutStore,
│                                SixbtServerStore, SixbtPlayerStore (persisted per-server state),
│                                AutomationEngine (task queue + factories), NavigationManager
│                                (single owner of "go to a position"), DatabaseIO (export/import),
│                                StorageOperations (live container ↔ pure-planner bridge),
│                                ContainerInteractionManager (the one thing that clicks slots),
│                                StorageAutomation (task runners for withdraw/deposit), PlayerFacts,
│                                KitDeliveryService (drives KitDeliveryMachine on the client)
└── utils/                       BaritoneHelper, ElytraController, ElytraManager, FlightController,
                                 EquipmentScanner, FlightPlanner, WebhookManager, ServerUtils,
                                 WebhookSettings, ContainerHelper, CategoryParser
```

### Control and event flow

Modules never call each other directly. There are exactly three coupling paths:

1. **Events** — `AutomationManager.bus()` carries `AutomationEvent`s. A module publishes what happened
   (a stash was found, an elytra was replaced, the inventory is full) and any module that cares
   subscribes. `WebhookNotifier` is a single global subscriber that turns events into Discord alerts,
   so adding a new event type needs no new webhook code.
2. **Leases** — anything that touches the player (movement, rotation, inventory slots, equipment,
   interaction) takes a `Lease` from `TaskScheduler` first. A resource has one owner at a time and a
   higher `Priority` preempts a lower one. This is what stops two automations fighting over the same
   controls. `AutomationManager.tryAcquire` is the entry point; a module that is refused must back off
   for that tick rather than acting anyway. Leases carry a short timeout so a module that stops ticking
   releases control on its own.
3. **Tasks** — anything longer than a tick is a `Task` on the `AutomationEngine`, not an inline loop.
   The engine runs one task at a time in priority order, deduplicates by name+category, re-queues a
   failed-but-retryable task, and reports every transition on the bus. The runner is built lazily from
   a per-`Task.Category` factory (`engine.registerFactory(...)`), so the queue holds descriptions, not
   live objects. GUI, `/bh` commands and `ChatCommands` all submit through the engine, so there is one
   queue and one owner of the work.

`AutomationBrain` is the reactive layer: it evaluates `Rule`s over `PlayerFacts` each tick and on
subscribed events. A rule fires on a rising edge only, subject to a cooldown, so a condition that stays
true does not spam an action. Rules call a module's public method or submit a task — never a private
shortcut.

The persistent gotchas: `AutomationManager` is **not** a Meteor `System` or `Module`, so
`install()` must subscribe it to `MeteorClient.EVENT_BUS` itself or its tick handler never runs and
timed leases never expire; and `AutomationBus` logs through its own `LogUtils` logger rather than
`BaseHuntingAddon.LOG`, because referencing the addon class from the bus pulls in `Category` →
`Items` → registries and breaks the headless logic test.

## Conventions and gotchas

**Baritone is compile-only.** It is a `recommends` in `fabric.mod.json`, not a `depends`, so the
addon must load without it. Never reference `baritone.*` types from a class that is initialised
unconditionally — that throws `NoClassDefFoundError` when Baritone is absent. All access goes through
`BaritoneHelper`, which probes for `baritone.api.BaritoneAPI` in a static block and exposes
`AVAILABLE`. Anything needing Baritone must check it first and report a clear chat error.

**Class presence is not usability.** Baritone bundles its elytra pathfinder as a nested jar
(`nether-pathfinder`); when that nested jar fails to load, `BaritoneAPI`'s static initialiser throws
and every later call into it throws too. `BaritoneHelper.AVAILABLE` therefore probes with the class
*not* initialised, and `get()`/`usable()`/`health()` resolve the primary baritone once and cache the
outcome and the reason. Modules guard with `usable()`, not `AVAILABLE`, so a half-broken Baritone
degrades to "not usable" instead of crashing the addon. The static probe must never initialise
`BaritoneAPI`, or a broken nested jar surfaces as an uncaught `ExceptionInInitializerError`.

**Loom's `modLocalRuntime` does not unpack Baritone's nested jars.** Adding `modLocalRuntime(libs.baritone)`
makes the dev client crash inside Baritone's own init (`NetherPathfinderContext` → JNA
`NativeLibrary.loadLibrary` → `UnsatisfiedLinkError` for `libflite.so`). To test the addon with real
Baritone, drop the release jar in `run/mods/` (e.g. `baritone-meteor-1.21.11-SNAPSHOT.jar`) and run
the client; a successful load shows `Loading 10 mods`, `Path Manager: Baritone`, then the addon and
Meteor init lines.

**v1 flight deployment is bounded.** `BaseHunter` classifies the player's state (`FlightState`) and
drives launches through a `RetryPolicy` (`deployPolicy`, rebuilt by `resetDeployPolicy()` from the
`deploy-retries` setting). A failed launch is retried with backoff and then abandoned with a clear
stop — never call `elytraPathTo` every tick, which is what the old code did. A new leg resets the
budget. The addon owns elytra replacement (`ElytraManager`), so Baritone's own `elytraAutoSwap` is
left `false`.

**Systems must be registered before modules load.** `Systems.add(...)` for `StashManager` and
`PlayerTracker` happens before `Modules.get().add(...)` in `onInitialize()`; otherwise their saved
data is discarded on startup.

**Webhook sending is async and rate limited.** `WebhookManager` is a singleton with one daemon
thread draining a bounded queue. Never send from the tick loop synchronously. It dedupes messages
within a 3s window — the dedupe key includes the embed body via `Embed.fingerprint()`, so if you add
a new field to `Embed`, add it to `fingerprint()` too or distinct alerts will collapse into one. The
key is only recorded once the message is accepted into the queue: a message dropped on a full queue
rolls its key back so the retry is not suppressed as a "duplicate".

**Mentions ride as message content.** Discord embeds cannot carry a mention, so `WebhookSettings.send`
passes `prefix()` as `content` alongside the embed. Preserve that when adding new send paths.

**Meteor settings** are declared with builders in each module; group them with `settings.createGroup`
and use `.visible(...)` to hide options that do not apply. Settings are the config UI — there is no
separate config file to update.

**Base Hunter V2 drives Meteor's ElytraFly, and Bounce mode overwrites rotation every tick.** It is
not a pathfinder; it holds `forward` and recasts the elytra, which is what removes the firework
requirement. Two consequences, both verified against the Meteor jar and both guarded by
`apiContractTest`:

- Bounce writes pitch from `ElytraFly.pitch` whenever `lockPitch` is on. `lockPitch` **defaults to
  true**, so `ElytraController.enable` must set it to false or `steerTo`'s altitude control is
  silently discarded.
- Bounce writes yaw from `ElytraFly.yawLockMode`. `yawLockMode` must stay `Smart` so Bounce passes
  the yaw `steerTo` wrote through unchanged; `Simple` rounds it to 45 degrees and `None` pins a fixed
  bearing, both of which break waypoint steering.

`ElytraManager` owns elytra replacement, so `ElytraFly.replace` is set to false — two owners racing to
swap the same chest slot is worse than one. Meteor's own replacement also stops working once the
chest slot holds a non-elytra item, whereas `ElytraManager.equipBest` re-equips from the inventory
after a mid-air break.

**Inventory slot indices are not all main inventory.** `PlayerInventory.size()` returns 43 and
`getStack(i)` maps indices 36..42 onto the armour/offhand slots, so index 38 is the *chest* slot.
Scanning `0..size()` for spares therefore counts the elytra you are wearing and can select the chest
slot as the replacement source. Always bound such scans with `PlayerInventory.MAIN_SIZE` (36).

**Elytra replacement has exactly one owner.** `ElytraManager` performs every chest-slot swap; the
`FlightController` decides *when* a swap is needed (`ElytraDeployment.decide`) and asks
`ElytraManager` to do it. Nothing else writes the chest slot. `ElytraFly.replace` and `chestSwap` are
pinned off for the same reason.

**Mending treats XP as scarce.** `MendingManager` only moves a damaged item into the offhand when
`hasXpSource()` is true (orbs are actually nearby, or bottles are carried and enabled). It never
assumes XP will appear, and it restores whatever was in the offhand when it finishes. `AutoMend`'s
behaviour of shuffling items around with no XP source is exactly what this avoids.

**Ender chest work is normal client interaction.** `EnderChestManager` looks at the block and calls
`interactBlock`, then operates on the resulting `GenericContainerScreenHandler` with `InvUtils`
shift-clicks. No packets are forged. If the server refuses to open the chest the module reports
`lastFailure()` rather than pretending it worked. Deposits never touch a category the user did not
select, and a preview of every planned move is logged before the first click.

**Database export/import replaces, it does not merge.** `DatabaseIO` writes every store into one NBT
tag; importing applies each section it finds and leaves stores whose section is missing untouched, so
an older or partial export cannot wipe data it does not describe. Each store is applied inside its own
try/catch, so one malformed section cannot abort the whole import. `DatabaseIO.sectionKeys()` is the
single source of truth for which stores are covered; a headless test pins it against the registered
stores, so a newly added store that is left out of export/import fails the build instead of silently
missing from every backup.

**Saved state** uses `StashManager`, `PlayerTracker`, `EnderChestStore`, `ExplorationStore`,
`StorageRegistry`, `WorkflowManager`, `KitBotStore`, `KitLoadoutStore`, `SixbtServerStore` and
`SixbtPlayerStore` with NBT serialisation, keyed per server address. All ten are registered with
`Systems.add(...)` *before* `Modules.get().add(...)` in `onInitialize()`; registering late discards
their saved data on startup. `ExplorationStore` stores visited chunks as a `long[]` of packed `ChunkPos`
keys and bounds the in-memory set (`ExploredRegions`) so a long session cannot grow without limit.
`SixbtPlayerStore` is likewise capped and evicting, because an anarchy server has effectively unlimited
player names.

**The 6b6t feature set is pure logic plus a thin skin.** `core/sixbt/` holds every decision — the
command catalogue, the chat/kit-request/tpa parsers, the kit-delivery state machine (whose actions are
data: `SendTpa`, `DropItem`, `KillSelf`, …), the request queue, loadouts and the home book — and touches
no Minecraft class, so `sixbtLogicTest` covers it headlessly. The modules and `systems/KitDeliveryService`
only carry the actions out on the client. Keep new 6b6t behaviour on the pure side of that line or the
suite cannot reach it. The kit-maker role is **off by default** (`delivery-enabled`): it teleports the
player to a stranger and then kills them, so it must never be an accidental default. It uses only
ordinary client actions (`/tpa`, `PlayerEntity.dropItem`, `/kill`) — no packet spoofing.

**Persisted records decode tolerantly through `RecordCodec`.** Every store writes a `version` int and
a list of record compounds; `fromTag` reads them via `RecordCodec.decodeList`, which skips an entry that
is not a compound or whose decoder throws, and counts the skips. A single corrupt record must never cost
the user the rest of the database. Each store exposes `lastSkippedRecords()` / `lastLoadedVersion()` and
logs a warning when it skipped anything; `/bh database status` and the GUI Database section report the
total. `RecordCodec` is pure NBT (no Fabric runtime needed), so `coreLogicTest.recordRecovery` covers
the recovery and versioning behaviour headlessly. When you add a store, use `RecordCodec` rather than a
hand-rolled loop, bump the store's `DB_VERSION` when its record shape changes, add it to
`DatabaseIO.SECTIONS`, and update the pinned list in `hardeningTest` — the coverage test fails the build
if the last two are out of step.

**A listener that throws becomes a `SYSTEM_ERROR` event.** `AutomationBus.reportListenerFailure` catches
the exception, logs it, and republishes a `SYSTEM_ERROR` event so the failure is visible without taking
the client down. A `reportingFailure` guard stops that from recursing if a `SYSTEM_ERROR` listener itself
throws, and the bus never reports a failure of a `SYSTEM_ERROR` event.

### Flight deployment policy (do not reintroduce the infinite retry)

`FlightController` (used by `BaseHunterV2`) delegates all retry bookkeeping to the pure
`DeploymentGovernor` in `core/logic`. The rule it enforces, and which a regression test in
`coreLogicTest.deploymentGovernor` pins, is: **re-asserting "I want to fly" must not reset the launch
budget.** `BaseHunterV2` calls `setWantsFlight(true)` on every tick while a route is active, so if that
call reset the retry policy the budget would refill 20 times a second and a takeoff that can never
succeed would be retried forever (this was a real bug; the same shape was fixed for v1 in 2.2.0).

- `setWantsFlight(true)` only resets on a genuine `false -> true` transition.
- A glide that actually begins (`FlightState.GLIDING`) replenishes the budget, so a launch that used
  most of its attempts once does not leave the next interruption with almost none.
- When the budget is spent the controller reports `FLIGHT_DEPLOY_FAILED` exactly once (via
  `shouldReportDeployFailure()`) and stops trying, rather than spamming chat every tick.
- `onDimensionChanged()` / `onDisconnected()` / `beginLeg()` start a fresh budget.

Anything that changes the deploy/recover policy belongs in `DeploymentGovernor`, not in
`FlightController`, so it stays testable without a Minecraft client.

### Bounded collections (long sessions must not leak)

Chunk scanners run for hours and can touch hundreds of thousands of chunks. Every collection that grows
with world size is capped:

- `NewChunks` keeps `newChunks` / `oldChunks` in a `BoundedLongSet` (200k cap, oldest-first eviction).
  The `foundAt` timestamp map is pruned by the set's eviction callback, so an evicted key never leaves a
  stale timestamp; do not prune it separately.
- `StashFinder.lastScanned` is size-capped the same way and pruned on the rescan window.
- `PlayerTracker` caps per-server entries and total sightings; `ExploredRegions` caps the visited-chunk
  set.

When you add a collection keyed by chunk position, player or stash, give it a cap and an eviction rule,
and assert the bound in `hardeningTest`. `BoundedLongSet` is the shared, tested building block: it is
synchronized (chunk scans run off the client thread) and takes an optional `LongConsumer` eviction
callback for keeping a parallel map in step.

### Storage subsystem

Sorting, deposits, withdrawals, restocking, shulker work and workflows all share one architecture, and
the invariants below are what keep six features from corrupting each other's clicks.

**One thing clicks slots: `ContainerInteractionManager`.** Every storage feature plans moves and submits
them to the manager; nothing else calls `InvUtils`. A submitted `Operation` holds an `INVENTORY` lease
for its whole life and only one runs at a time, so two modules can never click concurrently. The manager
is **not** a Module, so `install()` subscribes it to `MeteorClient.EVENT_BUS` itself; without that the
queue never advances. It also cancels everything on `GameLeftEvent`.

**Every operation is verified against a simulation, not assumed.** Before clicking, the manager snapshots
the handler, applies the planned moves through `InventorySimulator`, and stores the expected fingerprint
(`InventorySimulator.fingerprint`). After the clicks it waits for the live container to *match* that
fingerprint before reporting success; a wrong settled state, a missing slot, or a timeout is a failure
with a reason, never a silent success. `OperationState` drives this (opened → clicking → settled →
done/failed). This is why the planners are pure: the same `InventorySimulator` is the spec and the
checker.

**A whole-stack move is three clicks: source, destination, source.** That is a swap for two full stacks
and a top-up for two stacks of the same item. Clicking an empty source at the end is a harmless no-op.
A **partial** move (`SortPlanner.Kind.TRANSFER`) is the exact-quantity case: pick up the whole source
stack, place exactly N one at a time, then put the remainder back. `InventorySimulator` models that
click-for-click, so a partial transfer is verified like any other move, and `SortPlanner.Move.clickCost`
reports the click budget. Do not emit moves that need a different click pattern without extending
`executeMove` and the simulator together.

**Planners are pure and live in `core/logic`.** `StorageCategory` (category order + presets),
`ItemClassifier` (item id → category), `SortPlanner` (inventory ordering with group merging),
`TransferPlanner` (deposit / withdraw / category pull / item pull), `RestockPlanner` (profiles and
needs), `ShulkerInventory` (read shulker contents from the `CONTAINER` component), `ProtectionRules`
(never move these), `WorkflowEngine` (named step sequences), `OperationState`. They take plain data, not
Minecraft types, so `storageLogicTest` covers them headlessly against the real remapped classes.

**`ItemClassifier` matches by suffix, so ordering matters.** A nether star is rare, not nether; the
rare check runs before the nether/end/ore checks. Keep that order when adding rules.

**Slot ids, not indices, everywhere the manager is concerned.** The planners work in screen-handler slot
ids. `StorageOperations` converts a live `ScreenHandler` into planner `Stack`s (`playerStacks`,
`containerStacks`) and the player's empty slot ids, and it bounds every main-inventory scan with
`PlayerInventory.MAIN_SIZE` (36) so the armour and offhand slots are never mistaken for storage.

**Deposits only move categories the user selected.** A category not listed never moves, which is what
keeps elytras, totems and shulkers out of a chest unless asked. `ProtectionRules` is a second gate, and
`ChestSorter.reserveStacks` keeps a survival reserve in the inventory.

**Shulker contents are only known when the component is present.** `ShulkerInventory` reads a carried
box's contents from its item `CONTAINER` component; a box the client has never seen the inside of is
reported as *unknown*, never as empty. Do not treat unknown as empty.

**`StorageRegistry` records where containers are, not what is in them forever.** It persists per server
and dimension through `RecordCodec`, and an observation updates a container's contents only when the
container was actually opened. A `CONTAINER_OP_FAILED` event is published when an operation fails so the
failure is visible in chat, the GUI and Discord.

**Navigation reuses Baritone and degrades without it.** `StorageNetwork` and `RestockManager` walk with
`BaritoneHelper` when it is available and refuse with a clear message when it is not; they never
reference `baritone.*` types directly.

### Automation engine, rules, chat and kits

**One queue owns long-running work: `AutomationEngine` + `TaskQueue`.** A `Task` carries a name,
`Task.Category`, `Priority`, an optional requested/attained quantity, an attempt budget and a state.
The engine runs **one task at a time**, deduplicates a submission by name+category, re-queues a
failed-but-retryable task, and reports every transition on the bus (`TASK_*`). `Task.fail` is guarded
against a terminal state, so a late state write cannot resurrect a finished task. A task's runner is
built lazily from a per-category factory registered in `BaseHuntingAddon.onInitialize`
(`engine.registerFactory(Task.Category.WITHDRAW, ...)`); if you add a category, register its factory
there or the task will be submitted and immediately fail with "no runner".

**`AutomationEngine` is not a Module.** Like `AutomationManager` and `ContainerInteractionManager`,
`install()` subscribes it to `MeteorClient.EVENT_BUS` itself and it must be installed last in
`onInitialize`, after `NavigationManager`, so its subscriptions are in place before any module can
submit a task.

**Rules are edge-triggered.** `RuleSet.evaluateTick` fires a rule only on a false→true edge of its
`Condition`, subject to the rule's cooldown; a condition that stays true through the cooldown fires
once the cooldown expires rather than losing the edge. `Condition.parse` returns an *inert* condition
for a malformed expression (a bad rule does nothing, it never throws). Facts come from
`PlayerFacts.snapshot()`; add a fact there rather than reaching into `mc` from a rule action.

**The chat router only ever parses locally typed lines.** `ChatRouter.parse` returns null unless the
origin is `Origin.LOCAL_PLAYER`; a message received from another player is data and is never executed.
`ChatPermissions` refuses any command above `READ_ONLY` unless it is listed in `allowed-destructive`
or `allow-all-destructive` is on. Keep that gate when adding a chat command: register the command with
its permission level, and do not execute a destructive action from a lower level.

**Kit cooldowns are per server and are a client-side memory only.** The server owns the real cooldown.
`KitBotStore` persists the last-claim time per server through `RecordCodec` (a malformed kit is skipped,
never fatal), and `KitBot` adopts a server-reported cooldown rather than ignoring it. Never send a claim
the client believes is on cooldown.

**Exact-quantity transfers are tasks, not loops.** `AutoStorage` submits a `StorageAutomation` task
rather than clicking inline, so it is serialised, interruptible and reports partial/failed honestly.
Protection is enforced in the operation layer via `ProtectionRules`/`ProtectionGuard`, not trusted from
the caller, and `ItemFilter` matching is namespace-insensitive (`minecraft:elytra` == `elytra`).

**The command terminal is local-only input and delegates to the managers.** `CommandTerminal` is the
runtime half: it builds a `CommandRegistry` whose handlers call the *same* public methods the GUI and
`/bh` call (`AutoStorage.withdraw`, `StorageNetwork.gotoContainer`, `BaseHunterV2.start`, …), so the
three entry points cannot drift apart. The parser, registry, history and aliases in `core/terminal/`
are pure and covered by `TerminalLogicTest`. Two rules when adding a terminal command:

- **Register it with a permission level, and give a subcommand its own level when it differs.** The
  registry checks `levelFor(args)` — the level of the subcommand actually being run — so `storage
  list` can be `READ_ONLY` while `storage withdraw` is `MOVEMENT`. Mark anything that changes state
  `.confirm()` so it needs a second Enter; any intervening command disarms it.
- **Never parse chat text in the terminal.** Its input comes from the GUI text box or `/bh terminal
  run`; a chat message another player sent is data and must never reach the registry. `chat run` feeds
  the chat command registry (which keeps its own LOCAL_PLAYER + permission gate), it does not bypass it.

The terminal has no shell, no filesystem and no arbitrary code execution — every command is an addon
operation.

## Version pins

`gradle/libs.versions.toml` holds all of them: Minecraft 1.21.11, Yarn `1.21.11+build.3`, Fabric
Loader 0.18.2, Fabric Loom 1.14-SNAPSHOT, Meteor `1.21.11-SNAPSHOT`, Baritone `1.21.11-SNAPSHOT`.
Meteor and Baritone come from `maven.meteordev.org`. Bumping the Minecraft version generally means
bumping all of these together.
