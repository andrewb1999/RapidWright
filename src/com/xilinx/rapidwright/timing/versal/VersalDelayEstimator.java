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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.delayestimator.DelayEstimatorBase;
import com.xilinx.rapidwright.timing.delayestimator.InterconnectInfo;
import com.xilinx.rapidwright.util.FileTools;

/**
 * Per-node delay estimator for timing-driven RWRoute on Versal.
 *
 * <p>The Versal interconnect model ({@link VersalTimingModel}) is contextual: a hop depends on the
 * parent, its other children and the node's own children, which the router does not know while it
 * expands a node. The router instead charges every node the marginal fitted by
 * {@code fit_node_delays.py} into {@code timing/versal/node_delay_terms.txt}: the mean increment into
 * the node's children over the training route trees, keyed by the node's class
 * ({@link VersalTimingModel#parentClass(Node)}), with a fallback per intent code. The route delay of a
 * connection is the sum over its nodes from the source pin node to the node driving the sink pin
 * node (the sink pin node is 0), plus {@link #getExtraDelay(Node, boolean)} for a long node entered
 * from a long node. The full model is applied to the finished route trees between iterations.
 */
public class VersalDelayEstimator extends DelayEstimatorBase<InterconnectInfo> {

    public static final String DEFAULT_FILE = "timing" + File.separator + "versal" + File.separator + "node_delay_terms.txt";

    private static final class Table {
        final Map<String, Float> byClass = new HashMap<>();
        final Map<IntentCode, Float> byIntent = new EnumMap<>(IntentCode.class);
        float longToLong = 0, psPerTileX = 0, psPerTileY = 0, sourceHop = 0;
    }

    private static final Map<String, Table> TABLES = new HashMap<>();

    private final Table table;

    public VersalDelayEstimator(Device device, boolean useUTurnNodes) {
        this(device, useUTurnNodes, FileTools.getRapidWrightPath() + File.separator + DEFAULT_FILE);
    }

    public VersalDelayEstimator(Device device, boolean useUTurnNodes, String fileName) {
        super(device, new InterconnectInfo(), useUTurnNodes, 0, false);
        synchronized (TABLES) {
            table = TABLES.computeIfAbsent(fileName, VersalDelayEstimator::load);
        }
        versalLongToLongExtra = (short) Math.round(table.longToLong);
    }

    private static Table load(String fileName) {
        Table t = new Table();
        try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] f = line.split("\\s+");
                switch (f[0]) {
                    case "NODE":
                        t.byClass.put(f[1], Float.parseFloat(f[2]));
                        break;
                    case "INTENT": {
                        IntentCode ic;
                        try { ic = IntentCode.valueOf(f[1]); } catch (IllegalArgumentException e) { break; }
                        t.byIntent.put(ic, Float.parseFloat(f[2]));
                        break;
                    }
                    case "LONGLONG":
                        t.longToLong = Float.parseFloat(f[1]);
                        break;
                    case "HEURISTIC":
                        t.psPerTileX = Float.parseFloat(f[1]);
                        t.psPerTileY = Float.parseFloat(f[2]);
                        break;
                    case "SOURCE_HOP":
                        t.sourceHop = Float.parseFloat(f[1]);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("ERROR: cannot read the Versal node delay table " + fileName, e);
        }
        return t;
    }

    @Override
    public boolean chargesEveryNode() {
        return true;
    }

    /** Delay (ps) charged to a node: its class's marginal, else its intent's, else 0. */
    @Override
    public short getDelayOf(Node node) {
        Float v = table.byClass.get(VersalTimingModel.parentClass(node));
        if (v == null) v = table.byIntent.get(node.getIntentCode());
        return v == null ? 0 : (short) Math.round(v);
    }

    /** Delay per tile of horizontal distance (ps) for the router's remaining-delay estimate. */
    public float getPsPerTileX() {
        return table.psPerTileX;
    }

    /** Delay per tile of vertical distance (ps) for the router's remaining-delay estimate. */
    public float getPsPerTileY() {
        return table.psPerTileY;
    }

    /** Constant of the pre-route delay estimate of a connection (added to the source node and its first hop). */
    public float getSourceHopPs() {
        return table.sourceHop;
    }

    /** Extra delay of a long node entered from a long node. */
    public float getLongToLongExtra() {
        return table.longToLong;
    }
}
