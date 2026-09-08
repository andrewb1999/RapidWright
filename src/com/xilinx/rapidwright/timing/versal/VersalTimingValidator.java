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

package com.xilinx.rapidwright.timing.versal;

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.BELPin;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the Versal timing model against Vivado data dumped by dump_node_delays.tcl.
 *
 * <pre>
 * Usage: VersalTimingValidator design.dcp design.edf [--nets netdelays.csv] [--paths paths.csv] [--top N]
 * </pre>
 */
public class VersalTimingValidator {

    static List<String[]> readCsv(String path) throws IOException {
        List<String[]> rows = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                rows.add(splitCsv(line));
            }
        }
        return rows;
    }

    static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { sb.append('"'); i++; }
                    else q = false;
                } else sb.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(sb.toString()); sb.setLength(0); }
            else sb.append(c);
        }
        out.add(sb.toString());
        return out.toArray(new String[0]);
    }

    static class Stats {
        final List<Float> err = new ArrayList<>(), pct = new ArrayList<>();
        final List<float[]> pairs = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        String worstLabel = ""; float worst = 0;

        void add(float model, float vivado, String label) {
            float e = model - vivado;
            err.add(e); pairs.add(new float[] {vivado, model}); labels.add(label);
            if (vivado > 0) pct.add(100f * e / vivado);
            if (Math.abs(e) > Math.abs(worst)) { worst = e; worstLabel = label; }
        }

        /** The n samples with the largest |error|, worst first. */
        String worstList(int n) {
            Integer[] idx = new Integer[err.size()];
            for (int i = 0; i < idx.length; i++) idx[i] = i;
            java.util.Arrays.sort(idx, (a, b) -> Float.compare(Math.abs(err.get(b)), Math.abs(err.get(a))));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(n, idx.length); i++)
                sb.append(String.format("    %+7.1f ps  model %8.1f  vivado %8.1f  %s%n", err.get(idx[i]), pairs.get(idx[i])[1], pairs.get(idx[i])[0], labels.get(idx[i])));
            return sb.toString();
        }

        String summary() {
            if (err.isEmpty()) return "no samples";
            double m = 0, m2 = 0, mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
            for (float e : err) { m += e; m2 += e * e; mn = Math.min(mn, e); mx = Math.max(mx, e); }
            m /= err.size();
            double sd = Math.sqrt(m2 / err.size() - m * m);
            double pm = 0, pm2 = 0;
            for (float p : pct) { pm += p; pm2 += p * p; }
            pm /= Math.max(1, pct.size());
            double psd = Math.sqrt(Math.max(0, pm2 / Math.max(1, pct.size()) - pm * pm));
            return String.format("n=%d  error(model-vivado) avg %+.1f sd %.1f min %.0f max %.0f ps | pct avg %+.1f sd %.1f%% | spearman %.4f | worst %s",
                    err.size(), m, sd, mn, mx, pm, psd, spearman(), worstLabel + String.format(" (%+.0f ps)", worst));
        }

        double spearman() {
            int n = pairs.size();
            if (n < 3) return Double.NaN;
            double[] ra = rank(0), rb = rank(1);
            double ma = 0, mb = 0;
            for (int i = 0; i < n; i++) { ma += ra[i]; mb += rb[i]; }
            ma /= n; mb /= n;
            double num = 0, da = 0, db = 0;
            for (int i = 0; i < n; i++) { num += (ra[i] - ma) * (rb[i] - mb); da += (ra[i] - ma) * (ra[i] - ma); db += (rb[i] - mb) * (rb[i] - mb); }
            return num / Math.sqrt(da * db);
        }

        double[] rank(int idx) {
            Integer[] order = new Integer[pairs.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Float.compare(pairs.get(a)[idx], pairs.get(b)[idx]));
            double[] r = new double[order.length];
            for (int i = 0; i < order.length; i++) r[order[i]] = i;
            return r;
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage: VersalTimingValidator design.dcp design.edf [--corner slow_max|slow_min|fast_max|fast_min] [--nets netdelays.csv] [--paths paths.csv] [--endpoint cell/pin] [--top N] [--period ns]");
            return;
        }
        String netsCsv = null, pathsCsv = null, clockSinksCsv = null, clockPathsCsv = null, clockStepsCsv = null;
        int clockWorst = 0;           // --clock-worst N: list the N sinks with the largest clock arrival error
        boolean strictTiers = false;  // --strict-tiers: exit 2 when a clock step came from a class/intent fallback
        String leafDelayCsv = null;   // --leaf-delays <csv>: programmed RCLK leaf delays, fallback only (the model reads them from the leaf PIP delay index; default: <dcp>.leafdelay.csv next to the checkpoint)
        List<String> showEndpoints = new ArrayList<>();
        VersalCorner corner = VersalCorner.SLOW_MAX;
        float periodPs = 0;
        int top = 3;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--nets": netsCsv = args[++i]; break;
                case "--paths": pathsCsv = args[++i]; break;
                case "--top": top = Integer.parseInt(args[++i]); break;
                case "--endpoint": showEndpoints.add(args[++i]); break;
                case "--corner": corner = VersalCorner.fromString(args[++i]); break;
                case "--period": periodPs = Float.parseFloat(args[++i]) * 1000f; break;
                case "--clock-sinks": clockSinksCsv = args[++i]; break;
                case "--clock-worst": clockWorst = Integer.parseInt(args[++i]); break;
                case "--strict-tiers": strictTiers = true; break;
                case "--leaf-delays": leafDelayCsv = args[++i]; break;
                case "--clock-paths": clockPathsCsv = args[++i]; break;
                case "--clock-steps": clockStepsCsv = args[++i]; break;
                default: throw new IllegalArgumentException("unknown option " + args[i]);
            }
        }
        long t0 = System.currentTimeMillis();
        Design design = new java.io.File(args[1]).exists() ? Design.readCheckpoint(args[0], args[1]) : Design.readCheckpoint(args[0]);   // "-" or a missing EDIF: the DCP carries its own netlist
        // checkpoints assembled by RapidWright (e.g. RapidSA) can lack site pin instances on hard blocks
        // (DSP58 CLK); the clock model needs them to find the flop clock arrivals
        int before = 0;
        for (com.xilinx.rapidwright.design.SiteInst si : design.getSiteInsts()) before += si.getSitePinInstMap().size();
        if (System.getenv("NO_CREATE_SPI") == null) DesignTools.createMissingSitePinInsts(design);
        int after = 0;
        for (com.xilinx.rapidwright.design.SiteInst si : design.getSiteInsts()) after += si.getSitePinInstMap().size();
        if (after != before) System.out.println("created " + (after - before) + " missing site pin instances");
        System.out.println("loaded " + design.getName() + " on " + design.getPartName() + " in " + (System.currentTimeMillis() - t0) + " ms");
        t0 = System.currentTimeMillis();
        VersalTimingModel model = new VersalTimingModel(design.getDevice());   // all four corners
        int ci = model.indexOf(corner);
        System.out.println("model: " + model.getCornerCount() + " corners, comparing " + corner + ", " + model.getTerms(ci).getEdgeEntryCount()
                + " EDGE entries, loaded in " + (System.currentTimeMillis() - t0) + " ms");

        if (netsCsv != null) validateNets(design, model, ci, netsCsv);

        t0 = System.currentTimeMillis();
        VersalTimingGraph graph = new VersalTimingGraph(design, model);
        graph.build();
        graph.computeArrivals();
        List<VersalTimingGraph.Vertex> ends = graph.getEndpointsSorted(ci);
        System.out.printf("graph: %d vertices, %d edges, %d endpoints, %d pruned LUT inputs, %d constant LUTs, built in %d ms; unknown BELs %d %s, unrouted sinks %d, skipped nets %d, edge misses %d, intra-site misses %d/%d%n",
                graph.getVertexCount(), graph.getEdgeCount(), ends.size(), graph.getPrunedLutArcCount(), graph.getConstantLutCount(), System.currentTimeMillis() - t0,
                graph.getUnknownBelCount(), graph.getUnknownBelNames(), graph.getUnroutedSinkCount(), graph.getSkippedNetCount(),
                model.getEdgeMissCount(), model.getIntraSiteMissCount(), model.getIntraSiteLookupCount());
        for (int i = 0; i < Math.min(top, ends.size()); i++) {
            System.out.println("critical path #" + (i + 1) + " to " + ends.get(i) + ":");
            System.out.print(graph.formatPath(ends.get(i), ci));
        }
        if (periodPs > 0) System.out.print(graph.report(periodPs));
        for (String ep : showEndpoints) {
            int slash = ep.lastIndexOf('/');
            Cell cell = design.getCell(ep.substring(0, slash));
            String phys = cell == null ? null : cell.getPhysicalPinMapping(ep.substring(slash + 1));
            VersalTimingGraph.Vertex v = phys == null ? null : graph.getVertex(cell, phys);
            System.out.println("model path to " + ep + ":");
            if (v == null) System.out.println("  (not in graph)"); else System.out.print(graph.formatPath(v, ci));
        }
        if (pathsCsv != null) validatePaths(design, graph, ci, ends, pathsCsv, top);
        if (clockStepsCsv != null) validateClockNodes(design, clockStepsCsv);
        if (clockSinksCsv != null || clockPathsCsv != null) validateClock(design, model, clockSinksCsv, clockPathsCsv, periodPs, clockWorst, strictTiers, args[0], leafDelayCsv);
    }

    /** Compares the Java tree walk's node arrivals with the arrivals Vivado printed in the clock trace. */
    static void validateClockNodes(Design design, String stepsCsv) throws IOException {
        VersalClockModel cm = new VersalClockModel();
        List<String[]> rows = readCsv(stepsCsv);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < rows.get(0).length; i++) col.put(rows.get(0)[i], i);
        Map<Net, VersalClockModel.ClockTree> trees = new HashMap<>();
        Stats st = new Stats();
        int missing = 0;
        for (String[] r : rows.subList(1, rows.size())) {
            if (!r[col.get("outgoing_pip")].isEmpty()) continue;   // leaf contexts only (sink pin nodes)
            Net net = design.getNet(r[col.get("net")]);
            if (net == null) { missing++; continue; }
            VersalClockModel.ClockTree t = trees.computeIfAbsent(net, cm::analyze);
            com.xilinx.rapidwright.device.Node n = design.getDevice().getNode(r[col.get("node")]);
            float[] s = n == null ? null : t.leafState.get(n);
            if (s == null) { missing++; if (missing <= 3) System.out.println("  node not a leaf in the Java tree: " + r[col.get("node")]); continue; }
            float[] a = VersalClockModel.ClockTree.arrival(s);
            st.add(a[0], Float.parseFloat(r[col.get("arr_slow_max")]), r[col.get("node")]);
        }
        System.out.println("[clock nodes] Java leaf arrival vs trace (slow max): " + st.summary() + "; " + missing + " not matched; tiers " + cm.getTierUse());
    }


    // ---- pin resolution: Vivado names pins of macro cells (DSP58, LUTRAM macros) on the macro,
    // while the placed leaf primitive (e.g. DSP_C_DATA58_INST) owns the physical pin mapping
    private static Map<String, List<Cell>> macroChildren;

    /** The leaf cell and physical pin of a Vivado "cell/pin" name, or null. */
    static Object[] resolvePin(Design design, String pinName) {
        int slash = pinName.lastIndexOf('/');
        if (slash < 0) return null;
        String cellName = pinName.substring(0, slash), pin = pinName.substring(slash + 1);
        Cell cell = design.getCell(cellName);
        if (cell != null) {
            String phys = cell.getPhysicalPinMapping(pin);
            if (phys != null) return new Object[] {cell, phys};
        }
        if (macroChildren == null) {
            macroChildren = new HashMap<>();
            for (Cell c : design.getCells()) {
                int i = c.getName().lastIndexOf('/');
                if (i > 0) macroChildren.computeIfAbsent(c.getName().substring(0, i), k -> new ArrayList<>()).add(c);
            }
        }
        for (Cell c : macroChildren.getOrDefault(cellName, java.util.Collections.emptyList())) {
            String phys = c.getPhysicalPinMapping(pin);
            if (phys != null) return new Object[] {c, phys};
        }
        return null;
    }

    static void validateClock(Design design, VersalTimingModel model, String sinksCsv, String pathsCsv, float periodPs, int clockWorst, boolean strictTiers, String dcpPath, String leafDelayCsv) throws IOException {
        long t0 = System.currentTimeMillis();
        VersalClockModel cm = new VersalClockModel();
        String leafCsv = leafDelayCsv;
        if (leafCsv == null && dcpPath != null) { String c = dcpPath.replaceAll("\\.dcp$", "") + ".leafdelay.csv"; if (new java.io.File(c).exists()) leafCsv = c; }
        if (leafCsv != null) { cm.loadLeafDelays(leafCsv); System.out.println("clock model: " + cm.getLeafDelayCount() + " programmed leaf delays from " + leafCsv); }
        System.out.println("clock model: " + cm.getContextCount() + " exact contexts, loaded in " + (System.currentTimeMillis() - t0) + " ms");
        String[] corners = VersalClockModel.CORNERS;
        if (sinksCsv != null) {
            List<String[]> rows = readCsv(sinksCsv);
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < rows.get(0).length; i++) col.put(rows.get(0)[i], i);
            Stats[] st = new Stats[4];
            for (int i = 0; i < 4; i++) st[i] = new Stats();
            Stats cle = new Stats(), hard = new Stats();   // slow max, split: slice sinks vs hard-block sinks (BRAM/DSP/URAM/NoC/IO)
            Map<Net, VersalClockModel.ClockTree> trees = new HashMap<>();
            int skipped = 0;
            // SINKS_CSV=<file>: per-sink model vs Vivado arrivals (all four corners) and the sink's site
            java.io.PrintWriter sinkDump = System.getenv("SINKS_CSV") == null ? null : new java.io.PrintWriter(System.getenv("SINKS_CSV"));
            if (sinkDump != null) sinkDump.println("sink_pin,site,leaf_delay,model_slow_max,viv_slow_max,model_slow_min,viv_slow_min,model_fast_max,viv_fast_max,model_fast_min,viv_fast_min,node,tree_slow_max,tree_slow_min,tree_fast_max,tree_fast_min");
            for (String[] r : rows.subList(1, rows.size())) {
                Net net = design.getNet(r[col.get("net")]);
                String sinkPin = r[col.get("sink_pin")];
                int slash = sinkPin.lastIndexOf('/');
                Cell cell = design.getCell(sinkPin.substring(0, slash));
                if (net == null || cell == null) { skipped++; continue; }
                SitePinInst spi = cell.getSitePinFromLogicalPin(sinkPin.substring(slash + 1), null);
                if (spi == null) { skipped++; continue; }
                VersalClockModel.ClockTree t = trees.computeIfAbsent(net, cm::analyze);
                float[] a = cm.pinArrival(t, spi, cell);
                if (a == null) { skipped++; continue; }
                if (sinkDump != null) {
                    StringBuilder sb = new StringBuilder(sinkPin).append(',').append(spi.getSite().getName()).append(',').append(cm.leafLevel(spi.getConnectedNode()));
                    for (int i = 0; i < 4; i++) sb.append(',').append(a[i]).append(',').append(r[col.get(corners[i])]);
                    float[] tree = t.sinkArrival.get(spi);
                    sb.append(',').append(spi.getConnectedNode());
                    for (int i = 0; i < 4; i++) sb.append(',').append(tree == null ? "" : String.valueOf(tree[i]));
                    sinkDump.println(sb);
                }
                for (int i = 0; i < 4; i++) {
                    String v = r[col.get(corners[i])];
                    if (!v.isEmpty()) st[i].add(a[i], Float.parseFloat(v), sinkPin);
                }
                String v0 = r[col.get(corners[0])];
                if (!v0.isEmpty()) (spi.getSiteTypeEnum().name().startsWith("SLICE") ? cle : hard).add(a[0], Float.parseFloat(v0), sinkPin);
            }
            if (sinkDump != null) sinkDump.close();
            for (int i = 0; i < 4; i++) System.out.println("[clock sinks " + corners[i] + "] " + st[i].summary());
            System.out.println("[clock sinks slow_max, slices only]      " + cle.summary());
            System.out.println("[clock sinks slow_max, hard-block sinks] " + hard.summary());
            System.out.println("[clock sinks] skipped " + skipped + "; tiers " + cm.getTierUse() + "; intra-site misses " + cm.getSiteMissCount());
            if (clockWorst > 0) System.out.print("[clock sinks slow_max] worst " + clockWorst + ":\n" + st[0].worstList(clockWorst));
            if (strictTiers) {
                int fallback = 0;
                for (Map.Entry<String, Integer> e : cm.getTierUse().entrySet())
                    if (!(e.getKey().equals("ctx") || e.getKey().equals("tctx") || e.getKey().startsWith("fam"))) fallback += e.getValue();
                if (fallback > 0) { System.out.println("[clock sinks] STRICT: " + fallback + " clock steps came from class/intent/none fallbacks"); System.exit(2); }
            }
        }
        if (pathsCsv != null) {
            List<String[]> rows = readCsv(pathsCsv);
            Map<String, Integer> col = new HashMap<>();
            for (int i = 0; i < rows.get(0).length; i++) col.put(rows.get(0)[i], i);
            float setupUnc = 0, holdUnc = 0;
            float vivWns = Float.MAX_VALUE, vivWhs = Float.MAX_VALUE;
            for (String[] r : rows.subList(1, rows.size())) {
                String u = r[col.get("uncertainty_ps")];
                if (u.isEmpty()) continue;
                if (r[col.get("analysis")].equals("setup")) setupUnc = Float.parseFloat(u); else holdUnc = Float.parseFloat(u);
            }
            if (periodPs <= 0) {
                // period from a setup row: requirement = period for same-edge paths
                for (String[] r : rows.subList(1, rows.size()))
                    if (r[col.get("analysis")].equals("setup") && !r[col.get("requirement_ps")].isEmpty()) { periodPs = Float.parseFloat(r[col.get("requirement_ps")]); break; }
            }
            t0 = System.currentTimeMillis();
            VersalSlackAnalysis sa = new VersalSlackAnalysis(design, model, cm, periodPs, setupUnc, holdUnc);
            sa.run();
            System.out.println("slack analysis in " + (System.currentTimeMillis() - t0) + " ms");
            System.out.print(sa.report());
            List<String> cprCsv = new ArrayList<>();
            List<String> slackCsv = new ArrayList<>();
            Map<String, VersalSlackAnalysis.Result> byEnd = new HashMap<>();
            for (VersalSlackAnalysis.Result res : sa.getResults()) byEnd.put(res.endpoint.getName() + (res.fast ? "@fast" : "@slow"), res);
            for (String analysis : new String[] {"setup", "hold"}) {
                for (String corner : new String[] {"slow", "fast"}) {
                    Stats slack = new Stats(), launch = new Stats(), capture = new Stats(), cpr = new Stats(), data = new Stats(), check = new Stats(), unc = new Stats();
                    Stats[] cprV = {new Stats(), new Stats(), new Stats(), new Stats(), new Stats(), new Stats()};
                    Stats[] cprVS = {new Stats(), new Stats(), new Stats(), new Stats(), new Stats(), new Stats()}; // CPR evaluated for Vivado's own startpoint
                    int shownDbg = 0, shownCpr = 0;
                    java.util.Map<String, java.util.TreeMap<String, Integer>> cprGroups = new java.util.TreeMap<>();
                    int missing = 0;
                    float vivWorst = Float.MAX_VALUE, modelWorstOfViv = Float.MAX_VALUE;
                    for (String[] r : rows.subList(1, rows.size())) {
                        if (!r[col.get("analysis")].equals(analysis) || !r[col.get("corner")].equals(corner)) continue;
                        String ep = r[col.get("endpoint")];
                        Object[] rp = resolvePin(design, ep);
                        Cell cell = rp == null ? null : (Cell) rp[0];
                        String phys = rp == null ? null : (String) rp[1];
                        VersalSlackAnalysis.Result res = phys == null ? null : byEnd.get(cell.getName() + "/" + phys + "@" + corner);
                        if (res == null || r[col.get("slack_ps")].isEmpty()) {
                            missing++;
                            if (missing <= 3) {
                                VersalTimingGraph.Vertex v = cell == null || phys == null ? null : sa.getGraph().getVertex(cell, phys);
                                System.out.println("  " + analysis + " endpoint not matched: " + ep + " -> cell " + (cell == null ? null : cell.getName() + " (" + cell.getType() + " @ " + cell.getBELName() + ")") + " phys " + phys
                                        + (v == null ? " no vertex" : " vertex endpoint=" + v.endpoint + " arrival " + java.util.Arrays.toString(v.arrival) + " fanin " + sa.getGraph().describeFanin(v)));
                            }
                            continue;
                        }
                        boolean setup = analysis.equals("setup");
                        float vs = Float.parseFloat(r[col.get("slack_ps")]);
                        float ms = setup ? res.setupSlack : res.holdSlack;
                        slack.add(ms, vs, ep);
                        vivWorst = Math.min(vivWorst, vs);
                        modelWorstOfViv = Math.min(modelWorstOfViv, ms);
                        launch.add(setup ? res.launchClockMax : res.launchClockMin, Float.parseFloat(r[col.get("launch_clock_ps")]), ep);
                        capture.add(setup ? res.captureClockMin : res.captureClockMax, Float.parseFloat(r[col.get("capture_clock_ps")]), ep);
                        {
                            // Vivado's implied library check: setup: period + capture + cpr - unc - check - (launch + data) = slack
                            //                                 hold: launch + data - (capture - |cpr| + unc + check) = slack
                            String cc = r[col.get("cpr_ps")], uu = r[col.get("uncertainty_ps")], dd = r[col.get("datapath_ps")], ll = r[col.get("launch_clock_ps")], kk = r[col.get("capture_clock_ps")], rq = r[col.get("requirement_ps")];
                            if (!cc.isEmpty() && !dd.isEmpty() && !ll.isEmpty() && !kk.isEmpty()) {
                                float u = uu.isEmpty() ? 0 : Float.parseFloat(uu), cp = Math.abs(Float.parseFloat(cc)), d = Float.parseFloat(dd), l = Float.parseFloat(ll), k = Float.parseFloat(kk);
                                float req = rq.isEmpty() ? 0 : Float.parseFloat(rq);
                                float impliedCheck = setup ? req + k + cp - u - l - d - vs : l + d - k + cp - u - vs;
                                check.add(setup ? res.setupCheck : res.holdCheck, impliedCheck, ep);
                                unc.add(setup ? sa.getSetupUncertainty() : sa.getHoldUncertainty(), u, ep);
                            }
                        }
                        if (System.getenv("SLACK_CSV") != null) {
                            String[] parts = {r[col.get("logic_ps")], r[col.get("net_ps")]};
                            float lm = setup ? res.launchClockMax : res.launchClockMin, dmodel = setup ? res.dataMax - res.launchClockMax : res.dataMin - res.launchClockMin;
                            slackCsv.add(String.join(",", analysis, corner, ep, r[col.get("startpoint")], String.valueOf(res.launch), String.valueOf(vs), String.valueOf(ms),
                                    r[col.get("datapath_ps")], String.valueOf(dmodel), r[col.get("launch_clock_ps")], String.valueOf(lm), r[col.get("capture_clock_ps")], String.valueOf(setup ? res.captureClockMin : res.captureClockMax),
                                    r[col.get("cpr_ps")], String.valueOf(setup ? res.setupPessimism : res.holdPessimism), parts[0], parts[1], String.valueOf(setup ? res.setupCheck : res.holdCheck)));
                        }
                        String c = r[col.get("cpr_ps")];
                        if (!c.isEmpty()) {
                            cpr.add(setup ? res.setupPessimism : res.holdPessimism, Math.abs(Float.parseFloat(c)), ep);
                            for (int vv = 0; vv < 6; vv++) cprV[vv].add(setup ? res.setupPessimismVariants[vv] : res.holdPessimismVariants[vv], Math.abs(Float.parseFloat(c)), ep);
                            String vs0 = r[col.get("startpoint")];
                            int sl = vs0.lastIndexOf('/');
                            Object[] rs = resolvePin(design, vs0);
                            Cell sc = rs == null ? null : (Cell) rs[0];
                            SitePinInst lp = sc == null ? null : sc.getSitePinFromLogicalPin(vs0.substring(sl + 1), null);
                            SitePinInst cp = sa.clockSitePin(res.endpoint);
                            if (lp != null && cp != null && lp.getNet() == cp.getNet() && VersalClockModel.isGlobalClockNet(cp.getNet())) {
                                for (int vv = 0; vv < 6; vv++) cprVS[vv].add(cm.pessimism(sa.getClockTree(cp.getNet()), lp, cp, vv, analysis.equals("hold"))[corner.equals("fast") ? 1 : 0], Math.abs(Float.parseFloat(c)), ep);
                                float v2 = cm.pessimism(sa.getClockTree(cp.getNet()), lp, cp, 2)[corner.equals("fast") ? 1 : 0];
                                float v5 = cm.pessimism(sa.getClockTree(cp.getNet()), lp, cp, 5, analysis.equals("hold"))[corner.equals("fast") ? 1 : 0];
                                if (System.getenv("CPR_DEBUG5") != null && Math.abs(v5 - Math.abs(Float.parseFloat(c))) > Float.parseFloat(System.getenv("CPR_DEBUG5"))) {
                                    VersalClockModel.ClockTree t = sa.getClockTree(cp.getNet());
                                    java.util.List<com.xilinx.rapidwright.device.Node> pa = t.pathToRoot(lp.getConnectedNode());
                                    java.util.Set<com.xilinx.rapidwright.device.Node> onB = new java.util.HashSet<>(t.pathToRoot(cp.getConnectedNode()));
                                    com.xilinx.rapidwright.device.Node div = null;
                                    for (com.xilinx.rapidwright.device.Node x : pa) if (onB.contains(x)) { div = x; break; }
                                    int k = corner.equals("fast") ? 2 : 0;
                                    if (analysis.equals("hold")) k++;
                                    float vt = cm.vtSkew(t, lp.getConnectedNode(), cp.getConnectedNode(), div, k);
                                    System.out.printf("  CPR5 %s %s %s <- %s: vivado %s v5 %.1f v2 %.1f vt %.1f div %s%n", analysis, corner, ep, vs0, c, v5, v2, vt, div);
                                }
                                if (System.getenv("CPR_CSV") != null) {
                                    VersalClockModel.ClockTree t = sa.getClockTree(cp.getNet());
                                    java.util.List<com.xilinx.rapidwright.device.Node> pa = t.pathToRoot(lp.getConnectedNode());
                                    java.util.Set<com.xilinx.rapidwright.device.Node> onB = new java.util.HashSet<>(t.pathToRoot(cp.getConnectedNode()));
                                    com.xilinx.rapidwright.device.Node div = null;
                                    for (com.xilinx.rapidwright.device.Node x : pa) if (onB.contains(x)) { div = x; break; }
                                    float[] sl0 = t.leafState.get(lp.getConnectedNode()), sc0 = t.leafState.get(cp.getConnectedNode()), sd = div == null ? null : t.arriving.get(div);
                                    float[] sdl = div == null ? null : t.arrivalAt(div, null), sdc = sdl;
                                    if (sl0 != null && sc0 != null && sd != null) {
                                        StringBuilder sb = new StringBuilder();
                                        sb.append(analysis).append(',').append(corner).append(',').append(ep).append(',').append(vs0).append(',').append(c).append(',').append(r[col.get("launch_clock_ps")]).append(',').append(r[col.get("capture_clock_ps")]).append(',').append(div).append(',').append(pa.size()).append(',').append(t.pathToRoot(cp.getConnectedNode()).size());
                                        com.xilinx.rapidwright.device.Node dp = t.parent.get(div);
                                        sb.append(',').append(lp.getConnectedNode()).append(',').append(t.parentPip.get(lp.getConnectedNode()))
                                          .append(',').append(cp.getConnectedNode()).append(',').append(t.parentPip.get(cp.getConnectedNode()))
                                          .append(',').append(dp).append(',').append(dp == null ? null : t.parentPip.get(dp)).append(',').append(t.parentPip.get(div));
                                        for (float[] st : new float[][] {sl0, sc0, sd}) for (float v : st) sb.append(',').append(v);
                                        float[] la = cm.pinArrival(t, lp, sc), ca = cm.pinArrival(t, cp, res.endpoint.cell);
                                        for (float v : la) sb.append(',').append(v);
                                        for (float v : ca) sb.append(',').append(v);
                                        cprCsv.add(sb.toString());
                                    }
                                }
                                if (System.getenv("CPR_GROUP") != null && analysis.equals("setup") && corner.equals("slow")) {
                                    VersalClockModel.ClockTree t = sa.getClockTree(cp.getNet());
                                    java.util.List<com.xilinx.rapidwright.device.Node> pa = t.pathToRoot(lp.getConnectedNode());
                                    java.util.Set<com.xilinx.rapidwright.device.Node> onB = new java.util.HashSet<>(t.pathToRoot(cp.getConnectedNode()));
                                    com.xilinx.rapidwright.device.Node div = null, tl = null;
                                    for (com.xilinx.rapidwright.device.Node x : pa) { if (onB.contains(x)) { div = x; break; } tl = x; }
                                    com.xilinx.rapidwright.device.Node tc = null;
                                    for (com.xilinx.rapidwright.device.Node x : t.pathToRoot(cp.getConnectedNode())) { if (x.equals(div)) break; tc = x; }
                                    String key = div + " L>" + tl + " C>" + tc;
                                    cprGroups.computeIfAbsent(key, k -> new java.util.TreeMap<>()).merge(Math.round(Math.abs(Float.parseFloat(c))) + "|" + Math.round(v2), 1, Integer::sum);
                                }
                                if (System.getenv("CPR_DEBUG") != null && Math.abs(v2 - Math.abs(Float.parseFloat(c))) > Float.parseFloat(System.getenv("CPR_DEBUG")) && shownCpr++ < 3) {
                                    VersalClockModel.ClockTree t = sa.getClockTree(cp.getNet());
                                    System.out.printf("  CPR debug %s <- %s: vivado %s model(v2) %.1f%n", ep, vs0, c, v2);
                                    {
                                        java.util.List<com.xilinx.rapidwright.device.Node> pa0 = t.pathToRoot(lp.getConnectedNode());
                                        java.util.Set<com.xilinx.rapidwright.device.Node> onB0 = new java.util.HashSet<>(t.pathToRoot(cp.getConnectedNode()));
                                        com.xilinx.rapidwright.device.Node div = null;
                                        for (com.xilinx.rapidwright.device.Node x : pa0) if (onB0.contains(x)) { div = x; break; }
                                        float[] sl0 = t.leafState.get(lp.getConnectedNode()), sc0 = t.leafState.get(cp.getConnectedNode()), sd = t.arriving.get(div);
                                        System.out.println("    moments launch  " + java.util.Arrays.toString(sl0));
                                        System.out.println("    moments capture " + java.util.Arrays.toString(sc0));
                                        System.out.println("    moments common  " + java.util.Arrays.toString(sd));
                                        System.out.println("    vivado launch/capture " + r[col.get("launch_clock_ps")] + "/" + r[col.get("capture_clock_ps")] + " root " + java.util.Arrays.toString(t.arriving.get(t.roots.get(0))));
                                        int kk = corner.equals("fast") ? 2 : 0;
                                        for (com.xilinx.rapidwright.device.Node x = div; x != null; x = t.parent.get(x)) {
                                            float[] st = t.arriving.get(x);
                                            if (st == null) break;
                                            System.out.printf("    chain %-80s mean diff %7.1f sqrtVmax %5.1f sqrtVmin %5.1f total %7.1f%n", x, st[kk] - st[kk + 1], Math.sqrt(Math.max(0, st[4 + kk])), Math.sqrt(Math.max(0, st[5 + kk])), st[kk] - st[kk + 1] + Math.sqrt(Math.max(0, st[4 + kk])) + Math.sqrt(Math.max(0, st[5 + kk])));
                                        }
                                    }
                                    java.util.List<com.xilinx.rapidwright.device.Node> pa = t.pathToRoot(lp.getConnectedNode()), pb = t.pathToRoot(cp.getConnectedNode());
                                    java.util.Set<com.xilinx.rapidwright.device.Node> onB = new java.util.HashSet<>(pb);
                                    int k = corner.equals("fast") ? 2 : 0;
                                    int depth = 0;
                                    for (com.xilinx.rapidwright.device.Node x : pa) {
                                        boolean shared = onB.contains(x);
                                        float[] ar = t.arriving.get(x);
                                        float[] a = ar == null ? null : VersalClockModel.ClockTree.arrival(ar);
                                        System.out.printf("    %s%s arriving max-min %.1f (arr %.1f/%.1f) intent %s%n", shared ? "COMMON " : "launch ", x, a == null ? -1 : a[k] - a[k + 1], a == null ? -1 : a[k], a == null ? -1 : a[k + 1], x.getIntentCode());
                                        if (shared && ++depth > 4) break;
                                    }
                                    depth = 0;
                                    for (com.xilinx.rapidwright.device.Node x : pb) {
                                        if (onB.contains(x) && new java.util.HashSet<>(pa).contains(x)) break;
                                        float[] ar = t.arriving.get(x);
                                        float[] a = ar == null ? null : VersalClockModel.ClockTree.arrival(ar);
                                        System.out.printf("    capture %s arriving max-min %.1f (arr %.1f/%.1f) intent %s%n", x, a == null ? -1 : a[k] - a[k + 1], a == null ? -1 : a[k], a == null ? -1 : a[k + 1], x.getIntentCode());
                                    }
                                }
                            }
                        }
                        if (shownDbg < 2 && Math.abs(ms - vs) > 150) {
                            shownDbg++;
                            System.out.printf("  large slack error at %s: vivado slack %.0f req %s launch %s capture %s cpr %s unc %s data %s | model slack %.0f launch %.0f/%.0f capture %.0f/%.0f cpr %.0f/%.0f data %.0f/%.0f check %.0f/%.0f start %s vs vivado start %s%n",
                                    ep, vs, r[col.get("requirement_ps")], r[col.get("launch_clock_ps")], r[col.get("capture_clock_ps")], c, r[col.get("uncertainty_ps")], r[col.get("datapath_ps")],
                                    ms, res.launchClockMax, res.launchClockMin, res.captureClockMax, res.captureClockMin, res.setupPessimism, res.holdPessimism,
                                    res.dataMax - res.launchClockMax, res.dataMin - res.launchClockMin, res.setupCheck, res.holdCheck, res.launch, r[col.get("startpoint")]);
                        }
                        float dp = Float.parseFloat(r[col.get("datapath_ps")]);
                        data.add((setup ? res.dataMax - res.launchClockMax : res.dataMin - res.launchClockMin), dp, ep);
                    }
                    if (slack.err.isEmpty()) continue;
                    System.out.println("[" + analysis + " " + corner + "] slack:    " + slack.summary());
                    System.out.println("[" + analysis + " " + corner + "] launch:   " + launch.summary());
                    System.out.println("[" + analysis + " " + corner + "] capture:  " + capture.summary());
                    System.out.println("[" + analysis + " " + corner + "] cpr:      " + cpr.summary());
                    for (int vv = 0; vv < 6; vv++) System.out.println("[" + analysis + " " + corner + "]   cpr variant " + vv + ": " + cprV[vv].summary());
                    for (int vv = 0; vv < 6; vv++) System.out.println("[" + analysis + " " + corner + "]   cpr variant " + vv + " at Vivado startpoint: " + cprVS[vv].summary());
                    for (java.util.Map.Entry<String, java.util.TreeMap<String, Integer>> e : cprGroups.entrySet()) System.out.println("  CPR group " + e.getKey() + " (vivado|model:count) " + e.getValue());
                    System.out.println("[" + analysis + " " + corner + "] datapath: " + data.summary());
                    System.out.println("[" + analysis + " " + corner + "] check:    " + check.summary());
                    System.out.println("[" + analysis + " " + corner + "] uncert.:  " + unc.summary());
                    float modelWorst = Float.MAX_VALUE;
                    for (VersalSlackAnalysis.Result res : sa.getResults())
                        if (res.fast == corner.equals("fast")) modelWorst = Math.min(modelWorst, analysis.equals("setup") ? res.setupSlack : res.holdSlack);
                    System.out.printf("[%s %s] worst slack: vivado %.0f ps, model %.0f ps (model on Vivado's endpoints %.0f); %d endpoints not matched%n",
                            analysis, corner, vivWorst, modelWorst, modelWorstOfViv, missing);
                    if (analysis.equals("setup")) vivWns = Math.min(vivWns, vivWorst); else vivWhs = Math.min(vivWhs, vivWorst);
                }
            }
            if (System.getenv("SLACK_CSV") != null) {
                try (java.io.PrintWriter pw = new java.io.PrintWriter(System.getenv("SLACK_CSV"))) {
                    pw.println("analysis,corner,endpoint,viv_start,model_start,viv_slack,model_slack,viv_data,model_data,viv_launch,model_launch,viv_capture,model_capture,viv_cpr,model_cpr,viv_logic,viv_net,model_check");
                    for (String l : slackCsv) pw.println(l);
                }
            }
            if (System.getenv("CPR_CSV") != null) {
                try (java.io.PrintWriter pw = new java.io.PrintWriter(System.getenv("CPR_CSV"))) {
                    pw.println("analysis,corner,endpoint,startpoint,viv_cpr,viv_launch,viv_capture,div,launch_depth,capture_depth,l_node,l_pip,c_node,c_pip,dp_node,dp_pip,d_pip,l_m0,l_m1,l_m2,l_m3,l_v0,l_v1,l_v2,l_v3,c_m0,c_m1,c_m2,c_m3,c_v0,c_v1,c_v2,c_v3,d_m0,d_m1,d_m2,d_m3,d_v0,d_v1,d_v2,d_v3,l_arr0,l_arr1,l_arr2,l_arr3,c_arr0,c_arr1,c_arr2,c_arr3");
                    for (String l : cprCsv) pw.println(l);
                }
            }
            System.out.printf("[summary] WNS vivado %.0f ps model %.0f ps | WHS vivado %.0f ps model %.0f ps%n", vivWns, sa.getWNS(), vivWhs, sa.getWHS());
        }
    }

    static void validateNets(Design design, VersalTimingModel model, int ci, String csv) throws IOException {
        List<String[]> rows = readCsv(csv);
        String[] hdr = rows.get(0);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < hdr.length; i++) col.put(hdr[i], i);
        String cornerCol = model.getCorner(ci).getSuffix(), fullCol = "full_" + cornerCol;
        if (!col.containsKey(fullCol)) { System.out.println("[nets] no " + fullCol + " column in " + csv + " (re-dump needed for this corner)"); return; }
        Stats ic = new Stats(), full = new Stats(), intra = new Stats();
        List<String> netsDump = System.getenv("NETS_CSV") == null ? null : new ArrayList<>();
        Map<String, List<Float>> intraByClass = new HashMap<>();
        int missingNet = 0, missingPin = 0, unrouted = 0, shownKeys = 0;
        Map<Net, Map<SitePinInst, VersalTimingModel.SinkDelay>> cache = new HashMap<>();
        for (String[] r : rows.subList(1, rows.size())) {
            String netName = r[col.get("net")], sinkPin = r[col.get("sink_pin")];
            Net net = design.getNet(netName);
            if (net == null) { missingNet++; continue; }
            int slash = sinkPin.lastIndexOf('/');
            Cell cell = design.getCell(sinkPin.substring(0, slash));
            String logicalPin = sinkPin.substring(slash + 1);
            if (cell == null) { missingPin++; continue; }
            String fullStr = r[col.get(fullCol)];
            if (r[col.get("driver_site_pin")].equals("INTRASITE")) {
                // intra-site net: driver BEL pin -> sink BEL pin
                String drvBel = r[col.get("driver_bel_pin")].split(";")[0];
                String phys = cell.getPhysicalPinMapping(logicalPin);
                if (phys == null || drvBel.isEmpty() || fullStr.isEmpty()) { missingPin++; continue; }
                String[] db = drvBel.substring(drvBel.indexOf('/') + 1).split("/");
                Cell drv = null;
                for (Cell c : cell.getSiteInst().getCells()) if (c.getBELName().equals(db[0])) { drv = c; break; }
                if (drv == null) { missingPin++; continue; }
                BELPin from = drv.getBEL().getPin(db[1]), to = cell.getBEL().getPin(phys);
                if (from == null || to == null) { missingPin++; continue; }
                intra.add(model.intraSiteNetDelays(cell.getSiteInst(), from, to)[ci], Float.parseFloat(fullStr), netName + "->" + sinkPin);
                continue;
            }
            SitePinInst spi = cell.getSitePinFromLogicalPin(logicalPin, null);
            if (spi == null) {
                // no site pin: the sink is reached inside the driver's site (e.g. LUT cascade)
                String drvBel = r[col.get("driver_bel_pin")].split(";")[0];
                String phys = cell.getPhysicalPinMapping(logicalPin);
                String drvSite = drvBel.contains("/") ? drvBel.substring(0, drvBel.indexOf('/')) : "";
                if (phys != null && !fullStr.isEmpty() && drvSite.equals(cell.getSiteInst().getSiteName())) {
                    String[] db = drvBel.substring(drvBel.indexOf('/') + 1).split("/");
                    Cell drv = null;
                    for (Cell c : cell.getSiteInst().getCells()) if (c.getBELName().equals(db[0])) { drv = c; break; }
                    BELPin from = drv == null ? null : drv.getBEL().getPin(db[1]), to = cell.getBEL().getPin(phys);
                    if (from != null && to != null) {
                        intra.add(model.intraSiteNetDelays(cell.getSiteInst(), from, to)[ci], Float.parseFloat(fullStr), netName + "->" + sinkPin);
                        continue;
                    }
                }
                missingPin++;
                if (missingPin <= 3) System.out.println("  unresolved pin example: " + sinkPin + " cell type " + cell.getType() + " bel " + cell.getBELName()
                        + " phys " + phys + " net " + netName + " driver " + drvBel);
                continue;
            }
            Map<SitePinInst, VersalTimingModel.SinkDelay> delays = cache.computeIfAbsent(net, model::calcNetDelays);
            VersalTimingModel.SinkDelay sd = delays.get(spi);
            if (sd != null && sd.routed && netsDump != null) {
                com.xilinx.rapidwright.device.Node sn = spi.getConnectedNode();
                netsDump.add(netName + "," + sinkPin + "," + r[col.get(cornerCol)] + "," + sd.interconnect[ci] + "," + net.getSinkPins().size() + "," + net.getPIPs().size()
                        + "," + (sn == null ? "" : sn.getIntentCode()) + "," + (net.getSource() == null || net.getSource().getConnectedNode() == null ? "" : net.getSource().getConnectedNode().getTile().getName()) + "," + (sn == null ? "" : sn.getTile().getName()) + "," + (sn == null ? "" : sn.toString()));
            }
            if (sd == null || !sd.routed) {
                unrouted++;
                if (unrouted <= 3) {
                    System.out.println("  unrouted example: net " + netName + " sink " + spi + " src " + net.getSource()
                            + " rootNode " + (net.getSource() == null ? null : net.getSource().getConnectedNode())
                            + " sinkNode " + spi.getConnectedNode() + " pips " + net.getPIPs().size()
                            + " arrivals " + model.calcNodeArrivals(net).size() + " inMap " + (sd != null));
                    Map<com.xilinx.rapidwright.device.Node, Float> arr = model.calcNodeArrivals(net);
                    for (com.xilinx.rapidwright.device.PIP p : net.getPIPs()) {
                        if (!arr.containsKey(p.getStartNode()))
                            System.out.println("      orphan pip " + p + " start " + p.getStartNode() + " end " + p.getEndNode()
                                    + (p.isBidirectional() ? " bidir" : "") + (p.isReversed() ? " reversed" : "") + " type " + p.getPIPType());
                    }
                }
                continue;
            }
            String icStr = r[col.get(cornerCol)];
            if (!icStr.isEmpty()) ic.add(sd.interconnect[ci], Float.parseFloat(icStr), netName + "->" + sinkPin);
            if (!fullStr.isEmpty()) full.add(sd.getTotal(ci), Float.parseFloat(fullStr), netName + "->" + sinkPin);
            if (!icStr.isEmpty() && !fullStr.isEmpty()) {
                float err = (sd.driverIntraSite[ci] + sd.sinkIntraSite[ci]) - (Float.parseFloat(fullStr) - Float.parseFloat(icStr));
                String key = r[col.get("driver_bel_pin")].replaceAll("^[A-Z_0-9]+_X\\d+Y\\d+/", "").split(";")[0] + " " + net.getSource().getName()
                        + " -> " + spi.getName();
                intraByClass.computeIfAbsent(key, k -> new ArrayList<>()).add(err);
                if (shownKeys < 3) {
                    shownKeys++;
                    System.out.printf("  intra example: %s model drv %.0f sink %.0f vs vivado %.0f (file keys: driver %s, sink %s)%n", key, sd.driverIntraSite[ci], sd.sinkIntraSite[ci],
                            Float.parseFloat(fullStr) - Float.parseFloat(icStr), r[col.get("driver_bel_pin")], r[col.get("sink_bel_pins")]);
                }
            }
        }
        System.out.println("[nets] interconnect-only vs get_net_delays -interconnect_only: " + ic.summary());
        System.out.println("[nets] full (with intra-site) vs get_net_delays:              " + full.summary());
        System.out.println("[nets] intra-site nets vs get_net_delays:                     " + intra.summary());
        List<Map.Entry<String, List<Float>>> cls = new ArrayList<>(intraByClass.entrySet());
        cls.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));
        System.out.println("[nets] intra-site error by (driver bel pin, driver site pin -> sink site pin), largest classes:");
        for (int i = 0; i < Math.min(8, cls.size()); i++) {
            double m = 0; for (float e : cls.get(i).getValue()) m += e; m /= cls.get(i).getValue().size();
            System.out.printf("    n=%6d avg %+6.1f ps  %s%n", cls.get(i).getValue().size(), m, cls.get(i).getKey());
        }
        if (netsDump != null) {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(System.getenv("NETS_CSV"))) {
                pw.println("net,sink,vivado,model,fanout,pips,sink_intent,src_tile,sink_tile,sink_node");
                for (String l : netsDump) pw.println(l);
            } catch (java.io.IOException e) { throw new RuntimeException(e); }
        }
        System.out.printf("[nets] skipped: %d unknown nets, %d unresolved pins, %d unrouted sinks; edge misses %d, intra-site misses %d/%d%n",
                missingNet, missingPin, unrouted, model.getEdgeMissCount(), model.getIntraSiteMissCount(), model.getIntraSiteLookupCount());
        List<Map.Entry<String, Integer>> mk = new ArrayList<>(model.getIntraSiteMissKeys().entrySet());
        mk.sort((a, b) -> b.getValue() - a.getValue());
        for (int i = 0; i < Math.min(5, mk.size()); i++) System.out.println("    intra-site miss " + mk.get(i).getValue() + "x " + mk.get(i).getKey());
    }

    static void validatePaths(Design design, VersalTimingGraph graph, int ci, List<VersalTimingGraph.Vertex> ends, String csv, int top) throws IOException {
        List<String[]> rows = readCsv(csv);
        String[] hdr = rows.get(0);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < hdr.length; i++) col.put(hdr[i], i);
        Stats st = new Stats(), stLogic = new Stats(), stNet = new Stats(), stSame = new Stats(), stDiff = new Stats();
        List<String> pathsDump = System.getenv("PATHS_CSV") == null ? null : new ArrayList<>();
        int missing = 0, shown = 0;
        boolean maxCorner = graph.getModel().isMax(ci);
        float worstVivado = maxCorner ? -1 : Float.MAX_VALUE, worstModel = ends.isEmpty() ? -1 : ends.get(0).arrival[ci];
        VersalTimingGraph.Vertex worstErrV = null; float worstErr = 0;
        for (String[] r : rows.subList(1, rows.size())) {
            String ep = r[col.get("endpoint")];
            Object[] rp = resolvePin(design, ep);
            if (rp == null) { missing++; continue; }
            Cell cell = (Cell) rp[0];
            String phys = (String) rp[1];
            VersalTimingGraph.Vertex v = graph.getVertex(cell, phys);
            if (v == null || Float.isInfinite(v.arrival[ci])) {
                missing++;
                if (missing <= 4) {
                    System.out.println("  endpoint not found: " + ep + " type " + cell.getType() + " bel " + cell.getBELName() + " phys " + phys
                            + " vertex " + (v != null) + (v == null ? "" : " arrival " + v.arrival[ci] + " preds " + graph.describeFanin(v)));
                }
                continue;
            }
            float viv = Float.parseFloat(r[col.get("datapath_delay_ps")]);
            worstVivado = maxCorner ? Math.max(worstVivado, viv) : Math.min(worstVivado, viv);
            st.add(v.arrival[ci], viv, ep);
            float[] parts = graph.pathParts(v, ci);
            List<VersalTimingGraph.Edge> mp = graph.getPath(v, ci);
            String modelStart = (mp.isEmpty() ? v : mp.get(0).src).cell.getName();
            String vivStart = r[col.get("startpoint")];
            (vivStart.startsWith(modelStart + "/") ? stSame : stDiff).add(v.arrival[ci], viv, ep);
            stLogic.add(parts[0], Float.parseFloat(r[col.get("logic_delay_ps")]), ep);
            stNet.add(parts[1], Float.parseFloat(r[col.get("net_delay_ps")]), ep);
            if (pathsDump != null) {
                int nets = 0; for (VersalTimingGraph.Edge e : mp) if (e.net != null) nets++;
                pathsDump.add(ep + "," + vivStart + "," + modelStart + "," + viv + "," + v.arrival[ci] + "," + r[col.get("logic_delay_ps")] + "," + parts[0] + "," + r[col.get("net_delay_ps")] + "," + parts[1] + "," + r[col.get("logic_levels")] + "," + nets);
            }
            if (Math.abs(v.arrival[ci] - viv) > Math.abs(worstErr)) { worstErr = v.arrival[ci] - viv; worstErrV = v; }
            if (shown < top) {
                shown++;
                System.out.printf("vivado path to %s: data path %.0f ps (logic %s, net %s, levels %s); model %.0f ps%n",
                        ep, viv, r[col.get("logic_delay_ps")], r[col.get("net_delay_ps")], r[col.get("logic_levels")], v.arrival[ci]);
            }
        }
        System.out.println("[paths] per-endpoint data path delay vs Vivado worst path: " + st.summary());
        if (pathsDump != null) {
            try (java.io.PrintWriter pw = new java.io.PrintWriter(System.getenv("PATHS_CSV"))) {
                pw.println("endpoint,viv_start,model_start,viv_data,model_data,viv_logic,model_logic,viv_net,model_net,viv_levels,model_nets");
                for (String l : pathsDump) pw.println(l);
            }
        }
        System.out.println("[paths]   same startpoint as Vivado:                          " + stSame.summary());
        System.out.println("[paths]   different startpoint (path selection):              " + stDiff.summary());
        System.out.println("[paths]   logic part (incl. clk-to-Q):                       " + stLogic.summary());
        System.out.println("[paths]   net part (incl. intra-site):                       " + stNet.summary());
        if (worstErrV != null) {
            System.out.println("model path for worst-error endpoint " + worstErrV + ":");
            System.out.print(graph.formatPath(worstErrV, ci));
        }
        System.out.printf("[paths] worst endpoint (%s): vivado %.0f ps, model %.0f ps (%+.1f%%); %d endpoints not found%n", maxCorner ? "longest" : "shortest",
                worstVivado, worstModel, 100 * (worstModel - worstVivado) / worstVivado, missing);
    }
}
