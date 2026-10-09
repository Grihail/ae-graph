# AE2 Graph Crafting

**English** | [Русский](README.ru.md)

Addon for Applied Energistics 2 **rv3-beta-690-GTNH** (Minecraft 1.7.10). Other AE2 versions are not supported: the mod is a coremod and patches AE2 classes.

A crafting CPU with a Graph Co-Processor plans the whole job at once using every pattern in the network. It picks the cheapest combination of recipes, feeds byproducts of one step into other steps and uses recycling loops when they save resources. Stock AE2 picks one recipe per item and ignores byproducts.

## Requirements

- Minecraft 1.7.10, Forge 10.13.4.1614
- Applied Energistics 2 rv3-beta-690-GTNH

Install on both client and server.

## Items

### Graph Co-Processor

Part of a crafting CPU multiblock, placed like a regular Crafting Unit. A CPU with a co-processor runs the graph plan, other CPUs run the stock AE2 plan. Without a co-processor in the network the mod doesn't change crafting.

Also works as a Crafting Co-Processing Unit (+64 by default, `coprocessorAcceleration`).

Right-click opens the graph of the current or last plan: raw materials on the left, target on the right, purple nodes are patterns, orange arcs are loops. Shift + right-click with an empty hand opens the normal CPU status.

```text
C L C    C - Calculation Processor
E U E    L - Logic Processor
C L C    E - Engineering Processor
         U - Crafting Unit
```

### Fast Feed Card

Upgrade for the ME Interface (block or part). Normally, when a machine doesn't accept the whole pattern, the interface stalls for a few seconds before taking the next one. With the card the CPU pushes patterns into the machine back to back.

Shapeless: Advanced Card + Engineering Processor + Acceleration Card.

### Remote Crafting Bus

Lets one ME network order crafts from another without merging them. Put the bus on network A facing an ME Interface of network B. The crafts of that interface appear in network A's terminals. Network B calculates and runs them on its own CPUs with its own items, then the bus delivers the result to network A.

The **Priority** button in the bus GUI:

- **ON**: these items are crafted only through the bus. Network A's own patterns for them are ignored.
- **OFF**: network A uses its own patterns first, network B covers the rest.

Shapeless: Storage Bus + ME Interface (part) + Engineering Processor.

## Other changes

These apply to all crafting CPUs, with or without a co-processor:

- Items that no remaining step needs go to ME storage immediately instead of waiting until the job ends.
- If a job makes no progress for 60 seconds, the CPU takes the missing items from ME. If the target was already delivered, the job is closed and the CPU is freed.

If the graph can't build a plan, AE2's own plan is used and the chat says why.

## Configuration

`config/aegraph.cfg`. Command: `/aegraph status|on|off|log|reload` (op only).

| Option | Default | Description |
| --- | --- | --- |
| `enabled` | true | Enable the mod |
| `requireCoprocessor` | true | If false, all CPUs use the graph plan |
| `coprocessorAcceleration` | 64 | Co-processing bonus of the Graph Co-Processor |
| `fallbackToAE2WhenMissing` | true | Use AE2's plan when the graph lacks items and AE2 doesn't |
| `returnUnneededItems` | true | Send items no step needs to ME right away |
| `stallSeconds` | 60 | Seconds without progress before stall recovery, 0 to disable |
| `substitutes` | true | Handle patterns with substitution enabled |
| `fluidCostPerMb` | 0.001 | Cost of 1 mB of fluid |
| `storedIntermediateFactor` | 0.9 | Cost multiplier for crafted items already in ME |
| `surplusPenalty` | 0.01 | Penalty for leftover byproducts |
| `stockFirstTolerance` | 0.05 | How much worse a plan may be to use up stored intermediates, 0 to disable |
| `remoteCraftCost` | 100 | Cost of crafting through a Remote Crafting Bus with Priority off |
| `itemCostOverrides` | | `modid:name[@meta]=cost` |
| `timeBudgetMillis` | 4000 | Time limit for one calculation |
| `maxPatterns` | 3000 | Maximum number of patterns in a graph |
| `logPlans` | false | Write plans to the log |

## Known issues

- The craft confirmation screen shows a flat list instead of AE2's tree.
- Machines with random outputs are planned by what is written in the pattern.
- Network B accepts orders from the Remote Crafting Bus only on a free CPU. If all of them are busy, the order waits.
- Don't link two networks with buses in both directions for the same item with Priority on: the order will loop forever.

## Building

Gradle runs on JDK 25, the mod is compiled with JDK 8 (toolchain).

```sh
JAVA_HOME=<jdk-25> ./gradlew build
```

Run with `-Daegraph.selftest=true` to build a test network at spawn and run in-world tests. The result is logged as `AEGRAPH SELFTEST: PASS/FAIL`.

## License

[LGPLv3](COPYING.LESSER). Not affiliated with Applied Energistics 2 or GT New Horizons.
