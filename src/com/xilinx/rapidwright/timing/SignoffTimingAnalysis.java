/*
 * Copyright (c) 2026, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.timing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;

/**
 * Two-corner setup and hold analysis of register-to-register paths, priced
 * from a {@link ClockDelayModel}, an {@link InterconnectDelayModel} and a
 * {@link LogicDelayModel} rather than from Vivado.
 *
 * <p>The slack is assembled the way Vivado's report presents it. For setup:
 * <pre>
 *   arrival  = launch clock (max) + clock-to-Q + Σ net (max) + Σ logic (max)
 *   required = period + capture clock (min) + pessimism removal − uncertainty − setup
 *   slack    = required − arrival
 * </pre>
 * and for hold the corners swap, the (negative) hold pessimism removal and
 * hold uncertainty apply, and the hold requirement replaces setup. Each component is kept on the result so a
 * disagreement with Vivado can be attributed rather than just observed.
 *
 * <p>Connections routed inside a site are priced by the logic model's
 * intra-site table; the checkpoint's site pins must be present for the
 * distinction to be made, so callers should run
 * {@link DesignTools#createMissingSitePinInsts(Design)} on a checkpoint that
 * was not routed in-process.
 *
 * <p>Paths are enumerated from the netlist: from every register output,
 * forward through combinational cells, to every register input reached. The
 * worst path per endpoint pin is kept, which is what {@code report_timing
 * -nworst 1} reports and what a deskew solver needs. Paths that reach a cell
 * or net the models cannot price are dropped and counted, not guessed at.
 */
public class SignoffTimingAnalysis {

    /** Levels of combinational logic to follow between registers. */
    public static final int DEFAULT_MAX_LOGIC_DEPTH = 12;

    /** One priced path, with the components its slack was assembled from. */
    public static class PathResult {
        public final Cell launch;
        public final Cell capture;
        /** Vivado-style pin names, {@code inst/C} and {@code inst/D}. */
        public final String startpoint;
        public final String endpoint;
        public final boolean setup;
        public final float requirementPs;
        public final float launchClockPs;
        public final float captureClockPs;
        public final float pessimismRemovalPs;
        public final float uncertaintyPs;
        public final float clockToQPs;
        public final float netPs;
        public final float logicPs;
        /** The setup or hold requirement at the capturing register. */
        public final float checkPs;
        public final int logicLevels;
        public final float slackPs;
        /** The cell pins along the data path, for tracing a disagreement. */
        public final List<String> pins;
        /** The net delay of each hop along the data path, in path order. */
        public final List<Float> hopNetPs;

        PathResult(Cell launch, Cell capture, String startpoint, String endpoint, boolean setup,
                   float requirementPs, float launchClockPs, float captureClockPs,
                   float pessimismRemovalPs, float uncertaintyPs, float clockToQPs, float netPs,
                   float logicPs, float checkPs, int logicLevels, List<String> pins,
                   List<Float> hopNetPs) {
            this.launch = launch;
            this.capture = capture;
            this.startpoint = startpoint;
            this.endpoint = endpoint;
            this.setup = setup;
            this.requirementPs = requirementPs;
            this.launchClockPs = launchClockPs;
            this.captureClockPs = captureClockPs;
            this.pessimismRemovalPs = pessimismRemovalPs;
            this.uncertaintyPs = uncertaintyPs;
            this.clockToQPs = clockToQPs;
            this.netPs = netPs;
            this.logicPs = logicPs;
            this.checkPs = checkPs;
            this.logicLevels = logicLevels;
            this.pins = pins;
            this.hopNetPs = hopNetPs;
            if (setup) {
                float arrival = launchClockPs + clockToQPs + netPs + logicPs;
                float required = requirementPs + captureClockPs + pessimismRemovalPs
                        - uncertaintyPs - checkPs;
                this.slackPs = required - arrival;
            } else {
                // Hold pessimism removal is signed the way Vivado reports it,
                // negative, so it is added here as it is for setup.
                float arrival = launchClockPs + clockToQPs + netPs + logicPs;
                float required = captureClockPs + pessimismRemovalPs + uncertaintyPs + checkPs;
                this.slackPs = arrival - required;
            }
        }

