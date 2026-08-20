// SPDX-License-Identifier: LGPL-2.1-or-later
#include "ffmpegkmp_bridge.h"

#include <stdatomic.h>
#include <setjmp.h>
#include <stdio.h>
#include <stdlib.h>
#if defined(__EMSCRIPTEN__)
#include <emscripten/threading.h>
#endif
#if !defined(_WIN32)
#include <unistd.h>
#endif

#if FFMPEGKMP_EMBEDDED_FFTOOLS
#include "libavutil/log.h"
#endif

#if defined(__GNUC__) || defined(__clang__)
#define FFMPEGKMP_WEAK __attribute__((weak))
#else
#define FFMPEGKMP_WEAK
#endif

/* cmdutils expects these process globals; the embedded entry wrappers select behavior. */
const char program_name[] = "ffmpegkmp";
const int program_birth_year = 2000;

/* These symbols are supplied by the locally compiled fftools entry wrappers. */
#if FFMPEGKMP_EMBEDDED_FFTOOLS
extern int ffmpegkmp_ffmpeg_entry(int argc, char **argv);
extern int ffmpegkmp_ffprobe_entry(int argc, char **argv);
extern void ffmpegkmp_ffmpeg_cancel(void);
#else
extern int ffmpegkmp_ffmpeg_entry(int argc, char **argv) FFMPEGKMP_WEAK;
extern int ffmpegkmp_ffprobe_entry(int argc, char **argv) FFMPEGKMP_WEAK;
#endif

struct ffmpegkmp_context {
    ffmpegkmp_event_callback callback;
    ffmpegkmp_io_callback io_callback;
    void *opaque;
    atomic_bool cancelled;
};

static _Atomic(ffmpegkmp_context *) active_context = NULL;
/* Unsynchronized on purpose: hosts set it once, before the first execute. */
static char ffmpegkmp_temp_directory[448];
static atomic_int active_kind;
static _Thread_local jmp_buf exit_target;
static _Thread_local int exit_target_active;
static _Thread_local int requested_exit_status;

/* Implemented by the custom libavformat ffmpegkmp: protocol. */
extern void av_ffmpegkmp_protocol_set_callback(
        ffmpegkmp_io_callback callback,
        void *opaque);

#if defined(__EMSCRIPTEN__)
typedef struct ffmpegkmp_emscripten_event {
    ffmpegkmp_context *context;
    ffmpegkmp_event_kind kind;
    int level;
    const uint8_t *data;
    size_t size;
} ffmpegkmp_emscripten_event;

static void ffmpegkmp_deliver_emscripten_event(void *opaque) {
    const ffmpegkmp_emscripten_event *event = opaque;
    if (event->context && event->context->callback)
        event->context->callback(
                event->context->opaque,
                event->kind,
                event->level,
                event->data,
                event->size);
}
#endif

#if FFMPEGKMP_EMBEDDED_FFTOOLS
static void ffmpegkmp_log_callback(void *avcl, int level, const char *format, va_list arguments) {
    char line[4096];
    int print_prefix = 1;
    if (level > av_log_get_level())
        return;
    int size = av_log_format_line2(avcl, level, format, arguments, line, sizeof(line), &print_prefix);
    if (size > 0) {
        size_t emitted = (size_t) size;
        if (emitted >= sizeof(line))
            emitted = sizeof(line) - 1;
        ffmpegkmp_emit(FFMPEGKMP_EVENT_LOG, level, (const uint8_t *) line, emitted);
    }
}
#endif

ffmpegkmp_context *ffmpegkmp_context_create(
        ffmpegkmp_event_callback callback,
        void *opaque) {
    ffmpegkmp_context *context = calloc(1, sizeof(*context));
    if (!context)
        return NULL;
    context->callback = callback;
    context->opaque = opaque;
    atomic_init(&context->cancelled, 0);
    return context;
}

void ffmpegkmp_context_destroy(ffmpegkmp_context *context) {
    if (!context)
        return;
    ffmpegkmp_cancel(context);
    free(context);
}

void ffmpegkmp_context_set_io_callback(
        ffmpegkmp_context *context,
        ffmpegkmp_io_callback callback) {
    if (context)
        context->io_callback = callback;
}

