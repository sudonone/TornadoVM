/*
 * Copyright (c) 2018, 2019, APT Group, School of Computer Science,
 * The University of Manchester. All rights reserved.
 * Copyright (c) 2009, 2017, Oracle and/or its affiliates. All rights reserved.
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
 * Authors: James Clarkson
 *
 */
package uk.ac.manchester.tornado.drivers.opencl.graal.compiler;

import static org.graalvm.compiler.phases.common.DeadCodeEliminationPhase.Optionality.Optional;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.guarantee;
import static uk.ac.manchester.tornado.api.exceptions.TornadoInternalError.unimplemented;
import static uk.ac.manchester.tornado.runtime.TornadoCoreRuntime.getTornadoRuntime;
import static uk.ac.manchester.tornado.runtime.common.Tornado.DUMP_COMPILED_METHODS;
import static uk.ac.manchester.tornado.runtime.common.Tornado.error;
import static uk.ac.manchester.tornado.runtime.common.Tornado.info;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.GraalCompiler;
import org.graalvm.compiler.core.common.alloc.ComputeBlockOrder;
import org.graalvm.compiler.core.common.alloc.RegisterAllocationConfig;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.debug.Debug.Scope;
import org.graalvm.compiler.debug.DebugCloseable;
import org.graalvm.compiler.debug.DebugDumpScope;
import org.graalvm.compiler.debug.DebugEnvironment;
import org.graalvm.compiler.debug.DebugTimer;
import org.graalvm.compiler.debug.internal.method.MethodMetricsRootScopeInfo;
import org.graalvm.compiler.lir.LIR;
import org.graalvm.compiler.lir.asm.CompilationResultBuilderFactory;
import org.graalvm.compiler.lir.framemap.FrameMap;
import org.graalvm.compiler.lir.framemap.FrameMapBuilder;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.lir.phases.AllocationPhase.AllocationContext;
import org.graalvm.compiler.lir.phases.PreAllocationOptimizationPhase.PreAllocationOptimizationContext;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.StructuredGraph.AllowAssumptions;
import org.graalvm.compiler.nodes.StructuredGraph.Builder;
import org.graalvm.compiler.nodes.StructuredGraph.ScheduleResult;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import org.graalvm.compiler.options.OptionValues;
import org.graalvm.compiler.phases.OptimisticOptimizations;
import org.graalvm.compiler.phases.PhaseSuite;
import org.graalvm.compiler.phases.common.DeadCodeEliminationPhase;
import org.graalvm.compiler.phases.tiers.HighTierContext;
import org.graalvm.compiler.phases.tiers.LowTierContext;
import org.graalvm.compiler.phases.util.Providers;

import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.meta.Assumptions;
import jdk.vm.ci.meta.DefaultProfilingInfo;
import jdk.vm.ci.meta.ProfilingInfo;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.TriState;
import uk.ac.manchester.tornado.api.exceptions.TornadoInternalError;
import uk.ac.manchester.tornado.drivers.opencl.OCLTargetDescription;
import uk.ac.manchester.tornado.drivers.opencl.graal.OCLProviders;
import uk.ac.manchester.tornado.drivers.opencl.graal.OCLSuitesProvider;
import uk.ac.manchester.tornado.drivers.opencl.graal.backend.OCLBackend;
import uk.ac.manchester.tornado.drivers.opencl.graal.compiler.OCLLIRGenerationPhase.LIRGenerationContext;
import uk.ac.manchester.tornado.runtime.common.Tornado;
import uk.ac.manchester.tornado.runtime.graal.TornadoLIRSuites;
import uk.ac.manchester.tornado.runtime.graal.TornadoSuites;
import uk.ac.manchester.tornado.runtime.graal.compiler.TornadoCompilerIdentifier;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoHighTierContext;
import uk.ac.manchester.tornado.runtime.graal.phases.TornadoMidTierContext;
import uk.ac.manchester.tornado.runtime.sketcher.Sketch;
import uk.ac.manchester.tornado.runtime.sketcher.TornadoSketcher;
import uk.ac.manchester.tornado.runtime.tasks.CompilableTask;
import uk.ac.manchester.tornado.runtime.tasks.meta.TaskMetaData;

/**
 * Static methods for orchestrating the compilation of a
 * {@linkplain StructuredGraph graph}.
 */
