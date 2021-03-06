/*
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * The University of Manchester.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package uk.ac.manchester.tornado.examples.stencils;

import uk.ac.manchester.tornado.api.TaskSchedule;
import uk.ac.manchester.tornado.api.annotations.Parallel;

/**
 * Gauss-Seidel 2D stencil computation. This version has been adapted from the
 * PolyBench-ACC benchmark-suite available in:
 * https://github.com/cavazos-lab/PolyBench-ACC.
 */
public class GSeidel2D {

    final static int PB_STEPS = 20;
    final static int PB_N = 1024;
    final static int ITERATIONS = 31;

    private static float[] run2Dseidel(float[] a, int steps, int size) {
        for (int t = 0; t < steps - 1; t++) {
            for (int i = 1; i < (size - 2); i++) {
                for (int j = 1; j < (size - 2); j++) {
                    //@formatter:off
                    a[i * size + j] = (float) ((a[(i - 1) * size + (j - 1)] 
                                                + a[(i - 1) * size + j] 
                                                + a[(i - 1 ) * size + (j + 1)] 
                                                + a[i * size + (j - 1)] 
                                                + a[i * size + j] 
                                                + a[i * size + (j + 1)] 
                                                + a[(i + 1) * size + (j - 1)] 
                                                + a[(i + 1) * size + j] 
                                                + a[(i + 1) * size + (j + 1)]) 
                                                / 9.0);
                    //@formatter:on
                }
            }
        }
        return a;
    }

    private static void run2DseidelTornado(float[] a, int size) {
        for (@Parallel int i = 1; i < (size - 2); i++) {
            for (@Parallel int j = 1; j < (size - 2); j++) {
                //@formatter:off
                a[i * size + j] = (float) ((a[(i - 1) * size + (j - 1)]
                        + a[(i - 1) * size + j]
                        + a[(i - 1 ) * size + (j + 1)]
                        + a[i * size + (j - 1)]
                        + a[i * size + j]
                        + a[i * size + (j + 1)]
                        + a[(i + 1) * size + (j - 1)]
                        + a[(i + 1) * size + j]
                        + a[(i + 1) * size + (j + 1)])
                        / 9.0);
                //@formatter:on
            }
        }
    }

    private static float[] initArrayA(int size) {
        float[] a = new float[size * size];
        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                a[i * size + j] = ((float) i * (j + 2) + 10) / size;
            }
        }
        return a;
    }

    public static void main(String[] args) {
        int size,steps,iterations;

        size = PB_N;
        steps = PB_STEPS;
        iterations = ITERATIONS;

        if (args.length > 1) {
            size = Integer.parseInt(args[0]);
            steps = Integer.parseInt(args[1]);
            iterations = Integer.parseInt(args[2]);
        }

        float[] a = initArrayA(size);
        float[] aSeq = initArrayA(size);

        long start;
        long end;

        StringBuilder se = new StringBuilder();
        StringBuilder par = new StringBuilder();

        for (int i = 0; i < iterations; i++) {
            System.gc();
            start = System.nanoTime();
            aSeq = run2Dseidel(aSeq, steps, size);
            end = System.nanoTime();
            se.append("Sequential execution time of iteration is: " + (end - start) + " ns \n");
        }

        // @formatter:off
        final TaskSchedule graph = new TaskSchedule("s0")
                .streamIn(a, size)
                .task("t0", GSeidel2D::run2DseidelTornado, a, size)
                .streamOut(a);
        // @formatter:on

        for (int i = 0; i < iterations; i++) {
            start = System.nanoTime();
            for (int t = 0; t < steps; t++) {
                graph.execute();
            }
            end = System.nanoTime();
            par.append("Tornado execution time of iteration is: " + (end - start) + " ns \n");
        }

        System.out.println(se);
        System.out.println(par);
        System.out.println("Verify : " + verify(a, aSeq, size));

    }

    private static boolean verify(float[] tornado, float[] serial, int size) {
        boolean verified = true;

        for (int i = 0; i < size; i++) {
            for (int j = 0; j < size; j++) {
                if (Math.abs(tornado[i * size + j]) - Math.abs(serial[i * size + j]) > 0.1f) {
                    System.out.println(tornado[i * size + j] + " : " + serial[i * size + j]);
                    verified = false;
                    break;
                }
            }
        }
        return verified;
    }
}