        /** The data path delay, which is what Vivado calls it. */
        public float getDatapathPs() {
            return clockToQPs + netPs + logicPs;
        }

        public float getSkewPs() {
            return captureClockPs - launchClockPs + pessimismRemovalPs;
        }

        @Override
        public String toString() {
            return (setup ? "setup " : "hold  ") + startpoint + " -> " + endpoint + " slack "
                    + Math.round(slackPs) + " ps (datapath " + Math.round(getDatapathPs())
                    + ", skew " + Math.round(getSkewPs()) + ")";
        }
    }

    /** What the analysis could and could not price, for reporting. */
    public static class Coverage {
        public int launchRegisters;
        public int pathsPriced;
        public int endpointsPriced;
        public int unpricedClock;
        public int unpricedNet;
        public int unpricedLogic;
        public int unpricedCheck;
        public int depthExceeded;
        /** Hops that never leave the site. */
        public int intraSiteHops;
        /** Of those, the ones with no characterized delay, priced at zero. */
        public int intraSiteUnknown;
        /** The uncharacterized intra-site connections, with counts. */
        public final Map<String, Integer> intraSiteUnknownPairs = new TreeMap<>();
        /** Site types of clock sinks the clock model has no arrival for, with counts. */
        public final Map<String, Integer> uncoveredClockSiteTypes = new TreeMap<>();
        /** Why net delays could not be priced, with counts. */
        public final Map<String, Integer> unpricedNetReasons = new TreeMap<>();

        @Override
        public String toString() {
            return "launch registers " + launchRegisters + ", paths " + pathsPriced
                    + ", endpoints " + endpointsPriced + "; dropped: clock " + unpricedClock
                    + " " + uncoveredClockSiteTypes + ", net " + unpricedNet + " "
                    + unpricedNetReasons + ", logic " + unpricedLogic + ", check "
                    + unpricedCheck + ", depth " + depthExceeded + "; intra-site hops "
                    + intraSiteHops + " (" + intraSiteUnknown + " unknown: "
                    + intraSiteUnknownPairs + ")";
        }
    }

    private final Design design;
    private final Net clock;
    private final float periodPs;
    private final float uncertaintyPs;
    private final float holdUncertaintyPs;
    private final ClockDelayModel clockModel;
    private final InterconnectDelayModel netModel;
    private final LogicDelayModel logicModel;
    private final int maxLogicDepth;

    private final Coverage coverage = new Coverage();
    /** Placed cells by hierarchical instance name, which is how netlist pins name them. */
    private final Map<String, Cell> placed = new HashMap<>();

    /**
     * @param design        The routed design.
     * @param clock         The clock net whose registers are analysed.
     * @param periodPs      The clock period, i.e. the setup requirement.
     * @param uncertaintyPs Clock uncertainty Vivado applies to setup checks on
     *                      this clock. Hold checks get none, which is Vivado's
     *                      default for a clock without user uncertainty.
     */
    public SignoffTimingAnalysis(Design design, Net clock, float periodPs, float uncertaintyPs,
                                 ClockDelayModel clockModel, InterconnectDelayModel netModel,
                                 LogicDelayModel logicModel) {
        this(design, clock, periodPs, uncertaintyPs, 0, clockModel, netModel, logicModel,
                DEFAULT_MAX_LOGIC_DEPTH);
    }

    public SignoffTimingAnalysis(Design design, Net clock, float periodPs, float uncertaintyPs,
                                 float holdUncertaintyPs, ClockDelayModel clockModel,
                                 InterconnectDelayModel netModel, LogicDelayModel logicModel,
                                 int maxLogicDepth) {
        this.design = design;
        this.clock = clock;
        this.periodPs = periodPs;
        this.uncertaintyPs = uncertaintyPs;
        this.holdUncertaintyPs = holdUncertaintyPs;
        this.clockModel = clockModel;
        this.netModel = netModel;
        this.logicModel = logicModel;
        this.maxLogicDepth = maxLogicDepth;
        for (Cell c : design.getCells()) {
            if (c.getSite() != null && c.getEDIFHierCellInst() != null) {
                placed.put(c.getEDIFHierCellInst().getFullHierarchicalInstName(), c);
            }
        }
    }

