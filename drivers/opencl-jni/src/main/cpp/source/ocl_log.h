/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
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
 */
#ifndef TORNADO_OCL_LOG_H
#define TORNADO_OCL_LOG_H

#define PRINT_KERNEL_EVENTS 0
#define PRINT_DATA_TIMES 0
#define PRINT_DATA_SIZES 0

#define LOG_JNI 0

#define LOG_OCL_JNI(name, result)                          \
    if (LOG_JNI == 1)  {                                   \
        std::cout << "[TornadoVM-JNI] Calling : " << name  \
        << " -> Status: " << result                        \
        << std::endl;                                     \
    }                                                     \

#endif //TORNADO_OCL_LOG_H
