# Changelog

All notable changes to the Base Hunting Client. Format loosely follows [Keep a
Changelog](https://keepachangelog.com/); versions follow [Semantic Versioning](https://semver.org/).

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
