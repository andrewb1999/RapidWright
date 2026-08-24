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

import com.xilinx.rapidwright.device.Site;

/**
 * Prices the clock network: when the clock reaches each site, and how much of
 * a launch/capture pair's shared clock path Vivado credits back as pessimism
 * removal.
 *
 * <p>Arrival is measured from the clock's source through the buffer, so a
 * launch arrival at {@link Corner#SLOW_MAX} and a capture arrival at
 * {@link Corner#SLOW_MIN} are directly Vivado's startpoint and endpoint clock
 * delays. Their difference plus the pessimism removal is the skew.
 */
public interface ClockDelayModel {

    TimingFidelity getFidelity();

    /**
     * Arrival of the clock at a site's clock input, at one corner.
     *
     * @return picoseconds from the clock source, or null if the site is not
     *         a sink of this clock or is not covered by the model.
     */
    Float getArrivalPs(Site site, Corner corner);

    /**
     * Clock pessimism removal for a pair of endpoints: the credit for the
     * portion of the clock path both share, which cannot be at two corners at
     * once. Zero when the model does not track it.
     */
    float getPessimismRemovalPs(Site launch, Site capture, boolean setup);

    /**
     * Arrival at one clock site pin. A block RAM's two clock pins take
     * different leaf routes and arrive at different times; models that track
     * routes per pin override this, others answer for the site.
     *
     * @param sitePin the site pin name, or null for the site's clock
     */
    default Float getArrivalPs(Site site, String sitePin, Corner corner) {
        return getArrivalPs(site, corner);
    }

    /** Pessimism removal for a pair of clock site pins; see {@link #getArrivalPs(Site, String, Corner)}. */
    default float getPessimismRemovalPs(Site launch, String launchPin, Site capture, String capturePin,
                                        boolean setup) {
        return getPessimismRemovalPs(launch, capture, setup);
    }
}
