// SPDX-License-Identifier: LGPL-2.1-or-later
/* The process's thread count and physical footprint, for the benchmarks. */
#ifndef FFMPEGKMP_BENCH_USAGE_H
#define FFMPEGKMP_BENCH_USAGE_H

#if defined(__APPLE__)
#include <mach/mach.h>
#endif

/* The process's thread count and physical footprint in MB now; 0 where the benchmark cannot tell. */
static void process_usage(int *threads, int *footprint_mb) {
#if defined(__APPLE__)
    thread_act_array_t list;
    mach_msg_type_number_t count = 0;
    task_vm_info_data_t vm;
    mach_msg_type_number_t vm_count = TASK_VM_INFO_COUNT;
#endif
    *threads = 0;
    *footprint_mb = 0;
#if defined(__APPLE__)
    if (task_threads(mach_task_self(), &list, &count) == KERN_SUCCESS) {
        for (mach_msg_type_number_t index = 0; index < count; index++)
            mach_port_deallocate(mach_task_self(), list[index]);
        vm_deallocate(mach_task_self(), (vm_address_t)list, count * sizeof(*list));
        *threads = (int)count;
    }
    if (task_info(mach_task_self(), TASK_VM_INFO, (task_info_t)&vm, &vm_count) == KERN_SUCCESS)
        *footprint_mb = (int)(vm.phys_footprint >> 20);
#endif
}

#endif
