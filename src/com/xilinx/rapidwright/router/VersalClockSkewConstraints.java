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

package com.xilinx.rapidwright.router;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.edif.EDIFCellInst;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFDirection;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.router.VersalClockSkewSolver.SkewConstraint;

/**
 * Derives the launch/capture site pairs a clock skew solve needs, by walking
 * the netlist for register-to-register connections and pricing each pair with
 * {@link VersalClockTimingModel}.
 *
 * <p>This is what lets skew be optimized without Vivado in the loop: the pairs
 * come from connectivity, which RapidWright already has, and the skew from
 * characterized clock data, so no timing analysis is run.
 *
 * <p>Skew depends only on the two <em>sites</em> involved, not on which
 * registers inside them are connected, so pairs are collapsed to unique site
 * pairs. That is an exact deduplication rather than a sampling, and it keeps
 * the constraint count proportional to placement rather than to netlist size.
 */
public class VersalClockSkewConstraints {

    /**
     * How many levels of combinational logic to follow between registers.
     * Paths deeper than this are dropped rather than followed indefinitely;
     * four covers the LUT depth of typical pipelined logic.
     */
    public static final int DEFAULT_MAX_LOGIC_DEPTH = 4;

    /** Summary of what the walk found, for reporting. */
    public static class Result {
        public final List<SkewConstraint> constraints;
        public final int registerCount;
        public final int pairsFound;
        public final int pairsUncovered;

        Result(List<SkewConstraint> constraints, int registerCount, int pairsFound,
               int pairsUncovered) {
            this.constraints = constraints;
            this.registerCount = registerCount;
            this.pairsFound = pairsFound;
            this.pairsUncovered = pairsUncovered;
        }
    }

    public static Result build(Design design, VersalClockTimingModel model) {
        return build(design, model, DEFAULT_MAX_LOGIC_DEPTH, true);
    }

    /**
     * Walks the netlist and returns one constraint per connected site pair.
     *
     * @param design         The placed design.
     * @param model          Supplies the skew for a site pair.
     * @param maxLogicDepth  Levels of combinational logic to traverse between
     *                       registers.
     * @param setup          Which pessimism table to price the skew with.
     */
    public static Result build(Design design, VersalClockTimingModel model, int maxLogicDepth,
                               boolean setup) {
        // Placed cells, by the hierarchical instance name they implement.
        // Names are hierarchical rather than flat, so connectivity has to be
        // followed through the hierarchy: a walk that only looks at nets local
        // to one level finds just the connections inside a module and misses
        // every inter-module path, which are exactly the long, high-skew ones.
        Map<String, Cell> placed = new HashMap<>();
        for (Cell c : design.getCells()) {
            if (c.getSite() != null && c.getEDIFHierCellInst() != null) {
                placed.put(c.getEDIFHierCellInst().getFullHierarchicalInstName(), c);
            }
        }

        Set<String> seenPairs = new HashSet<>();
        List<SkewConstraint> constraints = new ArrayList<>();
        int registers = 0;
        int pairsFound = 0;
        int uncovered = 0;

        for (Map.Entry<String, Cell> e : placed.entrySet()) {
            EDIFHierCellInst launchInst = e.getValue().getEDIFHierCellInst();
            if (!isSequential(launchInst.getInst())) {
                continue;
            }
            registers++;
            Site launchSite = e.getValue().getSite();

            for (String captureName : reachableRegisters(launchInst, maxLogicDepth)) {
                Cell captureCell = placed.get(captureName);
                if (captureCell == null || captureCell.getSite() == null) {
                    continue;
                }
                Site captureSite = captureCell.getSite();
                if (captureSite.equals(launchSite)) {
                    // Same site: the clock arrives at both ends together, so
                    // there is no skew to correct.
                    continue;
                }
                pairsFound++;
                String key = launchSite.getName() + ">" + captureSite.getName();
                if (!seenPairs.add(key)) {
                    continue;
                }
                Integer skewPs = model.getSkewPs(launchSite, captureSite, setup);
                if (skewPs == null) {
                    uncovered++;
                    continue;
                }
                constraints.add(new SkewConstraint(launchSite, captureSite, skewPs / 1000.0));
            }
        }
        return new Result(constraints, registers, pairsFound, uncovered);
    }

    /**
     * Follows a register's outputs forward through combinational logic and
     * returns the hierarchical names of the registers reached.
     *
     * <p>Fanout is resolved with {@link EDIFHierNet#getLeafHierPortInsts},
     * which follows a net through module ports to the leaf pins it actually
     * reaches. Reading a leaf cell's own nets instead would confine the walk to
     * one level of hierarchy.
     */
    private static Set<String> reachableRegisters(EDIFHierCellInst launch, int maxLogicDepth) {
        Set<String> found = new HashSet<>();
        Set<String> visited = new HashSet<>();
        // Each entry pairs an instance with the logic depth it was reached at.
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] { launch, 0 });
        visited.add(launch.getFullHierarchicalInstName());

        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            EDIFHierCellInst inst = (EDIFHierCellInst) entry[0];
            int depth = (Integer) entry[1];

            for (EDIFHierPortInst pi : inst.getHierPortInsts()) {
                if (pi.getPortInst().getDirection() != EDIFDirection.OUTPUT) {
                    continue;
                }
                EDIFHierNet net = pi.getHierarchicalNet();
                if (net == null) {
                    continue;
                }
                for (EDIFHierPortInst sink : net.getLeafHierPortInsts(false, true)) {
                    EDIFHierCellInst sinkInst = sink.getHierarchicalInst();
                    if (sinkInst == null) {
                        continue;
                    }
                    EDIFCellInst leaf = sink.getPortInst().getCellInst();
                    if (leaf == null) {
                        continue;
                    }
                    String name = sinkInst.getFullHierarchicalInstName().isEmpty()
                            ? leaf.getName()
                            : sinkInst.getFullHierarchicalInstName() + "/" + leaf.getName();
                    if (name.equals(inst.getFullHierarchicalInstName())) {
                        continue;
                    }
                    if (isSequential(leaf)) {
                        // A clock pin is not a timing path endpoint in the
                        // sense meant here; only data inputs are.
                        if (!isClockPin(sink.getPortInst())) {
                            found.add(name);
                        }
                        continue;
                    }
                    if (depth + 1 > maxLogicDepth || !visited.add(name)) {
                        continue;
                    }
                    EDIFHierCellInst next = sinkInst.getChild(leaf);
                    if (next != null) {
                        queue.add(new Object[] { next, depth + 1 });
                    }
                }
            }
        }
        return found;
    }

    /**
     * Recognizes the cell types that end a timing path. Flip-flops are the ones
     * whose clock can actually be retimed by a leaf delay; block RAM, DSP and
     * shift registers are included because they still bound paths, and the
     * solver pins them at zero taps since they have no leaf delay to program.
     */
    public static boolean isSequential(EDIFCellInst inst) {
        if (inst.getCellType() == null) {
            return false;
        }
        String type = inst.getCellType().getName();
        return type.startsWith("FD") || type.startsWith("RAMB") || type.startsWith("DSP")
                || type.startsWith("SRL") || type.startsWith("URAM");
    }

    private static boolean isClockPin(EDIFPortInst pi) {
        String name = pi.getName();
        return name.equals("C") || name.equals("CLK") || name.startsWith("CLK");
    }
}
