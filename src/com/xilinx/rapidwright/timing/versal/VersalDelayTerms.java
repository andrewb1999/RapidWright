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

import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.TileTypeEnum;
import com.xilinx.rapidwright.util.FileTools;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Interconnect delay terms for the Versal lightweight timing model, read from
 * {@code timing/versal/intersite_delay_terms.txt}.
 *
 * <p>The model attributes the delay of a routed net to the nodes of its route tree:
 * <pre>
 *   arrival(node) = arrival(parent)
 *                 + EDGE(parent intent, node intent, child intents of node)
 *                 + sum over tiles crossed by parent of TILE(parent intent, tile type)
 *                 + FANOUT(node intent) * (children(node) - 1)
 * </pre>
 * The child-intent key is the sorted, '|'-joined set of the intent codes of the node's children
 * (without the NODE_ prefix), or LEAF for a sink pin node; the sibling key is the same set for the
 * parent's children. The parent class is its intent code, extended with ":family" (wire direction
 * and INT-tile half, e.g. EE_E) for horizontal wires, and the parent fanout bucket is its number
 * of children (1..4, 4 meaning 4 or more). Lookup falls back from the exact
 * (class, children, siblings, fanout) key to (class, children, siblings, *), (class, children, *, *),
 * the same three with the bare intent code, then to the maximum over the single child intents
 * present, then to EDGE_DEFAULT.
 */
public class VersalDelayTerms {

    public static final String DEFAULT_FILE = "timing" + File.separator + "versal" + File.separator + "intersite_delay_terms.txt";
    public static final String LEAF = "LEAF";
    /** FANOUT terms are linear only for small counts; extra children beyond this are not charged. */
    public static final int MAX_FANOUT_CHILDREN = 7;

    /** parent class (intent name, optionally ":family") -> node intent -> "children/siblings/fanout" -> ps */
    private final Map<String, Map<IntentCode, Map<String, Float>>> edge = new HashMap<>();
    /** EDGEX: correction per (parent class or intent, node intent, crossing signature of the parent wire) */
    private final Map<String, Map<IntentCode, Map<String, Float>>> edgeX = new HashMap<>();
    /** Parent fanout buckets are 1..MAX_PARENT_FANOUT_BUCKET (the last one means "or more"). */
    public static final int MAX_PARENT_FANOUT_BUCKET = 8;
    /** Grandparent fanout buckets are 1..MAX_GP_FANOUT_BUCKET (the last one means "or more"). */
    public static final int MAX_GP_FANOUT_BUCKET = 8;
    /** GPFAN: slew effect of a fanned-out driver two levels up: "gp intent/parent intent/node intent/gp fanout bucket" -> ps */
    private final Map<String, Float> gpTerms = new HashMap<>();
    private final Map<IntentCode, Map<IntentCode, Float>> edgeDefault = new EnumMap<>(IntentCode.class);
    private final Map<IntentCode, Map<TileTypeEnum, Float>> tile = new EnumMap<>(IntentCode.class);
    private final Map<IntentCode, Float> fanout = new EnumMap<>(IntentCode.class);
    private final Map<String, String> meta = new HashMap<>();
    private int unknownIntents = 0;

    public VersalDelayTerms() {
        this(FileTools.getRapidWrightPath() + File.separator + DEFAULT_FILE);
    }

    /** Loads the terms of a timing corner ({@code intersite_delay_terms.<corner>.txt}). */
    public VersalDelayTerms(VersalCorner corner) {
        this(FileTools.getRapidWrightPath() + File.separator + "timing" + File.separator + "versal" + File.separator
                + "intersite_delay_terms." + corner.getSuffix() + ".txt");
    }