public class OCLCompiler {

    private static final AtomicInteger compilationId = new AtomicInteger();

    private static final DebugTimer CompilerTimer = Debug.timer("GraalCompiler");
    private static final DebugTimer FrontEnd = Debug.timer("FrontEnd");
    private static final DebugTimer BackEnd = Debug.timer("BackEnd");
    private static final DebugTimer EmitLIR = Debug.timer("EmitLIR");
    private static final DebugTimer EmitCode = Debug.timer("EmitCode");

    private static final OCLLIRGenerationPhase LIR_GENERATION_PHASE = new OCLLIRGenerationPhase();

    /**
     * Encapsulates all the inputs to a
     * {@linkplain GraalCompiler#compile(Request) compilation}.
     */
    public static class Request<T extends OCLCompilationResult> {

        public final StructuredGraph graph;
        public final ResolvedJavaMethod installedCodeOwner;
        public final Object[] args;
        public final TaskMetaData meta;
        public final Providers providers;
        public final OCLBackend backend;
        public final PhaseSuite<HighTierContext> graphBuilderSuite;
        public final OptimisticOptimizations optimisticOpts;
        public final ProfilingInfo profilingInfo;
        public final TornadoSuites suites;
        public final TornadoLIRSuites lirSuites;
        public final T compilationResult;
        public final CompilationResultBuilderFactory factory;
        public final boolean isKernel;
        public final boolean buildGraph;

        /**
         * @param graph
         *            the graph to be compiled
         * @param cc
         *            the calling convention for calls to the code compiled for
         *            {@code graph}
         * @param installedCodeOwner
         *            the method the compiled code will be associated with once
         *            installed. This argument can be null.
         * @param args
         *            the arguments for the method
         * @param meta
         *            Tornado metadata associated with this method
         * @param providers
         * @param backend
         * @param target
         * @param graphBuilderSuite
         * @param optimisticOpts
         * @param profilingInfo
         * @param speculationLog
         * @param suites
         * @param lirSuites
         * @param compilationResult
         * @param factory
         */
        public Request(StructuredGraph graph, ResolvedJavaMethod installedCodeOwner, Object[] args, TaskMetaData meta, Providers providers, OCLBackend backend,
                PhaseSuite<HighTierContext> graphBuilderSuite, OptimisticOptimizations optimisticOpts, ProfilingInfo profilingInfo, TornadoSuites suites, TornadoLIRSuites lirSuites,
                T compilationResult, CompilationResultBuilderFactory factory, boolean isKernel, boolean buildGraph) {
            this.graph = graph;
            this.installedCodeOwner = installedCodeOwner;
            this.args = args;
            this.meta = meta;
            this.providers = providers;
            this.backend = backend;
            this.graphBuilderSuite = graphBuilderSuite;
            this.optimisticOpts = optimisticOpts;
            this.profilingInfo = profilingInfo;
            this.suites = suites;
            this.lirSuites = lirSuites;
            this.compilationResult = compilationResult;
            this.factory = factory;
            this.isKernel = isKernel;
            this.buildGraph = buildGraph;
        }

        /**
         * Executes this compilation request.
         *
         * @return the result of the compilation
         */
        public T execute() {

            return OCLCompiler.compile(this);
        }
    }

    /**
     * Requests compilation of a given graph.
     *
     * @param graph
     *            the graph to be compiled
     * @param cc
     *            the calling convention for calls to the code compiled for
     *            {@code graph}
     * @param installedCodeOwner
     *            the method the compiled code will be associated with once
     *            installed. This argument can be null.
     *
     * @return the result of the compilation
     */
    public static <T extends OCLCompilationResult> T compileGraph(StructuredGraph graph, ResolvedJavaMethod installedCodeOwner, Object[] args, TaskMetaData meta, Providers providers,
            OCLBackend backend, PhaseSuite<HighTierContext> graphBuilderSuite, OptimisticOptimizations optimisticOpts, ProfilingInfo profilingInfo, TornadoSuites suites, TornadoLIRSuites lirSuites,
            T compilationResult, CompilationResultBuilderFactory factory, boolean isKernel) {
        // Ensure a debug configuration for this thread is initialized

        return compile(new Request<>(graph, installedCodeOwner, args, meta, providers, backend, graphBuilderSuite, optimisticOpts, profilingInfo, suites, lirSuites, compilationResult, factory,
                isKernel, false));

    }

