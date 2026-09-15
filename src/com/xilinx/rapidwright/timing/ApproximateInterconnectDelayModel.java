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

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;

/**
 * The fitted UltraScale+ interconnect estimate, behind the
 * {@link InterconnectDelayModel} interface.
 *
 * <p>{@link TimingModel} prices a connection from wire classes and distance
 * with a dozen fitted constants, and has one corner. Both corners are answered
 * with that one number: the estimate has no minimum-delay information, and
 * returning the same value is the honest statement of that.
 */
public class ApproximateInterconnectDelayModel implements InterconnectDelayModel {

    private final TimingModel timingModel;

    public ApproximateInterconnectDelayModel(TimingModel timingModel) {
        this.timingModel = timingModel;
    }

    public TimingModel getTimingModel() {
        return timingModel;
    }

    @Override
    public TimingFidelity getFidelity() {
        return TimingFidelity.APPROXIMATE;
    }

    @Override
    public Float getNetDelayPs(Net net, SitePinInst sink, Corner corner) {
        SitePinInst source = net.getSource();
        if (source == null || sink == null) {
            return null;
        }
        return timingModel.calcDelay(source, sink, net);
    }
}
