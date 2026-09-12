# Timing-driven RWRoute on Versal: implementation plan

Branch: `versal-timing-driven` (from `versal-timing-model` at e824ca3f).

Goal: `RWRoute --timingDriven` on a Versal (xcv80) design with the same behaviour the flow has on
UltraScale+: per-connection criticality from a static timing analysis of the design, a per-node
delay in the A* cost, delay-weighted rerouting of the critical connections, and the critical path
report at the end. Everything the UltraScale+ flow does in `rwroute` and `timing` is kept; Versal
plugs in behind the three seams where UltraScale+ data enters. The Versal data-path model in
`timing.versal` (tables under `timing/versal/`, accuracy in the project NOTES.md) supplies the
numbers.

## 1. What the UltraScale+ flow consists of (and what is reused)

| Piece | Where | Versal plan |
|---|---|---|
| Static timing analysis: `TimingGraph` (JGraphT) of `TimingVertex` (cell pins) and `TimingEdge` (logic + intra-site + net delay), arrival/required propagation, super source/sink, `Connection.calculateCriticality` reading slack off the connection's edges | `timing/TimingGraph.java`, `TimingVertex.java`, `TimingEdge.java`, `TimingManager.calculateArrivalRequiredTimes/calculateCriticality`, `rwroute/Connection.java:185-239` | **reused unchanged**; only the graph population is Versal-specific (seam A) |
| Per-node delay used by the search: `RouteNodeTimingDriven.delay` computed once per rnode from `DelayEstimatorBase.getDelayOf(Node)` through `RouterHelper.computeNodeDelay`, gated by `RouteNode.isExitNode` (UltraScale+ intent codes); `DelayEstimatorBase.isLong` / `getExtraDelay` (45 ps long-after-long) | `rwroute/RouteNodeGraphTimingDriven.java:105-142`, `RouterHelper.java:619-624`, `RouteNode.java:719-737`, `timing/delayestimator/DelayEstimatorBase.java:99-138` | **Versal estimator** behind the same calls (seam B) |
| Cost function: `dlyWeight * (childRnode.getDelay() + getExtraDelay(...))` in the known cost, `estDlyWeight * (dx*0.32 + dy*0.16)` in the heuristic, weights from criticality | `RWRoute.java:1704-1710, 2080-2084, 2167-2171` | **reused**; the two heuristic constants come from the estimator (seam C) |
| Connection route delay = sum of rnode delays + extras (`Connection.getRouteDelay`, `TimingManager.patchUpDelayOfConnections`, `updateIllegalNetsDelays`), pre-route estimate (`estimateDelayOfConnections`, 113 ps + first hop) | `Connection.java:241-250`, `TimingManager.java:120-155`, `RWRoute.java:908-928` | **reused**; optional exact per-net refresh on top (step 3) |
| Reroute selection by criticality, timing weight schedule, critical path printout | `RWRoute.java:1176-1226`, `TimingManager.getCriticalPathInfo` | **reused unchanged** |
| Construction: `createRouteNodeGraph()` builds a `DelayEstimatorBase`, `createTimingManager()` builds `TimingManager(design, ..., estimator)`; overridden in `PartialRouter`, `CUFR`, `PartialCUFR`; `TimingAndWirelengthReport` builds both itself | `RWRoute.java:298-317`, `PartialRouter.java:174-190`, `CUFR.java:93-111`, `PartialCUFR.java:73-92`, `TimingAndWirelengthReport.java:59-64` | one series-aware factory each, called from all five places |
| Clock: skew is not modelled (`clkskew = 0`); the optional `--clkRouteTiming` template steers `routeClkWithPartialRoutes` | `TimingManager.java:197`, `TimingGraph.java:1531-1552, 1753-1793`, `RWRoute.java:478-486` | parity = no skew; `symmetricClkRouting` already routes Versal clocks; skew is step 6 (optional) |
| DSP: `--dspTimingDataFolder` pin remaps | `TimingGraph.java:1589-1602` | not needed: `VersalTimingGraph` has DSP58 stage arcs built in |