    public VersalDelayTerms(String fileName) {
        try (BufferedReader br = new BufferedReader(new FileReader(fileName))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("#")) {
                    int c = line.indexOf(':');
                    if (c > 0) meta.put(line.substring(1, c).trim(), line.substring(c + 1).trim());
                    continue;
                }
                String[] f = line.split("\\s+");
                switch (f[0]) {
                    case "EDGE": {
                        // EDGE class n children siblings fanout delay samples
                        // (older files: EDGE p n children [siblings] delay samples)
                        IntentCode n = intent(f[2]);
                        if (n == null || intent(f[1].split(":")[0]) == null) break;
                        String key;
                        float v;
                        if (f.length >= 8) { key = f[3] + "/" + f[4] + "/" + f[5]; v = Float.parseFloat(f[6]); }
                        else if (f.length >= 7) { key = f[3] + "/" + f[4] + "/*"; v = Float.parseFloat(f[5]); }
                        else { key = f[3] + "/*/*"; v = Float.parseFloat(f[4]); }
                        edge.computeIfAbsent(f[1], k -> new EnumMap<>(IntentCode.class))
                            .computeIfAbsent(n, k -> new HashMap<>()).put(key, v);
                        break;
                    }
                    case "EDGEX": {
                        // EDGEX <parent class or intent> <node intent> <crossing signature> <correction> <samples>
                        IntentCode n = intent(f[2]);
                        if (n == null || intent(f[1].split(":")[0]) == null || f.length < 5) break;
                        edgeX.computeIfAbsent(f[1], k -> new EnumMap<>(IntentCode.class))
                             .computeIfAbsent(n, k -> new HashMap<>()).put(f[3], Float.parseFloat(f[4]));
                        break;
                    }
                    case "EDGE_DEFAULT": {
                        IntentCode p = intent(f[1]), n = intent(f[2]);
                        if (p == null || n == null) break;
                        edgeDefault.computeIfAbsent(p, k -> new EnumMap<>(IntentCode.class)).put(n, Float.parseFloat(f[3]));
                        break;
                    }
                    case "TILE": {
                        IntentCode p = intent(f[1]);
                        TileTypeEnum t;
                        try { t = TileTypeEnum.valueOf(f[2]); } catch (IllegalArgumentException e) { break; }
                        if (p == null) break;
                        tile.computeIfAbsent(p, k -> new EnumMap<>(TileTypeEnum.class)).put(t, Float.parseFloat(f[3]));
                        break;
                    }
                    case "FANOUT": {
                        IntentCode n = intent(f[1]);
                        if (n != null) fanout.put(n, Float.parseFloat(f[2]));
                        break;
                    }
                    case "GPFAN": {
                        // GPFAN <grandparent intent> <parent intent> <node intent> <gp fanout bucket> <delay> <samples>
                        if (f.length < 6 || intent(f[1]) == null || intent(f[2]) == null || intent(f[3]) == null) break;
                        gpTerms.put(f[1] + "/" + f[2] + "/" + f[3] + "/" + f[4], Float.parseFloat(f[5]));
                        break;
                    }
                    default:
                        throw new RuntimeException("Unknown keyword '" + f[0] + "' in " + fileName + ": " + line);
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Could not read Versal delay terms from " + fileName, e);
        }
    }

    private IntentCode intent(String name) {
        try {
            return IntentCode.valueOf(name);
        } catch (IllegalArgumentException e) {
            unknownIntents++;
            return null;
        }
    }

    /** Build the child-set key used by the EDGE table from a list of child intent codes. */
    public static String childKey(List<IntentCode> children) {
        if (children.isEmpty()) return LEAF;
        List<String> names = new ArrayList<>(children.size());
        for (IntentCode ic : children) {
            String s = ic.name().startsWith("NODE_") ? ic.name().substring(5) : ic.name();
            if (!names.contains(s)) names.add(s);
        }
        java.util.Collections.sort(names);
        return String.join("|", names);
    }

