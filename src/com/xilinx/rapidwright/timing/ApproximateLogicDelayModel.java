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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.device.SiteTypeEnum;

/**
 * The UltraScale+ logic delay lookup, behind the {@link LogicDelayModel}
 * interface.
 *
 * <p>{@link DelayModel} carries one delay per arc with no corner and no
 * register requirements, so both corners are answered with the same value
 * and setup and hold are unknown. Configuration-dependent arcs (LUT
 * equations, carry initialization) are priced at their default
 * configuration here; {@link TimingGraph} still resolves those itself.
 */
public class ApproximateLogicDelayModel implements LogicDelayModel {

    private final DelayModel delayModel;

    public ApproximateLogicDelayModel(DelayModel delayModel) {
        this.delayModel = delayModel;
    }

    public DelayModel getDelayModel() {
        return delayModel;
    }

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.APPROXIMATE;
    }

    @Override
    public Float getPropagationDelayPs(Cell cell, String fromBelPin, String toBelPin, Corner corner) {
        String belName = cell.getBELName();
        if (belName == null) {
            return null;
        }
        Short belIdx;
        try {
            belIdx = delayModel.getBELIndex(belName);
        } catch (RuntimeException e) {
            // The lookup unboxes a missing entry.
            return null;
        }
        if (belIdx == null) {
            return null;
        }
        short delay = delayModel.getLogicDelay(belIdx, fromBelPin, toBelPin);
        return delay < 0 ? null : (float) delay;
    }

    @Override
    public Float getSetupPs(Cell cell, String clockBelPin, String dataBelPin, Corner corner) {
        return null;
    }

    @Override
    public Float getHoldPs(Cell cell, String clockBelPin, String dataBelPin, Corner corner) {
        return null;
    }

    @Override
    public Float getIntraSiteDelayPs(SiteTypeEnum siteType, String fromBelPin, String toBelPin,
                                     Corner corner) {
        Short d;
        try {
            d = delayModel.getIntraSiteDelay(siteType, fromBelPin, toBelPin);
        } catch (RuntimeException e) {
            return null;
        }
        // The table answers a missing arc with a negative sentinel.
        return d == null || d < 0 ? null : (float) d;
    }
}
