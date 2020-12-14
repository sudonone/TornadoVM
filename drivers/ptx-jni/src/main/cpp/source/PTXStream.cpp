/*
 * This file is part of Tornado: A heterogeneous programming framework:
 * https://github.com/beehive-lab/tornadovm
 *
 * Copyright (c) 2020, APT Group, Department of Computer Science,
 * School of Engineering, The University of Manchester. All rights reserved.
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

#include <jni.h>
#include <cuda.h>

#include <iostream>
#include "PTXStream.h"
#include "PTXModule.h"
#include "PTXEvent.h"
#include "ptx_utils.h"
#include "ptx_log.h"

/*
    A singly linked list (with elements of type StagingAreaList) is used to keep all the allocated pinned memory through cuMemAllocHost.
    A queue (with elements of type QueueNode) is used to hold all the free (no longer used) pinned memory regions.

    On a new read/write we call get_first_free_staging_area which will try to dequeue a pinned memory region to use it.
*/

/*
    Linked list which holds information regarding the pinned memory allocated.

    next            -- next element of the list
    staging_area    -- pointer to the pinned memory region
    length          -- length in bytes of the memory region referenced by staging_area
*/
typedef struct area_list {
    struct area_list *next;
    void *staging_area;
    size_t length;
} StagingAreaList;

/*
    Head of the allocated pinned memory list
 */
static StagingAreaList *head = NULL;

/*
    Linked list used to implement a queue which holds the free (no longer used) pinned memory regions.
*/
typedef struct queue_list {
    StagingAreaList* element;
    struct queue_list *next;
} QueueNode;

/*
    Pointers to the front and rear of the queue.
*/
static QueueNode *front = NULL;
static QueueNode *rear = NULL;

/*
    Adds a free pinned memory region to the queue.
*/
static void enqueue(StagingAreaList *region) {
    if (front == NULL) {
        front = static_cast<QueueNode *>(malloc(sizeof(QueueNode)));
        front->next = NULL;
        front->element = region;

        rear = front;
    } else {
        QueueNode *newRear = static_cast<QueueNode *>(malloc(sizeof(QueueNode)));
        newRear->next = NULL;
        newRear->element = region;

        rear->next = newRear;
        rear = newRear;
    }
}

/*
    Returns the first element (free pinned memory region) of the queue.
*/
static StagingAreaList* dequeue() {
    if (front == NULL) {
        return NULL;
    }
    StagingAreaList* region = front->element;
    QueueNode *oldFront = front;
    front = front->next;
    free(oldFront);

    return region;
}

/*
    Free the queue.
*/
static void free_queue() {
    if (front == NULL) return;

    QueueNode *node;
    while(front != NULL) {
        node = front;
        front = front->next;
        free(node);
    }
}

/*
    Checks if the given staging region can fit into the required size. If not, it allocates the required pinned memory.
*/
static StagingAreaList *check_or_init_staging_area(size_t size, StagingAreaList *list) {
    // Create
    if (list == NULL) {
        list = static_cast<StagingAreaList *>(malloc(sizeof(StagingAreaList)));
        CUresult result = cuMemAllocHost(&(list->staging_area), size);
        if (result != CUDA_SUCCESS) {
            std::cout << "\t[JNI] " << __FILE__ << ":" << __LINE__ << " in function: " << __FUNCTION__ << " result = " << result << std::endl;
            std::flush(std::cout);
            return NULL;
        }
        list->length = size;
        list->next = NULL;
    }

    // Update
    else if (list->length < size) {
        CUresult result = cuMemFreeHost(list->staging_area);
        if (result != CUDA_SUCCESS) {
            std::cout << "\t[JNI] " << __FILE__ << ":" << __LINE__ << " in function: " << __FUNCTION__ << " result = " << result << std::endl;
            std::flush(std::cout);
            return NULL;
        }
        result = cuMemAllocHost(&(list->staging_area), size);
        if (result != CUDA_SUCCESS) {
            std::cout << "\t[JNI] " << __FILE__ << ":" << __LINE__ << " in function: " << __FUNCTION__ << " result = " << result << std::endl;
            std::flush(std::cout);
            return NULL;
        }
        list->length = size;
    }
    return list;
}

/*
    Returns a StagingAreaList with pinned memory of given size.
*/
static StagingAreaList *get_first_free_staging_area(size_t size) {
    // Dequeue the first free staging area
    StagingAreaList *list = dequeue();

    list = check_or_init_staging_area(size, list);
    if (head == NULL) head = list;

    return list;
}

