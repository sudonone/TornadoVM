# TornadoVM Changelog
This file summarizes the new features and major changes for each *TornadoVM* version.

## TornadoVM 0.8
19/11/2020

- Added PTX backend for NVIDIA GPUs
	- Build TornadoVM using `make BACKEND=ptx,opencl` to obtain the two supported backends.
- TornadoVM JIT Compiler aligned with Graal 20.2.0 
- Support for other JDKs:
	- Red Hat Mandrel 11.0.9
	- Amazon Coretto 11.0.9
	- GraalVM LabsJDK 11.0.8
	- OpenJDK 11.0.8
	- OpenJDK 12.0.2
	- OpenJDK 13.0.2
	- OpenJDK 14.0.2
- Support for hybrid (CPU-GPU) parallel reductions
- New API for generic kernel dispatch. It introduces the concept of `WorkerGrid` and `GridTask`
	- A `WorkerGrid` is an object that stores how threads are organized on an  OpenCL device: 
        ```java
        WorkerGrid1D worker1D = new WorkerGrid1D(4096);
        ```
	- A `GridTask` is a map that relates a task-name with a worker-grid.  
       ```java
        GridTask gridTask = new GridTask();
        gridTask.set("s0.t0", worker1D);
       ```
	- A TornadoVM Task-Schedule can be executed using a `GridTask`:
      ```java
      ts.execute(gridTask);
      ```
	- More info: [link](https://github.com/beehive-lab/TornadoVM/commit/6191720fd947d3102e784dade9e576ed8af11068)
- TornadoVM profiler improved 
	- Profiler metrics added
	- Code features per task-graph
- Lazy device initialisation moved to early initialisation of PTX and OpenCL devices
- Initial support for Atomics (OpenCL backend) - [Link to examples](https://github.com/beehive-lab/TornadoVM/blob/master/unittests/src/main/java/uk/ac/manchester/tornado/unittests/atomics/TestAtomics.java)
- Task Schedules with 11-14 parameters supported
- Documentation improved
- Bug fixes for code generation,  numeric promotion, basic block traversal, Xilinx FPGA compilation. 

## TornadoVM 0.7
22/06/2020

  * Support for ARM Mali GPUs. See documentation [here](17_MALI.md)
  * Support parallel reductions on FPGAs
  * Agnostic FPGA vendor compilation via [configuration files](7_FPGA.md#step-1-updatecreate-the-fpgas-configuration-file) (Intel & Xilinx)
  * Support for AWS on Xilinx FPGAs [link](16_AWS.md)
  * Recompilation for different input data sizes supported
  * New TornadoVM API calls:
     a) Update references for re-compilation:
        `taskSchedule.updateReferences(oldRef, newRef);`
     b) Use the default OpenCL scheduler:
        `taskSchedule.useDefaultThreadScheduler(true);`
  * Use of JMH for benchmarking
  * Support for Fused Multiply-Add (FMA) instructions
  * Easy-selection of different devices for unit-tests
     `tornado-test.py -V --debug -J"-Dtornado.unittests.device=0:1"`
  * Bailout mechanism improved from parallel to sequential
  * Improve thread scheduling
  * Support for private memory allocation
  * Assertion mode included
  * Documentation improved
  * Several bug fixes


## TornadoVM 0.6
21/02/2020

  * TornadoVM compatible with GraalVM 19.3.0 using JDK 8 and JDK 11
  * TornadoVM compiler update for using Graal 19.3.0 compiler API
  * Support for dynamic languages on top of Truffle - [examples](https://github.com/beehive-lab/TornadoVM/tree/master/examples/src/main/java/uk/ac/manchester/tornado/examples/polyglot)
  * Support for multiple tasks per task-schedule on FPGAs (Intel and Xilinx)
  * Support for OSX Mojave and Catalina
  * Task-schedule name handling for FPGAs improved
  * Exception handling improved
  * Reductions for `long` type supported
  * Bug fixes for ternary conditions, reductions and code generator
  * Documentation improved


## TornadoVM 0.5
16/12/2019

  * Initial support for Xilinx FPGAs
  * TornadoVM API classes are now `Serializable`
  * Initial support for local memory for reductions
  * JVMCI built with local annotation patch removed. Now TornadoVM requires unmodified JDK8 with JVMCI support
  * Support of multiple reductions within the same `task-schedules`
  * Emulation mode on Intel FPGAs is fixed
  * Fix reductions on Intel Integrated Graphics
  * TornadoVM driver OpenCL initialization and OpenCL code cache improved
  * Refactoring of the FPGA execution modes (full JIT and emulation modes improved).


## TornadoVM 0.4
14/10/2019

  * Profiler supported (See [PROFILER](9_PROFILER.md))
    * Use `-Dtornado.profiler=True` to enable profiler
    * Use `-Dtornado.profiler=True -Dtornado.profiler.save=True` to dump the profiler logs
  * Feature extraction added (See [PROFILER](9_PROFILER.md))
    * Use `-Dtornado.feature.extraction=True` to enable code extraction features
  * Mac OSx support (See [INSTALL](1_INSTALL.md))
  * Automatic reductions composition (map-reduce) within the same task-schedule
  * Bug related to a memory leak when running on GPUs solved
  * Bug fixes and stability improvements


## TornadoVM 0.3
22/07/2019

  * New Matrix 2D and Matrix 3D classes with type specializations.
  * New API-call `TaskSchedule#batch` for batch processing. It allows programmers to run with more data than the maximum capacity of the accelerator by creating batches of executions.
  * FPGA full automatic compilation pipeline.
  * FPGA options simplified:
      * `-Dtornado.precompiled.binary=<binary>` for loading the bitstream.
      * `-Dtornado.opencl.userelative=True` for using relative addresses.
      * `-Dtornado.opencl.codecache.loadbin=True` *removed*.
  * Reductions support enhanced and fully automated on GPUs and CPUs.  
  * Initial support for reductions on FPGAs.
  * Initial API for profiling tasks integrated.

## TornadoVM 0.2
25/02/2019
  * Rename to TornadoVM
  * Device selection for better performance (CPU, multi-core, GPU, FPGA) via an API for Dynamic Reconfiguration
      * Added methods `executeWithProfiler` and `executeWithProfilerSequential` with an input policy.
      * Policies: `Policy.PERFORMANCE`, `Policy.END_2_END`, and `Policy.LATENCY` implemented.
  * Basic heuristic for predicting the highest performing target device with Dynamic Reconfiguration
  * Initial FPGA integration for Altera FPGAs:
    * Full JIT compilation mode
    * Ahead of time compilation mode
    * Emulation/debug mode
  * FPGA JIT compiler specializations
  * Added support for Java reductions:
    * Compiler specializations for CPU and GPU reductions
  * Performance and stability fixes

## Tornado 0.1.0
07/09/2018

  * Initial Implementation of the Tornado compiler
  * Initial GPU/CPU code generation for OpenCL
  * Initial support in the runtime to execute OpenCL programs generated by the Tornado JIT compiler
  * Initial Tornado-API release (@Parallel annotation and TaskSchedules)
  * Multi-GPU enabled through multiple tasks-schedules
