//! Replacement for `tauri::async_runtime`: one process-wide multi-thread
//! runtime owned by the core. Everything goes through `RUNTIME.handle()` so it
//! is safe to call from threads (e.g. the Swift main thread) that are not
//! inside a tokio context.

use std::future::Future;
use std::sync::OnceLock;
use tokio::runtime::Runtime;

pub use tokio::task::JoinHandle;

static RUNTIME: OnceLock<Runtime> = OnceLock::new();

pub fn runtime() -> &'static Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(3)
            .thread_name("dd-core")
            // Blocking pool: tokio's default cap is 512; spawn_blocking here is
            // file/scan work, never long waits, so 8 is plenty and idle
            // threads exit after 2 s.
            .max_blocking_threads(8)
            .thread_keep_alive(std::time::Duration::from_secs(2))
            // Utility QoS lets the kernel coalesce this runtime's timer wakeups
            // with other background work and keeps it on efficiency cores. (The
            // `droiddock-net` runtime stays at default QoS: it carries the
            // mirror / audio frames and the WS link itself.)
            .on_thread_start(set_utility_qos)
            .enable_all()
            .build()
            .expect("failed to build the core runtime")
    })
}

thread_local! {
    /// True on the command runtime's worker and blocking threads.
    static IN_COMMAND_RT: std::cell::Cell<bool> = const { std::cell::Cell::new(false) };
}

static COMMAND_RUNTIME: OnceLock<Runtime> = OnceLock::new();

/// Runtime for `dd_core_invoke` dispatch, at the default QoS. Commands include
/// taps/swipes/keys, call actions, media control and file transfers, none of
/// which may be starved on an efficiency core under load. Only the background
/// tickers (spawned from [`runtime`]'s own context) stay at UTILITY.
pub fn command_runtime() -> &'static Runtime {
    COMMAND_RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("dd-cmd")
            .on_thread_start(|| IN_COMMAND_RT.with(|c| c.set(true)))
            .max_blocking_threads(8)
            .thread_keep_alive(std::time::Duration::from_secs(2))
            .enable_all()
            .build()
            .expect("failed to build the command runtime")
    })
}

/// Run a command's dispatch future at default QoS.
pub fn spawn_command<F>(future: F) -> JoinHandle<F::Output>
where
    F: Future + Send + 'static,
    F::Output: Send + 'static,
{
    command_runtime().handle().spawn(future)
}

/// Work started by a command (on a command-runtime thread) stays on that
/// runtime and its QoS; everything else goes to the utility runtime.
fn current_or_core() -> tokio::runtime::Handle {
    if IN_COMMAND_RT.with(|c| c.get()) {
        if let Ok(h) = tokio::runtime::Handle::try_current() {
            return h;
        }
    }
    runtime().handle().clone()
}

pub fn spawn<F>(future: F) -> JoinHandle<F::Output>
where
    F: Future + Send + 'static,
    F::Output: Send + 'static,
{
    current_or_core().spawn(future)
}

pub fn spawn_blocking<F, R>(func: F) -> JoinHandle<R>
where
    F: FnOnce() -> R + Send + 'static,
    R: Send + 'static,
{
    current_or_core().spawn_blocking(func)
}

pub fn block_on<F: Future>(future: F) -> F::Output {
    runtime().block_on(future)
}

/// Mark the calling thread `QOS_CLASS_UTILITY`.
#[cfg(target_os = "macos")]
pub fn set_utility_qos() {
    // SAFETY: plain libc call affecting only the calling thread.
    unsafe {
        libc::pthread_set_qos_class_self_np(libc::qos_class_t::QOS_CLASS_UTILITY, 0);
    }
}

#[cfg(not(target_os = "macos"))]
pub fn set_utility_qos() {}