/*
    Called by cuStreamAddCallback, enqueues a StagingAreaList to the free queue for memory reuse.
*/
static void set_to_unused(CUstream hStream,  CUresult status, void *list) {
    StagingAreaList *stagingList = (StagingAreaList *) list;
    enqueue(stagingList);
}

/*
    Free all the allocated pinned memory.
*/
static CUresult free_staging_area_list() {
    CUresult result;
    while (head != NULL) {
        result = cuMemFreeHost(head->staging_area);
        if (result != CUDA_SUCCESS) {
            std::cout << "\t[JNI] " << __FILE__ << ":" << __LINE__ << " in function: " << __FUNCTION__ << " result = " << result << std::endl;
            std::flush(std::cout);
        }
        StagingAreaList *list = head;
        head = head->next;
        free(list);
    }
    return result;
}

static void stream_from_array(JNIEnv *env, CUstream *stream_ptr, jbyteArray array) {
    env->GetByteArrayRegion(array, 0, sizeof(CUstream), reinterpret_cast<jbyte *>(stream_ptr));
}

static jbyteArray array_from_stream(JNIEnv *env, CUstream *stream) {
    jbyteArray array = env->NewByteArray(sizeof(CUstream));
    env->SetByteArrayRegion(array, 0, sizeof(CUstream), reinterpret_cast<const jbyte *>(stream));
    return array;
}


jobjectArray transferFromDeviceToHostBlock(JNIEnv *env, jclass clazz, jlong device_ptr, jlong length, jbyteArray array, jlong host_offset, jbyteArray stream_wrapper, int NATIVE_J_TYPE) {
    CUevent beforeEvent, afterEvent;
    CUstream stream;
    stream_from_array(env, &stream, stream_wrapper);

    StagingAreaList *staging_list = get_first_free_staging_area(length);
    record_events_create(&beforeEvent, &afterEvent);
    record_event_begin(&beforeEvent, &stream);

    CUresult result = cuMemcpyDtoHAsync(staging_list->staging_area, device_ptr, (size_t) length, stream);
    LOG_PTX_JNI("cuMemcpyDtoHAsync", result);

    record_event_end(&afterEvent, &stream);
    if (cuEventQuery(afterEvent) != CUDA_SUCCESS) {
        cuEventSynchronize(afterEvent);
    }
    env->SetByteArrayRegion(array, host_offset / NATIVE_J_TYPE, length / NATIVE_J_TYPE, static_cast<const jbyte *>(staging_list->staging_area));
    set_to_unused(stream, result, staging_list);
    return wrapper_from_events(env, &beforeEvent, &afterEvent);
}