    public Coverage getCoverage() {
        return coverage;
    }

    /**
     * Prices every register-to-register path on the clock and returns the
     * worst per endpoint pin, most negative slack first.
     */
    public List<PathResult> analyze(boolean setup) {
        Map<String, PathResult> worstPerEndpoint = new HashMap<>();
        Set<String> clockSites = clockSiteNames();

        for (Cell launch : placed.values()) {
            if (!isRegister(launch) || !clockSites.contains(launch.getSite().getName())) {
                continue;
            }
            coverage.launchRegisters++;
            Corner launchCorner = setup ? Corner.SLOW_MAX : Corner.SLOW_MIN;
            Float launchClock = clockModel.getArrivalPs(launch.getSite(), launchCorner);
            if (launchClock == null) {
                coverage.unpricedClock++;
                coverage.uncoveredClockSiteTypes.merge(launch.getSiteInst().getSiteTypeEnum().name(), 1, Integer::sum);
                continue;
            }
            String clkBel = clockBelPin(launch);
            EDIFHierCellInst inst = launch.getEDIFHierCellInst();
            for (EDIFHierPortInst out : inst.getHierPortInsts()) {
                if (out.getPortInst().getDirection() != EDIFDirection.OUTPUT) {
                    continue;
                }
                String qBel = launch.getPhysicalPinMapping(out.getPortInst().getName());
                if (qBel == null || clkBel == null) {
                    continue;
                }
                Float clockToQ = logicModel.getPropagationDelayPs(launch, clkBel, qBel, launchCorner);
                if (clockToQ == null) {
                    coverage.unpricedLogic++;
                    continue;
                }
                List<String> pins = new ArrayList<>();
                pins.add(pinName(out));
                follow(launch, launchClock, clockToQ, out, 0, 0, 0, pins, new ArrayList<>(),
                        new HashSet<>(), setup, worstPerEndpoint);
            }
        }
        coverage.endpointsPriced = worstPerEndpoint.size();
        List<PathResult> results = new ArrayList<>(worstPerEndpoint.values());
        results.sort(Comparator.comparingDouble(r -> r.slackPs));
        return results;
    }

    /**
     * Follows one output pin forward through its net, pricing each sink:
     * registers end a path, combinational cells continue it.
     */
    private void follow(Cell launch, float launchClock, float clockToQ, EDIFHierPortInst out,
                        float netSoFar, float logicSoFar, int depth, List<String> pins,
                        List<Float> hops, Set<String> onPath, boolean setup,
                        Map<String, PathResult> worst) {
        Corner dataCorner = setup ? Corner.SLOW_MAX : Corner.SLOW_MIN;
        EDIFHierNet hierNet = out.getHierarchicalNet();
        if (hierNet == null) {
            return;
        }
        Net net = design.getNetlist().getPhysicalNetFromPin(out, design);
        if (net == null) {
            coverage.unpricedNet++;
            coverage.unpricedNetReasons.merge("no physical net", 1, Integer::sum);
            return;
        }
        Cell driver = placed.get(out.getFullHierarchicalInstName());
        String driverBel = driver == null ? null : driver.getPhysicalPinMapping(out.getPortInst().getName());
        String fromBelPin = driver == null || driverBel == null ? null
                : driver.getBELName() + "/" + driverBel;
        for (EDIFHierPortInst sink : hierNet.getLeafHierPortInsts(false, true)) {
            Cell cell = placed.get(sink.getFullHierarchicalInstName());
            if (cell == null) {
                continue;
            }
            String logicalPin = sink.getPortInst().getName();
            String belPin = cell.getPhysicalPinMapping(logicalPin);
            if (belPin == null) {
                continue;
            }
            Float netDelay = netDelay(net, cell, belPin, fromBelPin, dataCorner);
            if (netDelay == null) {
                coverage.unpricedNet++;
                continue;
            }
            float netHere = netSoFar + netDelay;
            hops.add(netDelay);

            if (isRegister(cell)) {
                if (!isClockPin(logicalPin)) {
                    finishPath(launch, launchClock, clockToQ, cell, logicalPin, belPin, netHere,
                            logicSoFar, depth, pins, hops, sink, setup, worst);
                }
                hops.remove(hops.size() - 1);
                continue;
            }
            if (depth + 1 > maxLogicDepth) {
                coverage.depthExceeded++;
                hops.remove(hops.size() - 1);
                continue;
            }
            String instName = sink.getFullHierarchicalInstName();
            if (!onPath.add(instName)) {
                // A combinational loop; do not follow it round again.
                hops.remove(hops.size() - 1);
                continue;
            }
            EDIFHierCellInst inst = cell.getEDIFHierCellInst();
            for (EDIFHierPortInst next : inst.getHierPortInsts()) {
                if (next.getPortInst().getDirection() != EDIFDirection.OUTPUT) {
                    continue;
                }
                String outBel = cell.getPhysicalPinMapping(next.getPortInst().getName());
                if (outBel == null) {
                    continue;
                }
                Float prop = logicModel.getPropagationDelayPs(cell, belPin, outBel, dataCorner);
                if (prop == null) {
                    // No arc between these pins; this output is not on the path.
                    continue;
                }
                pins.add(pinName(sink));
                pins.add(pinName(next));
                follow(launch, launchClock, clockToQ, next, netHere, logicSoFar + prop, depth + 1,
                        pins, hops, onPath, setup, worst);
                pins.remove(pins.size() - 1);
                pins.remove(pins.size() - 1);
            }
            onPath.remove(instName);
            hops.remove(hops.size() - 1);
        }
    }

