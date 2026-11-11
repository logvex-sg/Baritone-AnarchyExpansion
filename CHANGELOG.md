# Changelog

All notable changes to the Base Hunting Client. Format loosely follows [Keep a
Changelog](https://keepachangelog.com/); versions follow [Semantic Versioning](https://semver.org/).

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