jobjectArray transferFromDeviceToHostAsync(JNIEnv *env, jclass clazz, jlong device_ptr, jlong length, jbyteArray array, jlong host_offset, jbyteArray stream_wrapper) {
    /// FIXME : REVIEW THIS LINE
    jbyte *native_array = static_cast<jbyte *>(env->GetPrimitiveArrayCritical(array, 0));
    CUstream stream;  
    stream_from_array(env, &stream, stream_wrapper);

    CUevent beforeEvent;
    CUevent afterEvent;
    record_events_create(&beforeEvent, &afterEvent);  
    record_event_begin(&beforeEvent, &stream);  
 
    CUresult result = cuMemcpyDtoHAsync(native_array + host_offset, device_ptr, (size_t) length, stream);
    LOG_PTX_JNI("cuMemcpyDtoHAsync", result);
  
    record_event_end(&afterEvent, &stream);
    result = cuMemFreeHost(native_array);
    LOG_PTX_JNI("cuMemFreeHost", result);
    env->ReleasePrimitiveArrayCritical(array, native_array, 0);
    return wrapper_from_events(env, &beforeEvent, &afterEvent);  
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoH
 * Signature: (JJ[BJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoH__JJ_3BJ_3B
        (JNIEnv * env, jclass klass, jlong device_ptr, jlong length, jbyteArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostBlock(env, klass, device_ptr, length, array, host_offset, stream_wrapper,
                                         sizeof(jbyte));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoH
 * Signature: (JJ[SJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoH__JJ_3SJ_3B
        (JNIEnv * env, jclass klass, jlong device_ptr, jlong length, jshortArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostBlock(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jshort));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoH
 * Signature: (JJ[CJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoH__JJ_3CJ_3B
        (JNIEnv * env, jclass klass, jlong device_ptr, jlong length, jcharArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostBlock(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jchar));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoH
 * Signature: (JJ[IJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoH__JJ_3IJ_3B
        (JNIEnv * env, jclass klass, jlong device_ptr, jlong length, jintArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostBlock(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jint));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoH
 * Signature: (JJ[JJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoH__JJ_3JJ_3B
        (JNIEnv * env, jclass klass, jlong device_ptr, jlong length, jlongArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostBlock(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jlong));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoH
 * Signature: (JJ[FJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoH__JJ_3FJ_3B
        (JNIEnv * env, jclass klass, jlong device_ptr, jlong length, jfloatArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostBlock(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jfloat));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoH
 * Signature: (JJ[DJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoH__JJ_3DJ_3B
        (JNIEnv * env, jclass klass, jlong device_ptr, jlong length, jdoubleArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostBlock(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jdouble));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoHAsync
 * Signature: (JJ[BJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoHAsync__JJ_3BJ_3B
        (JNIEnv * env, jclass klass, jlong devicePtr, jlong length, jbyteArray array, jlong hostOffset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostAsync(env, klass, devicePtr, length, array, hostOffset, stream_wrapper);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoHAsync
 * Signature: (JJ[SJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoHAsync__JJ_3SJ_3B
        (JNIEnv * env, jclass klass, jlong devicePtr, jlong length, jshortArray array, jlong hostOffset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostAsync(env, klass, devicePtr, length, reinterpret_cast<jbyteArray>(array), hostOffset, stream_wrapper);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoHAsync
 * Signature: (JJ[CJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoHAsync__JJ_3CJ_3B
        (JNIEnv * env, jclass klass, jlong devicePtr, jlong length, jcharArray array, jlong hostOffset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostAsync(env, klass, devicePtr, length, reinterpret_cast<jbyteArray>(array), hostOffset, stream_wrapper);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoHAsync
 * Signature: (JJ[IJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoHAsync__JJ_3IJ_3B
        (JNIEnv * env, jclass klass, jlong devicePtr, jlong length, jintArray array, jlong hostOffset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostAsync(env, klass, devicePtr, length, reinterpret_cast<jbyteArray>(array), hostOffset, stream_wrapper);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoHAsync
 * Signature: (JJ[JJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoHAsync__JJ_3JJ_3B
        (JNIEnv * env, jclass klass, jlong devicePtr, jlong length, jlongArray array, jlong hostOffset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostAsync(env, klass, devicePtr, length, reinterpret_cast<jbyteArray>(array), hostOffset, stream_wrapper);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoHAsync
 * Signature: (JJ[FJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoHAsync__JJ_3FJ_3B
        (JNIEnv * env, jclass klass, jlong devicePtr, jlong length, jfloatArray array, jlong hostOffset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostAsync(env, klass, devicePtr, length, reinterpret_cast<jbyteArray>(array), hostOffset, stream_wrapper);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayDtoHAsync
 * Signature: (JJ[DJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayDtoHAsync__JJ_3DJ_3B
        (JNIEnv * env, jclass klass, jlong devicePtr, jlong length, jdoubleArray array, jlong hostOffset, jbyteArray stream_wrapper) {
    return transferFromDeviceToHostAsync(env, klass, devicePtr, length, reinterpret_cast<jbyteArray>(array), hostOffset, stream_wrapper);
}

jobjectArray transferFromHostToDeviceBlocking (JNIEnv *env, jclass clazz,
                                               jlong device_ptr,
                                               jlong length,
                                               jbyteArray array,
                                               jlong host_offset,
                                               jbyteArray stream_wrapper,
                                               int numBytesNativeType) {
    CUevent beforeEvent, afterEvent;  
    CUstream stream;
    stream_from_array(env, &stream, stream_wrapper);  
  
    StagingAreaList *staging_list = get_first_free_staging_area(length);  
    env->GetByteArrayRegion(array, host_offset / numBytesNativeType, length / numBytesNativeType,
                            static_cast<jbyte *>(staging_list->staging_area));
 
    record_events_create(&beforeEvent, &afterEvent);  
    record_event_begin(&beforeEvent, &stream);

    CUresult result = cuMemcpyHtoDAsync(device_ptr, staging_list->staging_area, (size_t) length, stream);
    LOG_PTX_JNI("cuMemcpyHtoDAsync", result);
  
    record_event_end(&afterEvent, &stream);  
    result = cuStreamAddCallback(stream, set_to_unused, staging_list, 0);
    LOG_PTX_JNI("cuStreamAddCallback", result);

    return wrapper_from_events(env, &beforeEvent, &afterEvent);  
}

jobjectArray transferFromHostToDeviceAsync(JNIEnv *env,
                                           jclass clazz,
                                           jlong device_ptr,
                                           jlong length,
                                           jbyteArray array,
                                           jlong host_offset,
                                           jbyteArray stream_wrapper,
                                           int numBytesNativeType) {
    CUevent beforeEvent, afterEvent;
    StagingAreaList *staging_list = get_first_free_staging_area(length);
    env->GetByteArrayRegion(array, host_offset / numBytesNativeType, length / numBytesNativeType,
                            static_cast<jbyte *>(staging_list->staging_area));

    CUstream stream;
    stream_from_array(env, &stream, stream_wrapper);
    record_events_create(&beforeEvent, &afterEvent);
    record_event_begin(&beforeEvent, &stream);

    CUresult result = cuMemcpyHtoDAsync(device_ptr, staging_list->staging_area, (size_t) length, stream);
    LOG_PTX_JNI("cuMemcpyHtoDAsync", result);

    record_event_end(&afterEvent, &stream);

    result = cuStreamAddCallback(stream, set_to_unused, staging_list, 0);
    LOG_PTX_JNI("cuStreamAddCallback", result);

    return wrapper_from_events(env, &beforeEvent, &afterEvent);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoD
 * Signature: (JJ[BJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoD__JJ_3BJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jbyteArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceBlocking(env, klass, device_ptr, length, array, host_offset, stream_wrapper, sizeof(jbyte));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoD
 * Signature: (JJ[SJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoD__JJ_3SJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jshortArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceBlocking(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jshort));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoD
 * Signature: (JJ[CJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoD__JJ_3CJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jcharArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceBlocking(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jchar));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoD
 * Signature: (JJ[IJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoD__JJ_3IJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jintArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceBlocking(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jint));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoD
 * Signature: (JJ[JJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoD__JJ_3JJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jlongArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceBlocking(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jlong));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoD
 * Signature: (JJ[FJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoD__JJ_3FJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jfloatArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceBlocking(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jfloat));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoD
 * Signature: (JJJ[DJ[I)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoD__JJ_3DJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jdoubleArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceBlocking(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jdouble));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoDAsync
 * Signature: (JJ[BJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoDAsync__JJ_3BJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jbyteArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceAsync(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jbyte));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoDAsync
 * Signature: (JJ[SJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoDAsync__JJ_3SJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jshortArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceAsync(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jshort));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoDAsync
 * Signature: (JJ[CJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoDAsync__JJ_3CJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jcharArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceAsync(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jchar));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoDAsync
 * Signature: (JJ[IJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoDAsync__JJ_3IJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jintArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceAsync(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jint));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoDAsync
 * Signature: (JJ[JJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoDAsync__JJ_3JJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jlongArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceAsync(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jlong));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoDAsync
 * Signature: (JJ[FJ[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoDAsync__JJ_3FJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jfloatArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceAsync(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jfloat));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    writeArrayHtoDAsync
 * Signature: (JJ[DJ[I)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_writeArrayHtoDAsync__JJ_3DJ_3B
        (JNIEnv *env, jclass klass, jlong device_ptr, jlong length, jdoubleArray array, jlong host_offset, jbyteArray stream_wrapper) {
    return transferFromHostToDeviceAsync(env, klass, device_ptr, length, reinterpret_cast<jbyteArray>(array), host_offset, stream_wrapper, sizeof(jdouble));
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    cuLaunchKernel
 * Signature: ([BLjava/lang/String;IIIIIIJ[B[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_cuLaunchKernel(
        JNIEnv *env,
        jclass clazz,
        jbyteArray module,
        jstring function_name,
        jint gridDimX, jint gridDimY, jint gridDimZ,
        jint blockDimX, jint blockDimY, jint blockDimZ,
        jlong sharedMemBytes,
        jbyteArray stream_wrapper,
        jbyteArray args) {

    CUevent beforeEvent, afterEvent;
    CUmodule native_module;
    array_to_module(env, &native_module, module);

    const char *native_function_name = env->GetStringUTFChars(function_name, 0);
    CUfunction kernel;
    CUresult result = cuModuleGetFunction(&kernel, native_module, native_function_name);
    LOG_PTX_JNI("cuModuleGetFunction", result);

    size_t arg_buffer_size = env->GetArrayLength(args);
    char arg_buffer[arg_buffer_size];
    env->GetByteArrayRegion(args, 0, arg_buffer_size, reinterpret_cast<jbyte *>(arg_buffer));

    void *arg_config[] = {
        CU_LAUNCH_PARAM_BUFFER_POINTER, arg_buffer,
        CU_LAUNCH_PARAM_BUFFER_SIZE,    &arg_buffer_size,
        CU_LAUNCH_PARAM_END
    };

    CUstream stream;
    stream_from_array(env, &stream, stream_wrapper);

    record_events_create(&beforeEvent, &afterEvent);
    record_event_begin(&beforeEvent, &stream);
    result = cuLaunchKernel(
            kernel,
            (unsigned int) gridDimX,  (unsigned int) gridDimY,  (unsigned int) gridDimZ,
            (unsigned int) blockDimX, (unsigned int) blockDimY, (unsigned int) blockDimZ,
            (unsigned int) sharedMemBytes, stream,
            NULL,
            arg_config);
    LOG_PTX_JNI("cuLaunchKernel", result);
    record_event_end(&afterEvent, &stream);

    env->ReleaseStringUTFChars(function_name, native_function_name);
    return wrapper_from_events(env, &beforeEvent, &afterEvent);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    cuCreateStream
 * Signature: ()[B
 */
JNIEXPORT jbyteArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_cuCreateStream
  (JNIEnv *env, jclass clazz) {
    int lowestPriority, highestPriority;
    CUresult result = cuCtxGetStreamPriorityRange (&lowestPriority, &highestPriority);
    LOG_PTX_JNI("cuCtxGetStreamPriorityRange", result);

    CUstream stream;
    result = cuStreamCreateWithPriority(&stream, CU_STREAM_NON_BLOCKING, highestPriority);
    LOG_PTX_JNI("cuStreamCreateWithPriority", result);
    return array_from_stream(env, &stream);
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    cuDestroyStream
 * Signature: ([B)J
 */
JNIEXPORT jlong JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_cuDestroyStream
  (JNIEnv *env, jclass clazz, jbyteArray stream_wrapper) {
    CUstream stream;
    stream_from_array(env, &stream, stream_wrapper);

    CUresult result = cuStreamDestroy(stream);
    LOG_PTX_JNI("cuStreamDestroy", result);

    free_queue();
    CUresult stagingAreaResult = free_staging_area_list();
    return (jlong) result & stagingAreaResult;
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    cuStreamSynchronize
 * Signature: ([B)J
 */
JNIEXPORT jlong JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_cuStreamSynchronize
  (JNIEnv *env, jclass clazz, jbyteArray stream_wrapper) {
    CUstream stream;
    stream_from_array(env, &stream, stream_wrapper);
    CUresult result = cuStreamSynchronize(stream);
    LOG_PTX_JNI("cuStreamSynchronize", result);
    return (jlong) result;
}

/*
 * Class:     uk_ac_manchester_tornado_drivers_ptx_PTXStream
 * Method:    cuEventCreateAndRecord
 * Signature: (Z[B)[[B
 */
JNIEXPORT jobjectArray JNICALL Java_uk_ac_manchester_tornado_drivers_ptx_PTXStream_cuEventCreateAndRecord
  (JNIEnv *env, jclass clazz, jboolean is_timing, jbyteArray stream_wrapper) {
    CUevent beforeEvent;
    CUevent afterEvent;
    CUstream stream;

    stream_from_array(env, &stream, stream_wrapper);
    record_events_create(&beforeEvent, &afterEvent);
    record_event_begin(&beforeEvent, &stream);
    record_event_end(&afterEvent, &stream);

    return wrapper_from_events(env, &beforeEvent, &afterEvent);
}