Why timing-driven does not work on Versal today: there is no series guard, but (a) `TimingModel`
would parse `timing/versal/intersite_delay_terms.txt` with the UltraScale+ reader, (b)
`DelayEstimatorBase.getTermInfo` classifies nodes by UltraScale+ wire names and intents, (c)
`RouteNode.isExitNode` is false for every Versal intent, so every rnode delay is 0, and (d)
`TimingGraph.addNetDelayEdges` uses `TimingModel.calcDelay`.

## 2. Design

### Seam A: populating `TimingGraph` from the Versal model

`VersalTimingGraph` (timing.versal) already builds the data-path graph of a placed design: logic
arcs per cell configuration, constant pruning, DSP58/LUTCY/SRL handling, net edges from the
routed tree with driver/sink intra-site terms. Rather than teaching `Connection` a second edge
type, convert it into the JGraphT `TimingGraph` the router already understands:

- New `VersalRWTimingGraph extends TimingGraph` (package `timing.versal`, or `timing` if the
  hooks are package-private). Overrides:
  - `build(isPartialRouting, targetNets)`: build a `VersalTimingGraph` on a single-corner
    `VersalTimingModel(device, SLOW_MAX, terms, delayModel)` (constructor exists,
    `VersalTimingModel.java:308`), then copy it: one `TimingVertex` per Versal vertex (name
    `cell/pin`; `setFlopInput()` on endpoint vertices so `buildSuperGraphPaths` picks them as
    sinks; launch vertices have in-degree 0 and become sources), one `TimingEdge` per edge:
    `logic` arcs set `logicDelay`; `intrasite` edges set `intraSiteDelay` = `netDelay` = the
    term (as `addNetDelayEdges` does for LUT->FF inside a slice); `net` edges set
    `intraSiteDelay` = driver + sink intra-site terms and `netDelay` = intra-site +
    interconnect (0 when the net is unrouted). Edge weight = total.
  - clock-to-Q and setup check: `buildSuperGraphPaths` creates zero-delay edges
    superSource->launch and endpoint->superSink. Set the launch edge's logic delay to the
    Versal clk-to-Q (`VersalTimingGraph.clkToQ`) and the endpoint edge's to the setup check
    (`Vertex.check`), so the required-time normalisation sees the same path delay
    `VersalSlackAnalysis` uses (minus clock arrival). Needs a small hook in `TimingGraph`:
    a protected `superEdgeDelay(TimingVertex, boolean isSource)` returning 0 by default.
  - `addNetDelayEdges(Net)`: recompute one net's edges from its PIPs
    (`VersalTimingModel.calcNetDelays`). Used by `PartialRouter.java:693` for nets routed
    outside the router and by step 3.
  - `setTimingEdgesOfConnections(List<Connection>)`: key the sink `SitePinInst` directly
    (`connection.getSink()`), skipping the `EDIFHierPortInst -> SitePinInst` map the
    UltraScale+ code needs. For that, `VersalTimingGraph.Edge` records the sink `SitePinInst`
    of every `net` edge (it is known in `planNet`, `VersalTimingGraph.java:757-770`; add a
    field). Hard-block sinks matched by name (DSP `C44` <-> `C_44_`) keep the site pin too.
- `VersalTimingGraph.planNet` needs an "unrouted" mode: for a sink whose site pin has no
  route, still create the edge with interconnect 0 and the intra-site terms (today it counts
  `unrouted` and creates nothing). RWRoute starts from an unrouted design and
  `estimateDelayOfConnections` then fills in `setTimingEdgesDelay`. Partial routing keeps
  the real delays of nets that already have PIPs (same as UltraScale+).
