/* DroidDock core C ABI — FROZEN (ABI v1). Single source of truth for the
 * Rust <-> Swift boundary. Changing anything here means bumping
 * DD_ABI_VERSION and updating core/src/ffi.rs and mac/Sources/DroidDock/Core
 * in the same change. */
#ifndef DROIDDOCK_CORE_H
#define DROIDDOCK_CORE_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif

#define DD_ABI_VERSION 1u

/* Binary stream ids for dd_frame_cb. */
#define DD_STREAM_MIRROR 1u   /* [flags][Annex-B access unit], flags bit0 = keyframe */
#define DD_STREAM_AUDIO  2u   /* [flags][s16le interleaved PCM], flags bit0 = reset after gap */

/* dd_core_start return codes */
#define DD_OK                0
#define DD_ERR_ALREADY       1
#define DD_ERR_BAD_CONFIG    2
#define DD_ERR_INIT          3

/* All pointers passed INTO a callback are borrowed for the duration of the
   call only; copy before returning. Callbacks may be invoked concurrently from
   any core thread and must never block. No memory ownership ever crosses the
   boundary in either direction. */
typedef void (*dd_event_cb)(void *ctx, const char *name, const char *payload_json);
typedef void (*dd_frame_cb)(void *ctx, uint32_t stream, const uint8_t *data, size_t len);
/* ok=1: json is the command's return value (JSON; "null" for unit).
   ok=0: json is a JSON string with the error message. */
typedef void (*dd_result_cb)(void *ctx, uint64_t req_id, int32_t ok, const char *json);

typedef struct dd_callbacks {
    void        *ctx;
    dd_event_cb  on_event;
    dd_frame_cb  on_frame;
    dd_result_cb on_result;
} dd_callbacks;

uint32_t dd_abi_version(void);

/* config_json: {"dataDir":"/Users/x/Library/Application Support/com.droiddock.mac",
                 "identifier":"com.droiddock.mac","version":"2.3.0","dev":false}
   Builds the runtime, loads config, starts ws server/mDNS/discovery/adb init.
   Call once, from any thread. Returns DD_OK or an error code. */
int32_t dd_core_start(const char *config_json, dd_callbacks callbacks);

/* Fire-and-forget; exactly one on_result(req_id) follows. Strings are copied
   before return. args_json may be NULL (treated as "{}"). */
void dd_core_invoke(const char *cmd, const char *args_json, uint64_t req_id);

/* Same, with a raw body (copied before return). Used by mirror_save_capture {ext}. */
void dd_core_invoke_bytes(const char *cmd, const char *args_json,
                          const uint8_t *bytes, size_t len, uint64_t req_id);

/* Blocking, bounded (~5 s): runs shutdown::on_quit (webdav unmount, quickshare,
   mDNS goodbye, scrcpy forward removal, adb kill-server). Call from
   applicationWillTerminate. Idempotent. */
void dd_core_shutdown(void);

#ifdef __cplusplus
}
#endif
#endif