    /**
     * Services a given compilation request.
     *
     * @return the result of the compilation
     */
    public static <T extends OCLCompilationResult> T compile(Request<T> r) {

        DebugEnvironment.ensureInitialized(getTornadoRuntime().getOptions());

        try (Scope s = MethodMetricsRootScopeInfo.createRootScopeIfAbsent(r.installedCodeOwner)) {
            assert !r.graph.isFrozen();

            try (Scope s0 = Debug.scope("GraalCompiler", r.graph, r.providers.getCodeCache()); DebugCloseable a = CompilerTimer.start()) {
                emitFrontEnd(r.providers, r.backend, r.installedCodeOwner, r.args, r.meta, r.graph, r.graphBuilderSuite, r.optimisticOpts, r.profilingInfo, r.suites, r.isKernel, r.buildGraph);
                emitBackEnd(r.graph, null, r.installedCodeOwner, r.backend, r.compilationResult, r.factory, null, r.lirSuites, r.isKernel);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
            return r.compilationResult;
        }
    }

    public static ProfilingInfo getProfilingInfo(StructuredGraph graph) {
        if (graph.method() != null) {
            return graph.method().getProfilingInfo();
        } else {
            return DefaultProfilingInfo.get(TriState.UNKNOWN);
        }
    }

    private static boolean isGraphEmpty(StructuredGraph graph) {
        return graph.start().next() == null;
    }

    /**
     * Builds the graph, optimizes it.
     */
    public static void emitFrontEnd(Providers providers, OCLBackend backend, ResolvedJavaMethod method, Object[] args, TaskMetaData meta, StructuredGraph graph,
            PhaseSuite<HighTierContext> graphBuilderSuite, OptimisticOptimizations optimisticOpts, ProfilingInfo profilingInfo, TornadoSuites suites, boolean isKernel, boolean buildGraph) {
        try (Scope s = Debug.scope("FrontEnd", new DebugDumpScope("FrontEnd")); DebugCloseable a = FrontEnd.start()) {

            /*
             * Register metadata with all tornado phases
             */
            ((OCLCanonicalizer) suites.getHighTier().getCustomCanonicalizer()).setContext(providers.getMetaAccess(), method, args, meta);

            final TornadoHighTierContext highTierContext = new TornadoHighTierContext(providers, graphBuilderSuite, optimisticOpts, method, args, meta, isKernel);

            if (buildGraph) {
                if (isGraphEmpty(graph)) {
                    graphBuilderSuite.apply(graph, highTierContext);
                    new DeadCodeEliminationPhase(Optional).apply(graph);
                } else {
                    Debug.dump(Debug.INFO_LEVEL, graph, "initial state");
                }
            }
            suites.getHighTier().apply(graph, highTierContext);
            graph.maybeCompress();

            final TornadoMidTierContext midTierContext = new TornadoMidTierContext(providers, backend, optimisticOpts, profilingInfo, method, args, meta);
            suites.getMidTier().apply(graph, midTierContext);

            graph.maybeCompress();

            final LowTierContext lowTierContext = new LowTierContext(providers, backend);
            suites.getLowTier().apply(graph, lowTierContext);

            Debug.dump(Debug.BASIC_LEVEL, graph.getLastSchedule(), "Final HIR schedule");
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    public static <T extends OCLCompilationResult> void emitBackEnd(StructuredGraph graph, Object stub, ResolvedJavaMethod installedCodeOwner, OCLBackend backend, T compilationResult,
            CompilationResultBuilderFactory factory, RegisterConfig registerConfig, TornadoLIRSuites lirSuites, boolean isKernel) {
        try (Scope s = Debug.scope("BackEnd", graph.getLastSchedule()); DebugCloseable a = BackEnd.start()) {
            LIRGenerationResult lirGen = null;
            lirGen = emitLIR(backend, graph, stub, registerConfig, lirSuites, compilationResult, isKernel);
            try (Scope s2 = Debug.scope("CodeGen", lirGen, lirGen.getLIR())) {
                int bytecodeSize = graph.method() == null ? 0 : graph.getBytecodeSize();
                compilationResult.setHasUnsafeAccess(graph.hasUnsafeAccess());
                emitCode(backend, graph.getAssumptions(), graph.method(), graph.getMethods(), bytecodeSize, lirGen, compilationResult, installedCodeOwner, factory, isKernel);
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    public static <T extends CompilationResult> LIRGenerationResult emitLIR(OCLBackend backend, StructuredGraph graph, Object stub, RegisterConfig registerConfig, TornadoLIRSuites lirSuites,
            T compilationResult, boolean isKernel) {
        try {
            return emitLIR0(backend, graph, stub, registerConfig, lirSuites, compilationResult, isKernel);
        } catch (Throwable e) {
            throw new TornadoInternalError(e);
        }
    }

    protected static <T extends CompilationResult> String getCompilationUnitName(StructuredGraph graph, T compilationResult) {
        if (compilationResult != null && compilationResult.getName() != null) {
            return compilationResult.getName();
        }
        ResolvedJavaMethod method = graph.method();
        if (method == null) {
            return "<unknown>";
        }
        return method.format("%H.%n(%p)");
    }

    private static <T extends CompilationResult> LIRGenerationResult emitLIR0(OCLBackend backend, StructuredGraph graph, Object stub, RegisterConfig registerConfig, TornadoLIRSuites lirSuites,
            T compilationResult, boolean isKernel) {
        try (Scope ds = Debug.scope("EmitLIR"); DebugCloseable a = EmitLIR.start()) {
            OptionValues options = graph.getOptions();
            ScheduleResult schedule = graph.getLastSchedule();
            Block[] blocks = schedule.getCFG().getBlocks();
            Block startBlock = schedule.getCFG().getStartBlock();
            assert startBlock != null;
            assert startBlock.getPredecessorCount() == 0;

            LIR lir = null;
            AbstractBlockBase<?>[] codeEmittingOrder = null;
            AbstractBlockBase<?>[] linearScanOrder = null;
            try (Scope s = Debug.scope("ComputeLinearScanOrder", lir)) {
                codeEmittingOrder = ComputeBlockOrder.computeCodeEmittingOrder(blocks.length, startBlock);
                linearScanOrder = ComputeBlockOrder.computeLinearScanOrder(blocks.length, startBlock);

                lir = new LIR(schedule.getCFG(), linearScanOrder, codeEmittingOrder, options);
                Debug.dump(Debug.INFO_LEVEL, lir, "After linear scan order");
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
            FrameMapBuilder frameMapBuilder = backend.newFrameMapBuilder(registerConfig);
            LIRGenerationResult lirGenRes = backend.newLIRGenerationResult(graph.compilationId(), lir, frameMapBuilder, graph, stub);
            LIRGeneratorTool lirGen = backend.newLIRGenerator(lirGenRes);
            NodeLIRBuilderTool nodeLirGen = backend.newNodeLIRBuilder(graph, lirGen);

            // LIR generation
            LIRGenerationContext context = new LIRGenerationContext(lirGen, nodeLirGen, graph, schedule, isKernel);
            LIR_GENERATION_PHASE.apply(backend.getTarget(), lirGenRes, context);

            try (Scope s = Debug.scope("LIRStages", nodeLirGen, lir)) {
                Debug.dump(Debug.BASIC_LEVEL, lir, "After LIR generation");
                LIRGenerationResult result = emitLowLevel(backend.getTarget(), lirGenRes, lirGen, lirSuites, backend.newRegisterAllocationConfig(registerConfig, new String[] {}));
                Debug.dump(Debug.BASIC_LEVEL, lir, "Before code generation");
                return result;
            } catch (Throwable e) {
                throw Debug.handle(e);
            }
        } catch (Throwable e) {
            throw Debug.handle(e);
        }
    }

    public static LIRGenerationResult emitLowLevel(OCLTargetDescription target, LIRGenerationResult lirGenRes, LIRGeneratorTool lirGen, TornadoLIRSuites lirSuites,
            RegisterAllocationConfig registerAllocationConfig) {
        final PreAllocationOptimizationContext preAllocOptContext = new PreAllocationOptimizationContext(lirGen);
        lirSuites.getPreAllocationStage().apply(target, lirGenRes, preAllocOptContext);
        AllocationContext allocContext = new AllocationContext(lirGen.getSpillMoveFactory(), registerAllocationConfig);
        lirSuites.getAllocationStage().apply(target, lirGenRes, allocContext);
        return lirGenRes;
    }

    public static void emitCode(OCLBackend backend, Assumptions assumptions, ResolvedJavaMethod rootMethod, List<ResolvedJavaMethod> inlinedMethods, int bytecodeSize, LIRGenerationResult lirGenRes,
            OCLCompilationResult compilationResult, ResolvedJavaMethod installedCodeOwner, CompilationResultBuilderFactory factory, boolean isKernel) {
        try (DebugCloseable a = EmitCode.start()) {
            FrameMap frameMap = lirGenRes.getFrameMap();
            final OCLCompilationResultBuilder crb = backend.newCompilationResultBuilder(lirGenRes, frameMap, compilationResult, factory, isKernel);
            backend.emitCode(crb, lirGenRes.getLIR(), installedCodeOwner);

            if (assumptions != null && !assumptions.isEmpty()) {
                compilationResult.setAssumptions(assumptions.toArray());
            }
            if (inlinedMethods != null) {
                compilationResult.setMethods(rootMethod, inlinedMethods);
            }

            compilationResult.setNonInlinedMethods(crb.getNonInlinedMethods());
            crb.finish();

            if (Debug.isCountEnabled()) {
                Debug.counter("CompilationResults").increment();
                Debug.counter("CodeBytesEmitted").add(compilationResult.getTargetCodeSize());
            }

            Debug.dump(Debug.BASIC_LEVEL, compilationResult, "After code generation");
        }
    }

    public static byte[] compileGraphForDevice(StructuredGraph graph, TaskMetaData meta, String entryPoint, OCLProviders providers, OCLBackend backend) {
        throw unimplemented();
    }

    public static OCLCompilationResult compileCodeForDevice(ResolvedJavaMethod resolvedMethod, Object[] args, TaskMetaData meta, OCLProviders providers, OCLBackend backend) {
        Tornado.info("Compiling %s on %s", resolvedMethod.getName(), backend.getDeviceContext().getDevice().getDeviceName());
        final TornadoCompilerIdentifier id = new TornadoCompilerIdentifier("compile-kernel" + resolvedMethod.getName(), compilationId.getAndIncrement());

        Builder builder = new Builder(getTornadoRuntime().getOptions(), AllowAssumptions.YES);
        builder.method(resolvedMethod);
        builder.compilationId(id);
        builder.name("compile-kernel" + resolvedMethod.getName());

        final StructuredGraph kernelGraph = builder.build();

        OptimisticOptimizations optimisticOpts = OptimisticOptimizations.ALL;
        ProfilingInfo profilingInfo = resolvedMethod.getProfilingInfo();

        OCLCompilationResult kernelCompResult = new OCLCompilationResult("internal", resolvedMethod.getName(), meta, backend);
        CompilationResultBuilderFactory factory = CompilationResultBuilderFactory.Default;

        final OCLSuitesProvider suitesProvider = providers.getSuitesProvider();
        Request<OCLCompilationResult> kernelCompilationRequest = new Request<>(kernelGraph, resolvedMethod, args, meta, providers, backend, suitesProvider.getGraphBuilderSuite(), optimisticOpts,
                profilingInfo, suitesProvider.createSuites(), suitesProvider.getLIRSuites(), kernelCompResult, factory, true, true);

        kernelCompilationRequest.execute();

        final Set<ResolvedJavaMethod> includedMethods = new HashSet<>();
        final Deque<ResolvedJavaMethod> worklist = new ArrayDeque<>();
        worklist.addAll(kernelCompResult.getNonInlinedMethods());

        while (!worklist.isEmpty()) {
            final ResolvedJavaMethod currentMethod = worklist.pop();
            if (!includedMethods.contains(currentMethod)) {
                final OCLCompilationResult compResult = new OCLCompilationResult("internal", currentMethod.getName(), meta, backend);
                Builder builder1 = new Builder(getTornadoRuntime().getOptions(), AllowAssumptions.YES);
                builder1.method(resolvedMethod);
                builder1.compilationId(id);
                builder1.name("internal" + currentMethod.getName());

                final StructuredGraph graph = builder.build();
                Request<OCLCompilationResult> methodcompilationRequest = new Request<>(graph, currentMethod, null, null, providers, backend, suitesProvider.getGraphBuilderSuite(), optimisticOpts,
                        profilingInfo, suitesProvider.createSuites(), suitesProvider.getLIRSuites(), compResult, factory, false, true);

                methodcompilationRequest.execute();
                worklist.addAll(compResult.getNonInlinedMethods());

                kernelCompResult.addCompiledMethodCode(compResult.getTargetCode());
            }
        }

        return kernelCompResult;
    }

    public static OCLCompilationResult compileSketchForDevice(Sketch sketch, CompilableTask task, OCLProviders providers, OCLBackend backend) {

        final StructuredGraph kernelGraph = (StructuredGraph) sketch.getGraph().getReadonlyCopy().copy();
        ResolvedJavaMethod resolvedMethod = kernelGraph.method();

        info("Compiling sketch %s on %s", resolvedMethod.getName(), backend.getDeviceContext().getDevice().getDeviceName());

        final TaskMetaData taskMeta = task.meta();
        final Object[] args = task.getArguments();

        OptimisticOptimizations optimisticOpts = OptimisticOptimizations.ALL;
        ProfilingInfo profilingInfo = resolvedMethod.getProfilingInfo();

        OCLCompilationResult kernelCompResult = new OCLCompilationResult(task.getId(), resolvedMethod.getName(), taskMeta, backend);
        CompilationResultBuilderFactory factory = CompilationResultBuilderFactory.Default;

        Set<ResolvedJavaMethod> methods = new HashSet<>();

        final OCLSuitesProvider suitesProvider = providers.getSuitesProvider();
        Request<OCLCompilationResult> kernelCompilationRequest = new Request<>(kernelGraph, resolvedMethod, args, taskMeta, providers, backend, suitesProvider.getGraphBuilderSuite(), optimisticOpts,
                profilingInfo, suitesProvider.createSuites(), suitesProvider.getLIRSuites(), kernelCompResult, factory, true, false);

        kernelCompilationRequest.execute();

        if (DUMP_COMPILED_METHODS) {
            methods.add(kernelGraph.method());
            methods.addAll(kernelGraph.getMethods());
            for (ResolvedJavaMethod m : kernelCompResult.getMethods()) {
                methods.add(m);
            }
        }

        final Deque<ResolvedJavaMethod> worklist = new ArrayDeque<>();
        worklist.addAll(kernelCompResult.getNonInlinedMethods());

        while (!worklist.isEmpty()) {
            final ResolvedJavaMethod currentMethod = worklist.pop();
            Sketch currentSketch = TornadoSketcher.lookup(currentMethod);
            final OCLCompilationResult compResult = new OCLCompilationResult(task.getId(), currentMethod.getName(), taskMeta, backend);
            @SuppressWarnings("unchecked") final StructuredGraph graph = (StructuredGraph) currentSketch.getGraph().getMutableCopy(null);

            Request<OCLCompilationResult> methodcompilationRequest = new Request<>(graph, currentMethod, null, null, providers, backend, suitesProvider.getGraphBuilderSuite(), optimisticOpts,
                    profilingInfo, suitesProvider.createSuites(), suitesProvider.getLIRSuites(), compResult, factory, false, false);

            methodcompilationRequest.execute();
            worklist.addAll(compResult.getNonInlinedMethods());

            if (DUMP_COMPILED_METHODS) {
                methods.add(graph.method());
                methods.addAll(graph.getMethods());
            }

            kernelCompResult.addCompiledMethodCode(compResult.getTargetCode());
        }

        if (DUMP_COMPILED_METHODS) {
            final Path outDir = Paths.get("./opencl-compiled-methods");
            if (!Files.exists(outDir)) {
                try {
                    Files.createDirectories(outDir);
                } catch (IOException e) {
                    error("unable to create cache dir: %s", outDir.toString());
                    error(e.getMessage());
                }
            }

            guarantee(Files.isDirectory(outDir), "cache directory is not a directory: %s", outDir.toAbsolutePath().toString());

            File file = new File(outDir + "/" + task.getId() + "-" + resolvedMethod.getName());
            try (PrintWriter pw = new PrintWriter(file);) {
                for (ResolvedJavaMethod m : methods) {
                    pw.printf("%s,%s\n", m.getDeclaringClass().getName(), m.getName());
                }
            } catch (IOException e) {
                error("unable to dump source: ", e.getMessage());
            }
        }

        return kernelCompResult;
    }
}