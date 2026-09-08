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

/** Timing corners of the Versal delay model; each has its own pair of data files. */
public enum VersalCorner {
    /** slow process, max delay: setup analysis (the default files without a corner suffix) */
    SLOW_MAX("slow_max", true),
    /** slow process, min delay: hold analysis */
    SLOW_MIN("slow_min", false),
    /** fast process, max delay: setup analysis */
    FAST_MAX("fast_max", true),
    /** fast process, min delay: hold analysis */
    FAST_MIN("fast_min", false);

    private final String suffix;
    private final boolean max;

    VersalCorner(String suffix, boolean max) {
        this.suffix = suffix;
        this.max = max;
    }

    /** File-name suffix used by the data files, e.g. "slow_min". */
    public String getSuffix() {
        return suffix;
    }

    /** Whether this is a max-delay (setup) corner; otherwise min-delay (hold). */
    public boolean isMax() {
        return max;
    }

    public static VersalCorner fromString(String s) {
        for (VersalCorner c : values()) if (c.suffix.equalsIgnoreCase(s) || c.name().equalsIgnoreCase(s)) return c;
        throw new IllegalArgumentException("unknown corner " + s);
    }
}