- `TimingManager`: the router constructor branches on `design.getSeries() == Versal`:
  `timingModel = null`, `timingGraph = new VersalRWTimingGraph(...)`, skip
  `timingModel.build()`. Guard the two `timingModel` uses in the verbose critical-path
  breakdown (`getSourceIntraSiteDelayTerm`, `getSinkIntraSiteDelayTerm`) with a Versal
  equivalent (`VersalTimingModel.driverIntraSiteDelay/sinkIntraSiteDelay`) or skip them.
  `getDesignTimingRequirement` (parses `-period` from the XDC in the DCP) is series-neutral;
  `VersalTimingReport.periodFromConstraints` does the same and can be dropped later.
- Memory: keep the `VersalTimingGraph` only while copying (its `float[4]` arrays become
  `float[1]` with the single-corner model), then release it; the `VersalTimingModel` stays for
  step 3. Expect JGraphT to cost more per edge than `VersalTimingGraph.Edge` (intrusive edge
  maps): the 8x8's 3.0 M vertices / 12.2 M edges are a few GB either way; the 32x8 is 4x.
  Measure in step 5.

### Seam B: a Versal per-node delay for the search

The Versal hop delay is contextual (grandparent, parent's child set, child's child set,
`VersalTimingModel.java:387-476`), so it cannot be evaluated when A* expands a node. What the
router needs is a fixed delay per node, as on UltraScale+. Use the marginal:

- New table `timing/versal/node_delay_terms.txt`, written by a new `fit_node_delays.py`
  (project root) from the training runs' `<run>.nodes.csv` (per-node increments, column
  `slow_max_ps`; same runs as `run_versal_flow.sh fit`, plus rapidsa8x8b if its nodes are
  parsed). Rows:
  - `NODE <class> <mean ps> <sd> <samples>`: mean increment into a node of that class,
    class = `VersalTimingModel.nodeClass` (intent + horizontal wire family + dedicated
    family), so the same Java classifier serves lookup;
  - `INTENT <intent> <mean ps>`: fallback by bare intent code;
  - `LONGLONG <ps>`: mean extra of a long node entered from a long node over the marginal
    (replaces the UltraScale+ 45 ps);
  - `HEURISTIC <ps per tile H> <ps per tile V>`: from the HLONG10/VLONG12 marginals divided
    by their spans (replaces 0.32/0.16);
  - `SOURCE_HOP <ps>`: mean driver intra-site + first hop (replaces the 113 ps in
    `estimateDelayOfConnections`).
  The script also reports, on a held-out quarter of the nets, the per-sink error of the
  marginal sum along Vivado's route against `get_net_delays`, next to the full model's
  (NOTES.md: sd 12-75 ps per design). That number is the price of the per-node
  approximation and decides whether step 3 is needed for good criticality.
- New `VersalDelayEstimator extends DelayEstimatorBase<InterconnectInfo>`:
  - `DelayEstimatorBase` gets a protected constructor that skips `TimingModel` (the current
    one builds the UltraScale+ model in the constructor, `DelayEstimatorBase.java:81-90`).
  - `getDelayOf(Node)`: `NODE` class lookup, `INTENT` fallback, else 0. Optionally add the
    node's own hard-block-column crossing term (`VersalTimingModel.tileTerm(Node)` is a pure
    per-node function; it is charged on the child in the full model, so subtract the class
    mean crossing from the marginal if it is added). Not in the first version.
  - `isLong` / `getExtraDelay` are static and used without an estimator reference
    (`Connection.getRouteDelay`, `TimingManager.patchUpDelayOfConnections`,
    `RWRoute.evaluateCostAndPush`). Extend `isLong` to `NODE_HLONG6/10`, `NODE_VLONG7/12`;
    `getExtraDelay` returns the UltraScale+ 45 ps for UltraScale+ intents and the `LONGLONG`
    value (a static set when the Versal estimator loads its table) for Versal long intents.
- `RouterHelper.computeNodeDelay`: on Versal every node carries its own marginal; skip the
  `isExitNode` gate (which encodes the UltraScale+ node-group convention) when the estimator
  is the Versal one, e.g. `estimator.isExitNode(node)` with the UltraScale+ default calling
  `RouteNode.isExitNode`.
