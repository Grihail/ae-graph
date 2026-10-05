# AE2 Graph Crafting

**English** | [Русский](README.ru.md)

An addon for **Applied Energistics 2 rv3-beta-690-GTNH** (Minecraft 1.7.10, Forge 10.13.4.1614).
It replaces AE2's stock autocrafting calculation with a planner that builds a **graph of every encoded pattern** in the network and searches for the most efficient plan as a whole. Byproducts of one branch feed other branches, alternative routes are compared, and recycling loops are used when they pay off.

Example: HBM NTM bedrock ore. Stock AE2 builds a single chain `ore → primary → output`. The graph finds washing (×2), the acid and solvent routes (another ×2 and ×2) and byproduct reprocessing on its own. On a model of the chain built from HBM's sources, that is **80 ore for 640 outputs instead of 640**.

> **Compatibility:** the mod is a coremod that patches AE2 classes. It is built and tested against **AE2 rv3-beta-690-GTNH** only. Other AE2 versions may not work.

## Usage

1. Craft a **Graph Co-Processor** (AE2 creative tab):

   ```text
   C L C      C — Calculation Processor
   E U E      L — Logic Processor
   C L C      E — Engineering Processor, U — Crafting Unit
   ```

2. Build it into a crafting CPU multiblock like a regular crafting unit (the CPU must stay a solid cuboid).
3. Request crafts as usual: terminal, interface with a crafting card, export bus, level emitter, OC/CC.
   - CPUs **with a co-processor** run the graph plan.
   - Regular CPUs run AE2's stock plan. It is calculated in parallel, so old CPUs keep working as before.
   - With automatic CPU selection, a graph job goes to a free CPU with a co-processor. If there is none, it goes to a regular CPU with the stock plan.