    private void finishPath(Cell launch, float launchClock, float clockToQ, Cell capture,
                            String dataLogicalPin, String dataBel, float netPs, float logicPs,
                            int depth, List<String> pins, List<Float> hops, EDIFHierPortInst endPin,
                            boolean setup, Map<String, PathResult> worst) {
        Corner captureCorner = setup ? Corner.SLOW_MIN : Corner.SLOW_MAX;
        Float captureClock = clockModel.getArrivalPs(capture.getSite(), captureCorner);
        if (captureClock == null) {
            coverage.unpricedClock++;
            coverage.uncoveredClockSiteTypes.merge(capture.getSiteInst().getSiteTypeEnum().name(), 1, Integer::sum);
            return;
        }
        String clkBel = clockBelPin(capture);
        if (clkBel == null) {
            coverage.unpricedCheck++;
            return;
        }
        // The requirement is a library value at the slow corner's worst case.
        Float check = setup ? logicModel.getSetupPs(capture, clkBel, dataBel, Corner.SLOW_MAX)
                : logicModel.getHoldPs(capture, clkBel, dataBel, Corner.SLOW_MAX);
        if (check == null) {
            coverage.unpricedCheck++;
            return;
        }
        float cpr = clockModel.getPessimismRemovalPs(launch.getSite(), capture.getSite(), setup);

        List<String> pathPins = new ArrayList<>(pins);
        pathPins.add(pinName(endPin));
        String startpoint = launch.getEDIFHierCellInst().getFullHierarchicalInstName() + "/"
                + clockLogicalPin(launch);
        String endpoint = capture.getEDIFHierCellInst().getFullHierarchicalInstName() + "/"
                + dataLogicalPin;
        PathResult r = new PathResult(launch, capture, startpoint, endpoint, setup, periodPs,
                launchClock, captureClock, cpr, setup ? uncertaintyPs : holdUncertaintyPs,
                clockToQ, netPs, logicPs, check, depth, pathPins, new ArrayList<>(hops));
        coverage.pathsPriced++;
        PathResult prev = worst.get(endpoint);
        if (prev == null || r.slackPs < prev.slackPs) {
            worst.put(endpoint, r);
        }
    }

