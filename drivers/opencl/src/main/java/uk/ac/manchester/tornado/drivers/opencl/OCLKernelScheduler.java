/*
 * This file is part of Tornado: A heterogeneous programming framework: 
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2013-2020, APT Group, Department of Computer Science,
 * The University of Manchester. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 *
 */
package uk.ac.manchester.tornado.drivers.opencl;

import uk.ac.manchester.tornado.api.WorkerGrid;
import uk.ac.manchester.tornado.api.common.Event;
import uk.ac.manchester.tornado.api.profiler.ProfilerType;
import uk.ac.manchester.tornado.runtime.common.Tornado;
import uk.ac.manchester.tornado.runtime.common.TornadoOptions;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

import java.util.Arrays;

public abstract class OCLKernelScheduler {

    protected final OCLDeviceContext deviceContext;

    protected double min;
    protected double max;

    OCLKernelScheduler(final OCLDeviceContext context) {
        deviceContext = context;
    }

    public abstract void calculateGlobalWork(final TaskMetaData meta, long batchThreads);

    public abstract void calculateLocalWork(final TaskMetaData meta);

    public int submit(final OCLKernel kernel, final TaskMetaData meta, long batchThreads) {
        return submit(kernel, meta, null, batchThreads);
    }

    private void updateProfiler(final int taskEvent, final TaskMetaData meta) {
        if (TornadoOptions.isProfilerEnabled()) {
            Event tornadoKernelEvent = deviceContext.resolveEvent(taskEvent);
            tornadoKernelEvent.waitForEvents();
            long timer = meta.getProfiler().getTimer(ProfilerType.TOTAL_KERNEL_TIME);
            // Register globalTime
            meta.getProfiler().setTimer(ProfilerType.TOTAL_KERNEL_TIME, timer + tornadoKernelEvent.getExecutionTime());
            // Register the time for the task
            meta.getProfiler().setTaskTimer(ProfilerType.TASK_KERNEL_TIME, meta.getId(), tornadoKernelEvent.getExecutionTime());
        }
    }

    public int launch(final OCLKernel kernel, final TaskMetaData meta, final int[] waitEvents, long batchThreads) {
        if (meta.isWorkerGridAvailable()) {
            WorkerGrid grid = meta.getWorkerGrid(meta.getId());
            long[] global = grid.getGlobalWork();
            long[] offset = grid.getGlobalOffset();
            long[] local = grid.getLocalWork();
            return deviceContext.enqueueNDRangeKernel(kernel, grid.dimension(), offset, global, local, waitEvents);
        } else {
            System.out.println("Running with Local Work: " + meta.getLocalWork());
            return deviceContext.enqueueNDRangeKernel(kernel, meta.getDims(), meta.getGlobalOffset(), meta.getGlobalWork(), (meta.shouldUseOpenCLDriverScheduling() ? null : meta.getLocalWork()),
                    waitEvents);
        }
    }

    private void checkLocalWorkGroupFitsOnDevice(final TaskMetaData meta) {
        // Check if the LocalWorkGroup fits on the device
        WorkerGrid grid = meta.getWorkerGrid(meta.getId());
        long[] local = grid.getLocalWork();
        if (local != null) {
            OCLGridInfo gridInfo = new OCLGridInfo(deviceContext.getDevice(), local);
            boolean checkedDimensions = gridInfo.checkGridDimensions();
            if (!checkedDimensions) {
                System.out.println("Warning: TornadoVM changed the user-defined local size to null. Now, the OpenCL driver will select the best configuration.");
                grid.setLocalWorkToNull();
            }
        }
    }

    public int submit(final OCLKernel kernel, final TaskMetaData meta, final int[] waitEvents, long batchThreads) {

        if (!meta.isWorkerGridAvailable()) {
            if (!meta.isGlobalWorkDefined()) {
                calculateGlobalWork(meta, batchThreads);
            }
            if (!meta.isLocalWorkDefined()) {
                calculateLocalWork(meta);
            }
        } else {
            checkLocalWorkGroupFitsOnDevice(meta);
        }

        if (meta.isDebug()) {
            meta.printThreadDims();
        }
        final int taskEvent = launch(kernel, meta, waitEvents, batchThreads);
        updateProfiler(taskEvent, meta);
        return taskEvent;
    }

}
