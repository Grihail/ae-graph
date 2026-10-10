# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
