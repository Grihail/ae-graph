# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [1.1.2]

### Changed

- Remote Crafting Bus works like an ME Interface whose machines stand in network B. Network A sees the patterns of the faced interface with their inputs, crafts or gathers the inputs in its own plan on the same CPU and pushes each run through the bus into network B's interface and machine. The products come back from network B's storage, or straight from the machine into network A. Network B no longer calculates or runs jobs for network A and needs no CPU. This replaces the opaque "nothing -> item" step of 1.1.0-1.1.1, which could stall when network B lacked items. Cancel jobs that use the bus before updating.

## [1.1.1]

### Fixed

- Remote Crafting Bus: when network B lacks items for an order, the bus now takes them from network A's storage and moves them to network B once per order. Before, the order waited forever. If neither network has the items, the bus GUI lists what is missing.

## [1.1.0]

### Added

- Works with the original Applied Energistics 2 rv3-beta-6: the mod is now built against it.

### Changed

- Recipes are much more expensive, in line with what the items do:
  - Graph Co-Processor: built around a Crafting Co-Processing Unit (it accelerates its CPU by +64 by default), plus 4 Engineering, 2 Calculation and 2 Logic Processors.
  - Fast Feed Card: Acceleration Card + 2 Advanced Cards + 4 Engineering Processors + 2 Fluix Pearls.
  - Remote Crafting Bus: 2 Storage Buses + ME Interface + 4 Engineering Processors + 2 Fluix Pearls.

## [1.0.0]

First public release. Requires Applied Energistics 2 rv3-beta-690-GTNH on Minecraft 1.7.10.

### Added

- Graph planner: builds a recipe graph from every encoded pattern in the network and finds the most efficient whole plan (bounded simplex + branch and bound), with byproduct recycling, value-based item costs, the "stock first" rule and surplus penalties.
- Graph Co-Processor block: enables graph planning for its crafting CPU, acts as a co-processing unit, and shows the plan graph on right-click.
- Fallback to AE2's own plan with a chat message explaining why.
- Execution stability fixes for all CPUs: unneeded items go straight to ME, stall recovery, ghost-wait cleanup.
- Fast Feed Card: an ME Interface upgrade that flushes a stuck buffer so the CPU can push runs back to back.
- Remote Crafting Bus: orders crafts from an ME Interface of another, independent ME network, with an exclusive "Priority" mode.
- `/aegraph` command and `config/aegraph.cfg`.
- English and Russian localization.