- `RouteNodeGraphTimingDriven`: the `maskNodesCrossRCLK` exclusion sets are UltraScale+ wire
  names; on Versal they resolve to empty sets and the option is a no-op (fine, document it).
  The `getDelay() > 10000` U-turn sentinel never triggers on Versal.

### Seam C: heuristic constants

`RouteNodeGraphTimingDriven` exposes `getHeuristicPsPerTileX/Y()` from the estimator
(UltraScale+ returns the current 0.32/0.16); `RWRoute.evaluateCostAndPush` reads them once
per connection into `ConnectionState`. `estimateDelayOfConnections` reads `SOURCE_HOP` the
same way.

### Factories

`RouterHelper.createDelayEstimator(Design, RWRouteConfig)` and
`TimingManager.create(...)` (or the series branch inside the constructor) replace the five
inline constructions (`RWRoute`, `PartialRouter`, `CUFR`, `PartialCUFR`,
`TimingAndWirelengthReport`). No behaviour change on UltraScale+.

## 3. Steps, in order

1. **Node delay table and estimator** (seam B). `fit_node_delays.py`, the table,
   `VersalDelayEstimator`, the `DelayEstimatorBase` constructor, `isLong`/`getExtraDelay`,
   `computeNodeDelay`. Unit test: the estimator's delay of a few known nodes equals the
   table; hold-out error printed by the script goes into NOTES.md. About 1.5 days.
