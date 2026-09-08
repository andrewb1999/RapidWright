package com.xilinx.rapidwright.timing.versal;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Node;

import java.io.PrintWriter;
import java.util.Map;

/** Debug: model arrival state (arriving, i.e. with the node's own wire) at every node of every global clock net. */
public class VersalClockNodeDump {
    public static void main(String[] args) throws Exception {
        Design design = new java.io.File(args[1]).exists() ? Design.readCheckpoint(args[0], args[1]) : Design.readCheckpoint(args[0]);
        DesignTools.createMissingSitePinInsts(design);
        VersalClockModel cm = new VersalClockModel();
        try (PrintWriter pw = new PrintWriter(args[2])) {
            pw.println("net,node,parent,pip,arr_slow_max,arr_slow_min,arr_fast_max,arr_fast_min,implicit");
            for (Net net : design.getNets()) {
                if (!VersalClockModel.isGlobalClockNet(net)) continue;
                VersalClockModel.ClockTree t = cm.analyze(net);
                for (Map.Entry<Node, float[]> e : t.arriving.entrySet()) {
                    float[] a = VersalClockModel.ClockTree.arrival(e.getValue());
                    Node p = t.parent.get(e.getKey());
                    pw.printf("%s,%s,%s,%s,%.1f,%.1f,%.1f,%.1f,%d%n", net.getName(), e.getKey(), p, t.parentPip.get(e.getKey()), a[0], a[1], a[2], a[3],
                            t.parentPip.get(e.getKey()) == null && p != null ? 1 : 0);
                }
                pw.println("#sinks");
                for (Map.Entry<SitePinInst, float[]> e : t.sinkArrival.entrySet())
                    pw.printf("%s,%s,%s,,%.1f,%.1f,%.1f,%.1f,0%n", net.getName(), "SINK " + e.getKey().getSite().getName() + "/" + e.getKey().getName(), e.getKey().getConnectedNode(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]);
            }
        }
        System.out.println("tiers " + cm.getTierUse());
    }
}