int ffmpegkmp_execute(
        ffmpegkmp_context *context,
        ffmpegkmp_command_kind kind,
        int argc,
        const char *const *argv) {
    ffmpegkmp_context *expected = NULL;
    int (*entry)(int, char **);
    int result;
#if FFMPEGKMP_EMBEDDED_FFTOOLS
    char probe_output[512] = { 0 };
    char **effective_argv = (char **) argv;
    int effective_argc = argc;
#endif

    if (!context || argc < 0 || (argc > 0 && !argv))
        return -22;
    if (!atomic_compare_exchange_strong(&active_context, &expected, context))
        return -16;

    atomic_store(&context->cancelled, 0);
    atomic_store(&active_kind, kind);
    av_ffmpegkmp_protocol_set_callback(context->io_callback, context->opaque);
#if FFMPEGKMP_EMBEDDED_FFTOOLS
    av_log_set_callback(ffmpegkmp_log_callback);
    /* fftools' -v/-loglevel mutates the process-global log level, so a run that
     * passed `-v error` (a typical ffprobe) would silence every later run in the
     * same process — including the stats lines callers parse. Reset per run. */
    av_log_set_level(AV_LOG_INFO);
#endif
    entry = kind == FFMPEGKMP_COMMAND_FFMPEG
            ? ffmpegkmp_ffmpeg_entry
            : ffmpegkmp_ffprobe_entry;
#if FFMPEGKMP_EMBEDDED_FFTOOLS
    if (kind == FFMPEGKMP_COMMAND_FFPROBE) {
#if defined(_WIN32)
        if (!tmpnam(probe_output))
            probe_output[0] = 0;
#elif defined(__EMSCRIPTEN__)
        /* Each command runs alone in its own worker-backed virtual filesystem. */
        snprintf(probe_output, sizeof(probe_output), "/ffmpegkmp-probe-output.json");
        remove(probe_output);
#else
        /* A relative template resolves against the process cwd, which in Android app
         * processes is the unwritable "/" — mkstemp fails and the -o redirect is
         * silently dropped, losing the probe JSON. Anchor the file in an explicitly
         * configured directory, or TMPDIR (set on Apple platforms), before falling
         * back to the cwd for CLI-style hosts. */
        int probe_fd;
        const char *temp_directory = ffmpegkmp_temp_directory[0]
                ? ffmpegkmp_temp_directory
                : getenv("TMPDIR");
        if (temp_directory && *temp_directory)
            snprintf(probe_output, sizeof(probe_output),
                    "%s/ffmpegkmp-probe-XXXXXX", temp_directory);
        else
            snprintf(probe_output, sizeof(probe_output), "ffmpegkmp-probe-XXXXXX");
        probe_fd = mkstemp(probe_output);
        if (probe_fd >= 0)
            close(probe_fd);
        else
            probe_output[0] = 0;
#endif
    }
    if (kind == FFMPEGKMP_COMMAND_FFPROBE && probe_output[0]) {
        effective_argv = calloc((size_t) argc + 2, sizeof(*effective_argv));
        if (!effective_argv) {
            av_log_set_callback(av_log_default_callback);
            av_ffmpegkmp_protocol_set_callback(NULL, NULL);
            atomic_store(&active_context, NULL);
            return -12;
        }
        for (int i = 0; i < argc; i++)
            effective_argv[i] = (char *) argv[i];
        effective_argv[argc] = (char *) "-o";
        effective_argv[argc + 1] = probe_output;
        effective_argc += 2;
    }
#endif
    if (!entry) {
        result = -38;
    } else {
        exit_target_active = 1;
        if (setjmp(exit_target) == 0)
            result = entry(
#if FFMPEGKMP_EMBEDDED_FFTOOLS
                    effective_argc, effective_argv
#else
                    argc, (char **) argv
#endif
            );
        else
            result = requested_exit_status;
        exit_target_active = 0;
    }
#if FFMPEGKMP_EMBEDDED_FFTOOLS
    av_log_set_callback(av_log_default_callback);
    if (probe_output[0]) {
        FILE *output = fopen(probe_output, "rb");
        if (output) {
            uint8_t buffer[4096];
            size_t count;
            while ((count = fread(buffer, 1, sizeof(buffer), output)) > 0)
                ffmpegkmp_emit(FFMPEGKMP_EVENT_STDOUT, 0, buffer, count);
            fclose(output);
        }
        remove(probe_output);
        free(effective_argv);
    }
#endif
    av_ffmpegkmp_protocol_set_callback(NULL, NULL);
    atomic_store(&active_context, NULL);
    return result;
}

void ffmpegkmp_set_temp_directory(const char *path) {
    if (!path || !*path) {
        ffmpegkmp_temp_directory[0] = 0;
        return;
    }
    snprintf(ffmpegkmp_temp_directory, sizeof(ffmpegkmp_temp_directory), "%s", path);
}

void ffmpegkmp_exit(int status) {
    requested_exit_status = status;
    if (exit_target_active)
        longjmp(exit_target, 1);
}

void ffmpegkmp_cancel(ffmpegkmp_context *context) {
    if (context) {
        atomic_store(&context->cancelled, 1);
#if FFMPEGKMP_EMBEDDED_FFTOOLS
        if (atomic_load(&active_context) == context &&
                atomic_load(&active_kind) == FFMPEGKMP_COMMAND_FFMPEG)
            ffmpegkmp_ffmpeg_cancel();
#endif
    }
}

int ffmpegkmp_cancel_requested(void) {
    ffmpegkmp_context *context = atomic_load(&active_context);
    return context && atomic_load(&context->cancelled);
}

void ffmpegkmp_emit(
        ffmpegkmp_event_kind kind,
        int level,
        const uint8_t *data,
        size_t size) {
    ffmpegkmp_context *context = atomic_load(&active_context);
    if (!context || !context->callback || !data || !size)
        return;
#if defined(__EMSCRIPTEN__)
    if (!emscripten_is_main_runtime_thread()) {
        ffmpegkmp_emscripten_event event = {
                .context = context,
                .kind = kind,
                .level = level,
                .data = data,
                .size = size,
        };
        /* addFunction() updates only the main runtime worker's Wasm table.
         * Proxy there before invoking the JavaScript-backed callback. */
        emscripten_sync_run_in_main_runtime_thread(
                EM_FUNC_SIG_VI,
                ffmpegkmp_deliver_emscripten_event,
                &event);
        return;
    }
#endif
    if (context && context->callback && data && size)
        context->callback(context->opaque, kind, level, data, (uint64_t)size);
}