4. **Right-click the co-processor** to open the graph view (Shift + right-click with an empty hand shows AE2's normal CPU status). The block is rendered by AE2's renderer, like co-processing units and storage: in a formed CPU it connects to its neighbours, and the symbol glows when powered. The view shows the plan its CPU is running (or ran last). If the CPU is idle, it shows the last plan calculated in the network (preview).
   - Raw materials from ME are on the left, the target on the right. Squares are items, purple nodes are patterns with their run count.
   - Orange arcs are recycling loops. A green bar under a pattern shows execution progress.
   - The header shows how much is taken from ME and how much stock AE2 would take.
   - Mouse: drag to pan, wheel to zoom, `R` to reset the view. Hover a node for details.

If the network has no CPU with a co-processor, the mod changes nothing.

## How it works

- **Graph.** Backward closure from the target: every pattern that produces what is needed, directly or through intermediate steps.
- **Optimization.** A linear program (bounded simplex): minimize the "cost" of what is taken from ME subject to a balance constraint for every item. Fluids (ae2fc) are counted in mB.
- **An item's cost is its value, not its count.** An auxiliary problem is solved first, and its dual variables (shadow prices) give the marginal value of every item. Example: if A yields 8 resources and an item B, and B yields 2 resources, then A is worth 10 resources and B is worth 2. So a small order doesn't burn A and pile up B, and stored B is used first. Crafted intermediates in ME are slightly cheaper (`storedIntermediateFactor`), so leftovers get used before fresh raw materials.
- **"Stock first" rule (strict, second optimization level).** First, the best achievable efficiency is found. Then, among plans at most `stockFirstTolerance` (5%) worse than that, the one that produces the fewest intermediates already stored in ME is chosen. So accumulated byproducts are consumed before patterns that replenish them are run, and penalties for junk (copper etc.) don't override that. Example: if A yields 8 resources and B, and B yields 2 resources, then with 8880 B in stock any order up to 17,760 resources is made from B only, and A starts only when B runs out. The rule applies to intermediates (items the plan produces anyway), not to raw materials: reprocessing waste back into raw materials (crumbs → BASE) is not forbidden by it.
- **Surplus penalty.** Extra resources and byproducts are penalized (`surplusPenalty`). Of two equally efficient plans, the one that leaves less junk wins.
- **Whole runs.** A pattern can't run 2.4 times, so an integer plan is found by branch and bound: branching on `x ≤ ⌊x⌋` and `x ≥ ⌈x⌉` with pruning by the LP bound. On real chains this gives the exact optimum; on huge graphs the search is time-limited and returns the best plan found.
- **Reconciliation after rounding.** A run rounded up takes more inputs than calculated. So the plan is re-solved with the runs fixed: what is missing is crafted by earlier steps, taken from stock or made by another route. If an item is scarce, its consumer runs one time less and the difference is made another way. This way "2 items missing" due to rounding doesn't happen.
- **Surplus is measured by value, not count.** So the plan doesn't run pointless junk reprocessing like "4 byproducts → 1".
- **Feasibility check.** The plan is replayed the way AE2's CPU executes it. If a loop or a catalyst can't start, a "seed" is added. It is borrowed from ME (and returned at the end) or crafted by a separate sub-plan.
- **Execution.** AE2's stock CPU is used: it greedily runs any pattern whose inputs are ready, which naturally follows a branching, looping plan. Job completion is also fixed: the CPU closes the job even if the target arrived before the last byproduct-processing steps.
- **Substitutions.** The graph treats a pattern with "substitute" enabled the same way AE2's CPU does: any OreDictionary equivalent or a different damage value is accepted instead of the recorded item, and for crafting patterns only those the recipe still works with. In the view this is a "Substitute" node and an "any accepted variant" item.
- **Fallback.** If the graph can't make the target or lacks something that stock AE2 covers (emitters etc.), AE2's plan is used. This is never silent: the chat says why ("the graph lacks …"), and the co-processor shows the calculated graph with a red "running AE2's plan: …" note. If all CPUs with a co-processor are busy, the chat warns that the job goes to a regular CPU.

## Execution stability

AE2's stock CPU puts all pattern outputs (except the target itself) into its internal buffer and releases them to ME only when the whole job finishes. On a long job, byproducts and intermediates aren't visible in ME until the end. And if even one step can't run — for example, a machine returned fewer byproducts than the pattern says — the job never finishes: items are locked in the CPU, the status "waits" for items that are no longer being made, and the CPU counts as busy and takes no new jobs (graph jobs included — they go to a regular CPU). The mod fixes this on **all** CPUs:

- **Unneeded items go straight to ME.** An incoming item that no remaining step needs goes directly to storage; surplus in the CPU buffer is sent to ME too. Finished resources and byproducts appear in the system while crafting is still running. (`returnUnneededItems`)
- **Stall recovery.** If a job doesn't move for `stallSeconds` (60 s by default), the CPU pulls from ME what the remaining steps lack, and a one-run "seed" for looped steps. If ME doesn't have it and the order has already been delivered, the job is closed and everything in the buffer is returned to ME. If the target hasn't been delivered yet, the log says exactly what is missing.
- **Ghost waits.** If the job is done but the CPU still waits for items that never came, the wait is cleared after `stallSeconds` and the CPU is released.

## Machine feed speed

Stock AE2 has two reasons to feed patterns slowly even into a fast machine:

- **Stuck interface buffer → Fast Feed Card.** If not everything fits into the machine on a push (for example, a neighbouring block "agreed" to accept an item but didn't), the rest falls into the interface buffer. While the buffer isn't empty, the interface refuses new patterns ("something is stuck") and drains the buffer only on its "sleepy" tick — up to several seconds. The result is one pattern every 1–3 seconds.
  The card goes into the upgrade slot of an ME Interface (block or part, 1 card). An interface with the card flushes its buffer as soon as the CPU wants to push the next pattern, so the CPU pushes runs back to back while the machine accepts them — effectively as a stack. Recipe (shapeless): Advanced Card + Engineering Processor + Acceleration Card. On the test rig, 9 runs sped up from 558 ticks to 2.
- **CPU operation limit.** AE2 lets a CPU make (co-processing units + 1) pushes per 3 ticks. The Graph Co-Processor also works as a co-processing unit (`coprocessorAcceleration`, +64 by default). The value applies when the CPU multiblock forms: after changing the config, rebuild the CPU (break and place a block) or rejoin the world. On regular CPUs, use AE2 co-processing units.

Beyond that, speed is limited by the machine itself. To go faster, place several identical machines around one interface or several interfaces with the same pattern — the CPU distributes runs in parallel.

## Remote Crafting Bus

Connects two **independent** ME networks without merging them. The bus is placed on a cable of the main network A and faces an ME Interface (block or part) of another network B. The crafts of **that** interface — every output of each of its patterns, byproducts included — show up in network A's terminals as orderable. Network B stays independent: its own autocrafting, interfaces and logic keep working as before.

Recipe (shapeless): Storage Bus + ME Interface (part) + Engineering Processor.

How an order runs:

1. Network A sees an item from network B as a single step "nothing → item". Its calculator (graph or stock AE2) **does not look inside network B**: it doesn't see B's patterns, storage or chain. The calculation in A stays small no matter how many steps B has.
2. When network A's CPU reaches this step, it hands all runs to the bus at once (rather than one per tick). The bus orders the item in network B. Network B calculates the whole chain with its own patterns and resources and runs it on **its own** free CPU.
3. Network B's CPU hands finished items to the bus, and the bus passes them into network A. There the waiting CPU picks them up like a normal crafting result.

Storage and patterns of the networks don't mix. Network A doesn't see network B's items, network B doesn't see network A's patterns. Each network calculates and ticks its own crafts.

**Right-click the bus** to open its window:

- Link status and the list of items the interface offers to network A.
- Requests: what is waiting to start in network B, what is crafting (delivered / ordered), what is waiting to be sent to network A.
- The reason for waiting, if any: network B lacks resources or has no free CPU. The bus retries every 5 seconds.
- The **Priority** button:
  - **ON** — autocrafting of these items in network A goes **only** through this bus to network B. Network A's own patterns for these items are hidden from both calculators.
  - **OFF** — network A crafts on its own first (with its patterns and stock), and network B covers what A can't make. For the graph planner a remote craft costs `remoteCraftCost` × the item's value; for AE2's calculator it comes last by priority.

Cancelling: if the job in network A is cancelled, the bus drops the request from its queue and cancels it in network B within 5 seconds. If the job is cancelled in network B, the bus orders the remainder again. Bus requests are saved with the world: after a rejoin, crafting in network B continues and reaches network A. If the bus is broken, items already delivered from B but not yet accepted by network A drop on the ground.

## Commands and config

`/aegraph status|on|off|log|reload` (op). Config: `config/aegraph.cfg`.

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | true | Master switch |
| `requireCoprocessor` | true | `false` — graph plan on all CPUs, no co-processor needed |
| `coprocessorAcceleration` | 64 | How many parallel pushes a co-processor gives, like AE2 co-processing units |
| `fallbackToAE2WhenMissing` | true | Use AE2's plan if it is complete and the graph lacks something |
| `returnUnneededItems` | true | The CPU sends items no remaining step needs to ME right away |
| `stallSeconds` | 60 | Seconds without progress before the CPU pulls missing items from ME or closes a finished job; 0 — off |
| `substitutes` | true | Account for substitutions in patterns with the "substitute" flag |
| `fluidCostPerMb` | 0.001 | Cost of 1 mB of fluid (1 bucket = 1 item) |
| `storedIntermediateFactor` | 0.9 | Discount for crafted intermediates stored in ME |
| `surplusPenalty` | 0.01 | Penalty per unit of surplus or byproduct (fraction of the item's base cost) |
| `stockFirstTolerance` | 0.05 | "Stock first": how much (fraction) worse a plan may get to consume stored intermediates; 0 — off |
| `remoteCraftCost` | 100 | Cost of crafting through a Remote Crafting Bus without priority (fraction of the item's value). The higher, the stricter "own network first" |
| `itemCostOverrides` | — | `modid:name[@meta]=cost` — e.g. make valuable raw materials more expensive |
| `timeBudgetMillis` | 4000 | Time for optimizing one request (in a background thread) |
| `maxPatterns` | 3000 | Graph size limit |
| `logPlans` | false | Write plans to the log |

## Limitations

- In a substituting pattern the CPU itself chooses which accepted item to take and may take one the plan assigned to another step. Stall recovery (pulling from ME) handles that.
- The craft confirmation window shows a list rather than AE2's tree (the tree and "pattern optimization" exist only in AE2's calculator). Look at the graph on the co-processor.
- Machines with probabilistic outputs are planned by what the pattern says, as in stock AE2.
- The Remote Crafting Bus orders one item in network B. If B's pattern also makes something else, those byproducts stay in network B. With priority on, the bus claims **all** outputs of the interface's patterns in network A, byproducts included. If network A should make any of them itself, turn priority off.
- Network B takes an order from network A only on a **free** CPU: AE2 merges a job into a busy CPU, and the result wouldn't go to the bus. If all of network B's CPUs are busy with their own crafts, the request waits in the bus.
- Network A always considers a remote item available. Whether network B has enough resources is found out only at execution: if not, the job in A waits and the bus shows what is missing.
- Don't connect buses in a ring (A → B and B → A for the same item, both with priority): the request will bounce between the networks forever.

## Building

Gradle (RetroFuturaGradle 2.0.2) needs JDK 25; compilation uses JDK 8 (toolchain).

```sh
JAVA_HOME=<jdk-25> ./gradlew build
```

Output: `build/libs/AEGraphCrafting-1.7.10-1.0.0.jar`. The mod is required on both client and server.

See [CHANGELOG.md](CHANGELOG.md) for the release history.

Tests: `./gradlew test` (planner, graph layout). In-world integration test on a real server with AE2: launching with `-Daegraph.selftest=true` builds a test ME network near spawn (y=200), runs the scenarios and writes `AEGRAPH SELFTEST: PASS/FAIL` to the log. A second network with a Remote Crafting Bus is tested there too. The test stops the server in the middle of a remote craft. Running again on the same world with `-Daegraph.selftest=true -Daegraph.selftest.resume=true` checks that the craft completes after the reload.

## License

[GNU Lesser General Public License v3.0](COPYING.LESSER) (LGPLv3), which supplements the [GNU GPL v3](COPYING).

Applied Energistics 2 is © AlgorithmX2 and contributors; the GTNH fork is maintained by GT New Horizons. This addon is not affiliated with either project.