    /**
     * @param parentClass  parent intent name, optionally with ":family" for horizontal wires
     * @param parent       parent intent code
     * @param node         node intent code
     * @param childKey     child-set key of the node ({@link #childKey(List)})
     * @param siblingKey   child-set key of the parent
     * @param parentFanout number of children of the parent
     * @return the EDGE delay using the fallback order described in the class comment; 0 with a
     *         miss recorded if nothing is known
     */
    public float edgeDelay(String parentClass, IntentCode parent, IntentCode node, String childKey, String siblingKey,
                           int parentFanout, int[] missCounter) {
        String pf = String.valueOf(Math.max(1, Math.min(MAX_PARENT_FANOUT_BUCKET, parentFanout)));
        String[] suffixes = {childKey + "/" + siblingKey + "/" + pf, childKey + "/" + siblingKey + "/*", childKey + "/*/*"};
        String[] classes = parentClass.equals(parent.name()) ? new String[] {parentClass} : new String[] {parentClass, parent.name()};
        for (String pc : classes) {
            Map<IntentCode, Map<String, Float>> byNode = edge.get(pc);
            if (byNode == null) continue;
            Map<String, Float> byKey = byNode.get(node);
            if (byKey == null) continue;
            for (String suffix : suffixes) {
                Float v = byKey.get(suffix);
                if (v != null) return v;
            }
        }
        Map<IntentCode, Map<String, Float>> byNode = edge.get(parent.name());
        Map<String, Float> byKey = byNode == null ? null : byNode.get(node);
        if (byKey != null && childKey.indexOf('|') >= 0) {
            float best = Float.NEGATIVE_INFINITY;
            for (String c : childKey.split("\\|")) {
                Float s = byKey.get(c + "/*/*");
                if (s != null) best = Math.max(best, s);
            }
            if (best != Float.NEGATIVE_INFINITY) return best;
        }
        Float d = edgeDefault.getOrDefault(parent, java.util.Collections.emptyMap()).get(node);
        if (d != null) return d;
        if (missCounter != null) missCounter[0]++;
        return 0f;
    }

    /**
     * EDGEX correction for a node whose parent wire spans the given crossing signature (see
     * VersalTimingModel.crossingSignature): exact parent class first, then the bare parent intent; 0 if none.
     */
    public float crossingCorrection(String parentClass, IntentCode parent, IntentCode node, String fullSignature, String signature) {
        if (edgeX.isEmpty()) return 0f;
        for (String sig : fullSignature.equals(signature) ? new String[] {signature} : new String[] {fullSignature, signature}) {
            for (String pc : parentClass.equals(parent.name()) ? new String[] {parentClass} : new String[] {parentClass, parent.name()}) {
                Map<IntentCode, Map<String, Float>> byNode = edgeX.get(pc);
                if (byNode == null) continue;
                Map<String, Float> byKey = byNode.get(node);
                if (byKey == null) continue;
                Float v = byKey.get(sig);
                if (v != null) return v;
            }
        }
        return 0f;
    }

    public int getEdgeXEntryCount() {
        int n = 0;
        for (Map<IntentCode, Map<String, Float>> a : edgeX.values()) for (Map<String, Float> b : a.values()) n += b.size();
        return n;
    }

    /** Backwards-compatible lookup without family or fanout information. */
    public float edgeDelay(IntentCode parent, IntentCode node, String childKey, String siblingKey, int[] missCounter) {
        return edgeDelay(parent.name(), parent, node, childKey, siblingKey, 1, missCounter);
    }

    public boolean hasTileTerms(IntentCode parent) {
        return tile.containsKey(parent);
    }

    public float tileTerm(IntentCode parent, TileTypeEnum t) {
        Map<TileTypeEnum, Float> m = tile.get(parent);
        if (m == null) return 0f;
        return m.getOrDefault(t, 0f);
    }

    public float fanoutTerm(IntentCode node) {
        return fanout.getOrDefault(node, 0f);
    }

    /**
     * GPFAN term of an edge parent -> node whose parent is driven by a node of intent {@code grandparent}
     * with {@code gpFanout} children; 0 when the parent is a root or the combination is unlisted.
     */
    public float grandparentTerm(IntentCode grandparent, IntentCode parent, IntentCode node, int gpFanout) {
        if (grandparent == null || gpTerms.isEmpty()) return 0f;
        int b = Math.max(1, Math.min(MAX_GP_FANOUT_BUCKET, gpFanout));
        return gpTerms.getOrDefault(grandparent.name() + "/" + parent.name() + "/" + node.name() + "/" + b, 0f);
    }

    public Map<String, String> getMeta() {
        return meta;
    }

    public int getUnknownIntentCount() {
        return unknownIntents;
    }

    public int getEdgeEntryCount() {
        int n = 0;
        for (Map<IntentCode, Map<String, Float>> m : edge.values()) for (Map<String, Float> c : m.values()) n += c.size();
        return n;
    }
}
