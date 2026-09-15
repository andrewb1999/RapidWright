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

import java.util.HashMap;
import java.util.Map;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.device.Site;
import com.xilinx.rapidwright.device.Tile;
import com.xilinx.rapidwright.rwroute.RouterHelper;

/**
 * The single-corner clock arrival table RWRoute already reads, behind the
 * {@link ClockDelayModel} interface.
 *
 * <p>{@link ClkRouteTiming} holds one delay per interconnect tile the clock
 * reaches. Both corners are answered with it and no pessimism removal is
 * tracked, so skew from this model is the plain arrival difference.
 */
public class ClkRouteTimingClockModel implements ClockDelayModel {

    private final ClkRouteTiming clkRouteTiming;
    private final Map<Site, String> siteTile = new HashMap<>();

    /**
     * @param clkRouteTiming The delay table.
     * @param clk            The routed clock net, used to find which
     *                       interconnect tile feeds each sink site.
     */
    public ClkRouteTimingClockModel(ClkRouteTiming clkRouteTiming, Net clk) {
        this.clkRouteTiming = clkRouteTiming;
        for (SitePinInst spi : clk.getPins()) {
            if (spi.isOutPin()) {
                continue;
            }
            Tile t = null;
            try {
                t = RouterHelper.getUpstreamINTTileOfClkIn(spi);
            } catch (RuntimeException e) {
                // Not a pin this table can describe.
            }
            if (t != null) {
                siteTile.put(spi.getSite(), t.getName());
            }
        }
    }

    public ClkRouteTiming getClkRouteTiming() {
        return clkRouteTiming;
    }

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.APPROXIMATE;
    }

    @Override
    public Float getArrivalPs(Site site, Corner corner) {
        String tile = siteTile.get(site);
        if (tile == null) {
            return null;
        }
        Short d = clkRouteTiming.getRouteDelaysToSinkINTTiles().get(tile);
        return d == null ? null : (float) d;
    }

    @Override
    public float getPessimismRemovalPs(Site launch, Site capture, boolean setup) {
        return 0;
    }
}