2. **Timing graph adapter** (seam A). `VersalRWTimingGraph`, the unrouted mode and sink
   site pin in `VersalTimingGraph`, the `TimingManager` branch, the factories. At the end of
   this step `RWRoute --timingDriven` runs on `picoblaze_2022.2.dcp` (Versal, no global
   clock; the existing non-timing test's DCP) and prints a critical path. Unit test:
   `TestRWRoute.testTimingDrivenRoutingOnVersalDevice` (full routing, all pins routed, a
   finite critical path delay, criticalities in (0, 0.99]). About 3 days.
3. **Exact per-net refresh**. After each iteration (`updateTiming`, and
   `updateTimingAfterFixingRoutes`), rebuild each routed net's tree from its connections'
   node lists (`Connection.getNodes()`, sink-first) and run the full model on it: refactor
   `calcNodeArrivalsAllCorners` to take roots + a children map instead of `net.getPIPs()`,
   then `connection.setTimingEdgesDelay(arrival(sink node))`. Nets whose connections were
   not rerouted keep their delays. This is what the per-node sum cannot do on Versal:
   fan-out and sibling load terms, grandparent slew, crossing corrections. The 8x8's net
   edges take ~4 s for all nets, so per iteration this is cheap. Keep it behind a config flag
   (`--versalExactNetDelay`, default on for Versal) so parity behaviour can be compared.
   About 1 day.
4. **Constants** (seam C): heuristic ps/tile, `SOURCE_HOP`, `LONGLONG`. Half a day.
5. **Validation on RapidSA**. Unrouted checkpoints exist for 4x4 (27 MB), 8x8 (70 MB) and
   32x8 in ~/work/RapidSA (read-only; copy or symlink under `data/`). For 4x4 and 8x8:
   - route non-timing-driven and timing-driven (`memguard.sh`, `-Xmx36g` on an idle
     machine; measure peak RSS and wall per stage, the timing graph build in particular);
   - `VersalTimingReport` on both results (model WNS, clock model included);
   - Vivado `report_timing_summary` on both results (8-10 GB for the 8x8; not concurrently
     with the JVM) and on Vivado's own routing of the same placement
     (`systolic_array_8x8_routed.dcp`, WNS -181 ps by the model / -179 by Vivado).
   Success: Vivado-measured WNS of the timing-driven route better than the non-timing-driven
   one, and the router's own critical path estimate within the report model's error of
   Vivado's number. Record the table in NOTES.md. About 2 days including the runs.
6. **Optional, beyond parity: clock skew.** After `routeGlobalClkNet`,
   `VersalClockModel.analyze(clk)` gives every launch and capture arrival (validated within
   6 ps on RWRoute-routed trees, NOTES.md "Sweep results"). Put the launch arrival on the
   superSource edge and subtract the capture arrival on the superSink edge (negative edge
   weights are fine for the slack arithmetic; `computeArrivalTimesTopologicalOrder` only
   takes a max). CPR would be ignored (pessimistic on same-leaf paths); flag
   `--versalClockSkew`, default off. About 2 days.

Total to parity with validation (steps 1-5): about 8 working days.

## 4. Files touched

New:
- `src/com/xilinx/rapidwright/timing/versal/VersalDelayEstimator.java`
- `src/com/xilinx/rapidwright/timing/versal/VersalRWTimingGraph.java`
- `timing/versal/node_delay_terms.txt`
- project: `fit_node_delays.py`, a `nodes` stage in `run_versal_flow.sh`, NOTES.md section

Modified:
- `timing/delayestimator/DelayEstimatorBase.java`: protected table-less constructor;
  `isLong`, `getExtraDelay` series-aware
- `timing/TimingGraph.java`: `superEdgeDelay` hook; `sinkSitePinInstTimingEdges` registration
  reachable from the subclass
- `timing/TimingManager.java`: Versal branch (null `TimingModel`), factory
- `timing/versal/VersalTimingGraph.java`: sink `SitePinInst` on net edges, unrouted mode
- `timing/versal/VersalTimingModel.java`: `calcNodeArrivals` on an explicit tree
- `rwroute/RouterHelper.java`: `createDelayEstimator`, `computeNodeDelay` gate
- `rwroute/RouteNodeGraphTimingDriven.java`: heuristic constants from the estimator
- `rwroute/RWRoute.java`: factories, heuristic/estimate constants, step 3 refresh
- `rwroute/PartialRouter.java`, `CUFR.java`, `PartialCUFR.java`,
  `TimingAndWirelengthReport.java`: use the factories
- `rwroute/RWRouteConfig.java`: `--versalExactNetDelay`, `--versalClockSkew`
- `test/.../rwroute/TestRWRoute.java`: Versal timing-driven test;
  `test/.../timing/versal/TestVersalTimingModel.java`: estimator test

## 5. Risks and open points

- **Memory of the JGraphT graph on the 32x8.** If it does not fit next to the routing graph,
  the fallback is to keep `VersalTimingGraph` as the STA and give `Connection` an edge
  interface (`getSrc/getDst/getDelay/setRouteDelay`) implemented by both edge classes; more
  code churn in `rwroute`, so only if step 5 shows the need.
- **Marginal per-node accuracy.** High-fanout nets are where the per-node sum is worst
  (LOAD/FANOUT terms); step 3 corrects the criticality but not the search cost. If step 5
  shows the router chasing wrong nodes, a per-(parent intent, node intent) pair table with
  `getExtraDelay(child, parent)` (the call sites already have the parent) is the next
  refinement.
- **Direct connections and route-throughs.** `Connection.isDirect()` connections carry no
  timing edges on UltraScale+ either. Versal LUT route-throughs are rejected by RWRoute
  (`--lutRoutethru` unsupported), so the model's route-through handling is not exercised.
- **Nets with several source site pins** (`swapOutputPin`): the Versal model already treats
  every PIP start node without an incoming PIP as a root; the step 3 tree builder must do
  the same.
- **Period constraint.** RapidSA checkpoints carry `create_clock`; the picoblaze test DCP
  may not, in which case `timingRequirement` is 0 and the required time is normalised to the
  max arrival (existing UltraScale+ behaviour).
- **Speed grade.** Tables are -2MHP slow_max only, as the report model.