    /**
     * The net delay to a BEL pin of a cell. A connection routed entirely
     * inside the site — one that reaches the BEL pin without passing a site
     * pin — has no interconnect and is priced at zero here; being in the same
     * site as the source is not enough, since a LUT feeding a LUT beside it
     * still leaves through the interconnect and comes back.
     */
    private Float netDelay(Net net, Cell cell, String belPin, String fromBelPin, Corner corner) {
        SiteInst si = cell.getSiteInst();
        String sitePin = DesignTools.getRoutedSitePinFromPhysicalPin(cell, net, belPin);
        if (sitePin == null) {
            coverage.intraSiteHops++;
            Float d = fromBelPin == null ? null : logicModel.getIntraSiteDelayPs(
                    si.getSiteTypeEnum(), fromBelPin, cell.getBELName() + "/" + belPin, corner);
            if (d == null) {
                // Not characterized: priced at zero and counted, rather than
                // dropping every path through a common connection.
                coverage.intraSiteUnknown++;
                coverage.intraSiteUnknownPairs.merge(si.getSiteTypeEnum().name() + " " + fromBelPin
                        + " " + cell.getBELName() + "/" + belPin, 1, Integer::sum);
                return 0f;
            }
            return d;
        }
        SitePinInst spi = si.getSitePinInst(sitePin);
        if (spi == null) {
            // The site pin name from the site routing does not always match
            // the pin instance's name (DSP bus pins differ); fall back to the
            // net's own pin on this site when there is exactly one.
            SitePinInst only = null;
            for (SitePinInst p : net.getPins()) {
                if (!p.isOutPin() && p.getSiteInst() == si) {
                    if (only != null) {
                        only = null;
                        break;
                    }
                    only = p;
                }
            }
            spi = only;
        }
        if (spi == null) {
            coverage.unpricedNetReasons.merge("site pin " + sitePin + " not on net", 1, Integer::sum);
            return null;
        }
        Float d = netModel.getNetDelayPs(net, spi, corner);
        if (d == null) {
            coverage.unpricedNetReasons.merge("model", 1, Integer::sum);
        }
        return d;
    }

    private Set<String> clockSiteNames() {
        Set<String> sites = new HashSet<>();
        for (SitePinInst spi : clock.getPins()) {
            if (!spi.isOutPin()) {
                sites.add(spi.getSite().getName());
            }
        }
        return sites;
    }

    private static String pinName(EDIFHierPortInst pi) {
        return pi.getFullHierarchicalInstName() + "/" + pi.getPortInst().getName();
    }

    /** The BEL pin a register's clock arrives on. */
    private static String clockBelPin(Cell cell) {
        String logical = clockLogicalPin(cell);
        return logical == null ? null : cell.getPhysicalPinMapping(logical);
    }

    private static final String[] CLOCK_PIN_NAMES = { "C", "CLK", "WCLK", "CLKARDCLK", "CLKBWRCLK",
            "CLKA", "CLKB" };

    private static String clockLogicalPin(Cell cell) {
        for (String candidate : CLOCK_PIN_NAMES) {
            if (cell.getPhysicalPinMapping(candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isClockPin(String logicalPin) {
        for (String name : CLOCK_PIN_NAMES) {
            if (logicalPin.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a cell starts and ends timing paths: anything with a clock pin.
     * Flip-flops are the common case; memories, shift registers, DSPs and
     * NoC endpoints all qualify, and all bound the paths into them.
     */
    public static boolean isRegister(Cell cell) {
        EDIFCellInst inst = cell.getEDIFCellInst();
        if (inst == null || inst.getCellType() == null) {
            return false;
        }
        return clockLogicalPin(cell) != null;
    }

    /** A worst-slack summary line in the spirit of Vivado's. */
    public static String summarize(List<PathResult> results) {
        if (results.isEmpty()) {
            return "no paths";
        }
        float wns = results.get(0).slackPs;
        float tns = 0;
        int failing = 0;
        for (PathResult r : results) {
            if (r.slackPs < 0) {
                tns += r.slackPs;
                failing++;
            }
        }
        return String.format("WNS %.3f ns, TNS %.3f ns, %d failing of %d endpoints",
                wns / 1000, tns / 1000, failing, results.size());
    }

    /** Convenience for callers that only want the worst slack. */
    public static float worstSlackPs(List<PathResult> results) {
        return results.isEmpty() ? Float.POSITIVE_INFINITY : Collections.min(results,
                Comparator.comparingDouble(r -> r.slackPs)).slackPs;
    }
}
