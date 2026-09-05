import { useCallback, useEffect, useRef, useState, memo } from "react";
import { open as openDialog } from "@tauri-apps/plugin-dialog";
import { getCurrentWebview } from "@tauri-apps/api/webview";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { fmtBytes } from "../../lib/ui";
import {
  fsList,
  fsPull,
  fsPush,
  fsRename,
  fsDelete,
  fsCancel,
  fsOpenInPlace,
  fsPendingSyncs,
  on,
  onEditSync,
  type FsEntry,
  type Progress,
} from "../../lib/bridge";

const ROOT = "/sdcard";

function FilesView({
  linked,
  onToast,
}: {
  linked: boolean;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  const [path, setPath] = useState(ROOT);
  const [entries, setEntries] = useState<FsEntry[]>([]);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [hover, setHover] = useState(false);
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [prog, setProg] = useState<Progress | null>(null);
  // Phase 17: phone path → true while an edit-in-place save hasn't synced yet.
  const [pendingSync, setPendingSync] = useState<Record<string, boolean>>({});
  const pathRef = useRef(path);
  pathRef.current = path;

  const load = useCallback(
    async (p: string) => {
      if (!linked) return;
      setLoading(true);
      setErr(null);
      try {
        setEntries(await fsList(p));
      } catch (e) {
        setEntries([]);
        setErr(String(e));
      } finally {
        setLoading(false);
      }
    },
    [linked]
  );

  useEffect(() => {
    if (linked) load(path);
  }, [linked, path, load]);

  // Live transfer progress (push/pull/phone), same event the Electron app fed
  // its progress bar. Clears on done.
  useEffect(() => {
    return on<Progress>("transfer-progress", (p) => {
      // Phone-initiated pushes (Android share → Mac) are owned by App so they
      // surface on any view; here we only track push/pull the browser started.
      if (p.dir === "phone") return;
      if (p.done) {
        setProg(null);
        if (p.error) onToast("bad", `${p.name}: ${p.error}`);
        // refresh the listing if we're viewing the folder a push landed in
        if (p.dir === "push" && pathRef.current.startsWith("/sdcard/Download")) load(pathRef.current);
      } else {
        setProg(p);
      }
    });
  }, [onToast, load]);

  // Phase 17: hydrate the pending-sync badge from the Rust-side manifest on
  // mount — this view fully unmounts when the user switches tabs (see
  // `App.tsx`'s `renderView()`), so a writeback that failed while elsewhere
  // would otherwise show no badge until another live `edit-sync` event fires.
  useEffect(() => {
    if (!linked) return;
    let cancelled = false;
    fsPendingSyncs()
      .then((paths) => {
        if (cancelled || paths.length === 0) return;
        setPendingSync((p) => ({ ...Object.fromEntries(paths.map((path) => [path, true])), ...p }));
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [linked]);

  // Phase 17: track which phone paths have an unsynced edit-in-place save so
  // Row can render a pending badge until the writeback settles.
  useEffect(() => {
    return onEditSync((e) => {
      setPendingSync((p) => {
        if (e.status === "synced") {
          if (!(e.phonePath in p)) return p;
          return Object.fromEntries(Object.entries(p).filter(([k]) => k !== e.phonePath));
        }
        return { ...p, [e.phonePath]: true };
      });
    });
  }, []);

  // OS-file drag-and-drop → upload. Tauri's webview drag-drop event carries the
  // real absolute paths (the HTML5 drop event does not, in a webview).
  useEffect(() => {
    if (!linked) return;
    const un = getCurrentWebview().onDragDropEvent((ev) => {
      if (ev.payload.type === "over") setHover(true);
      else if (ev.payload.type === "leave") setHover(false);
      else if (ev.payload.type === "drop") {
        setHover(false);
        uploadPaths(ev.payload.paths);
      }
    });
    return () => {
      un.then((f) => f());
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [linked]);

  const uploadPaths = async (paths: string[]) => {
    // Upload into the folder currently being browsed (the phone's beginPush
    // honours `dest`), not a hardcoded Downloads dir.
    const dest = pathRef.current || ROOT;
    for (const p of paths) {
      try {
        await fsPush(p, dest);
        onToast("ok", `Sent ${p.split("/").pop()} → ${dest}`);
      } catch (e) {
        onToast("bad", String(e));
      }
    }
    load(dest); // refresh so the new file shows in the current folder
  };

  const pickAndUpload = async () => {
    const sel = await openDialog({ multiple: true });
    if (!sel) return;
    await uploadPaths(Array.isArray(sel) ? sel : [sel]);
  };

  const remotePath = (name: string) => (path === "/" ? `/${name}` : `${path}/${name}`);

  /* The five row handlers below are `useCallback`'d, and each takes the entry
     it acts on rather than closing over it.

     That is what makes `Row`'s `memo` real. Built inline per row they were a
     fresh function identity on every render of this view — and this view
     re-renders on every `transfer-progress` event, so a 500-entry folder was
     re-rendering 500 rows for each one. Keyed off `path` (not the entry) they
     change only when the user navigates. */
  const openDir = useCallback((e: FsEntry) => {
    if (!e.dir) return;
    setPath((p) => (p === "/" ? `/${e.name}` : `${p}/${e.name}`));
  }, []);

  const download = useCallback(
    async (e: FsEntry) => {
      setBusy((b) => ({ ...b, [e.name]: true }));
      try {
        await fsPull(remotePath(e.name), e.name);
        onToast("ok", `Saved — ${e.name}`);
      } catch (err) {
        onToast("bad", String(err));
      } finally {
        setBusy((b) => ({ ...b, [e.name]: false }));
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [path, onToast]
  );

  const openInPlace = useCallback(
    async (e: FsEntry) => {
      try {
        await fsOpenInPlace(remotePath(e.name));
        onToast("ok", `Opening ${e.name} — edits will sync back automatically`);
      } catch (err) {
        onToast("bad", String(err));
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [path, onToast]
  );

  const rename = useCallback(
    async (e: FsEntry, newName: string) => {
      try {
        await fsRename(remotePath(e.name), newName);
        onToast("ok", `Renamed to ${newName}`);
        load(path);
      } catch (err) {
        onToast("bad", String(err));
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [path, onToast, load]
  );

  const remove = useCallback(
    async (e: FsEntry) => {
      const what = e.dir ? "folder" : "file";
      if (!window.confirm(`Delete this ${what} from your phone?\n\n${e.name}`)) return;
      try {
        await fsDelete(remotePath(e.name));
        onToast("ok", `Deleted ${e.name}`);
        load(path);
      } catch (err) {
        onToast("bad", String(err));
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [path, onToast, load]
  );

  if (!linked) {
    return (
      <EmptyState
        icon="folder"
        title="No phone linked"
        body="Link your phone from the Dashboard to browse and transfer its files."
      />
    );
  }

  const crumbs = path.split("/").filter(Boolean);
  const q = query.trim().toLowerCase();
  const shown = q ? entries.filter((e) => e.name.toLowerCase().includes(q)) : entries;

  return (
    <div className="flex h-full flex-col">
      {/* header */}
      <div className="flex shrink-0 items-center justify-between gap-3 px-6 pt-5 pb-4">
        <h1 className="font-display text-[17px] font-semibold text-fg">Files</h1>
        <div className="flex items-center gap-2">
          <button onClick={() => load(path)} title="Refresh" className="btn-icon">
            <Icon name="reload" size={14} className={loading ? "spinner" : ""} />
          </button>
          <button onClick={pickAndUpload} className="btn btn-primary">
            <Icon name="upload" size={14} />
            Send to phone
          </button>
        </div>
      </div>

      {/* path + filter */}
      <div className="flex shrink-0 items-center gap-2 px-6 pb-3">
        <button
          onClick={() => setPath((p) => p.split("/").slice(0, -1).join("/") || "/")}
          disabled={crumbs.length <= 1}
          title="Up one level"
          className="btn-icon shrink-0 disabled:opacity-30"
        >
          <Icon name="chevronUp" size={14} />
        </button>
        <div className="data flex min-w-0 flex-1 items-center gap-0.5 overflow-hidden">
          {crumbs.map((c, i) => (
            <span key={i} className="flex min-w-0 items-center gap-0.5">
              {i > 0 && <Icon name="chevronRight" size={11} className="shrink-0 text-faint" />}
              <button
                onClick={() => setPath("/" + crumbs.slice(0, i + 1).join("/"))}
                className={`truncate rounded-md px-1 py-0.5 transition-colors ${
                  i === crumbs.length - 1 ? "font-medium text-fg" : "text-dim hover:text-fg"
                }`}
              >
                {i === 0 && c === "sdcard" ? "Internal storage" : c}
              </button>
            </span>
          ))}
        </div>
        <div className="relative shrink-0">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Filter this folder…"
            className="field w-56"
          />
          {query && (
            <button
              onClick={() => setQuery("")}
              title="Clear filter"
              className="absolute right-1.5 top-1/2 flex h-6 w-6 -translate-y-1/2 items-center justify-center rounded-md text-faint transition-colors hover:text-fg"
            >
              <Icon name="x" size={12} />
            </button>
          )}
        </div>
      </div>

      {/* live transfer progress */}
      {prog && (
        <div className="shrink-0 px-6 pb-3">
          <div className="card flex items-center gap-3 px-4 py-2.5">
            <span className="led h-1.5 w-1.5 shrink-0 rounded-full bg-(--color-link)" />
            <span className="label shrink-0">{prog.dir === "push" ? "Sending" : "Receiving"}</span>
            <span className="min-w-0 flex-1 truncate text-[12.5px] text-fg">{prog.name}</span>
            <span className="data shrink-0 text-faint">
              {fmtBytes(prog.sent)} / {fmtBytes(prog.total)}
            </span>
            <div className="h-0.75 w-28 shrink-0 overflow-hidden rounded-full bg-line">
              <div
                className="h-full rounded-full bg-(--color-accent) transition-all"
                style={{ width: `${prog.total ? Math.round((prog.sent / prog.total) * 100) : 0}%` }}
              />
            </div>
            <button onClick={() => fsCancel(prog.transferId)} title="Cancel transfer" className="btn-icon shrink-0">
              <Icon name="x" size={13} />
            </button>
          </div>
        </div>
      )}

      {/* listing */}
      <div
        className={`relative min-h-0 flex-1 overflow-y-auto px-6 pb-6 transition-shadow ${
          hover ? "shadow-[inset_0_0_0_2px_var(--color-accent)]" : ""
        }`}
      >
        {hover && (
          <div className="glass-heavy pointer-events-none sticky top-0 z-10 mb-3 rounded-xl border border-[color-mix(in_srgb,var(--color-accent)_40%,transparent)] px-4 py-2 text-center text-[12.5px] font-medium text-(--color-accent)">
            Drop to send to <span className="data">{path}</span>
          </div>
        )}
        {err ? (
          <div className="card rise-fast px-6 py-10 text-center">
            <p className="text-[12.5px] text-bad">{err}</p>
          </div>
        ) : entries.length === 0 && !loading ? (
          <div className="card rise-fast px-6 py-10 text-center">
            <p className="text-[12.5px] text-dim">This folder is empty.</p>
          </div>
        ) : shown.length === 0 ? (
          <div className="card rise-fast px-6 py-10 text-center">
            <p className="text-[12.5px] text-dim">No matches for "{query}".</p>
          </div>
        ) : (
          <ul className="card rise-fast divide-y divide-line overflow-hidden">
            {shown.map((e) => (
              <li key={e.name}>
                <Row
                  entry={e}
                  busy={!!busy[e.name]}
                  pendingSync={!e.dir && !!pendingSync[remotePath(e.name)]}
                  onOpen={openDir}
                  onEditOpen={openInPlace}
                  onDownload={download}
                  onRename={rename}
                  onDelete={remove}
                />
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

/// Memoised, and its callbacks take the entry rather than closing over it —
/// see the note on the handlers in `FilesView`. Without both halves, every
/// `transfer-progress` event re-rendered every row in the directory.
const Row = memo(function Row({
  entry,
  busy,
  pendingSync,
  onOpen,
  onEditOpen,
  onDownload,
  onRename,
  onDelete,
}: {
  entry: FsEntry;
  busy: boolean;
  pendingSync: boolean;
  onOpen: (entry: FsEntry) => void;
  onEditOpen: (entry: FsEntry) => void;
  onDownload: (entry: FsEntry) => void;
  onRename: (entry: FsEntry, newName: string) => void;
  onDelete: (entry: FsEntry) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(entry.name);
  const committed = useRef(false);

  const startEdit = (ev?: React.MouseEvent) => {
    ev?.stopPropagation();
    committed.current = false;
    setName(entry.name);
    setEditing(true);
  };
  const commit = (ev?: React.SyntheticEvent) => {
    ev?.stopPropagation();
    if (committed.current) return;
    committed.current = true;
    const next = name.trim();
    setEditing(false);
    if (next && next !== entry.name) onRename(entry, next);
    else setName(entry.name);
  };
  const cancel = (ev?: React.SyntheticEvent) => {
    ev?.stopPropagation();
    committed.current = true;
    setName(entry.name);
    setEditing(false);
  };

  return (
    <div
      onDoubleClick={
        editing ? undefined : entry.dir ? () => onOpen(entry) : () => onEditOpen(entry)
      }
      onClick={entry.dir && !editing ? () => onOpen(entry) : undefined}
      className={`group flex items-center gap-3 px-3.5 py-2 transition-colors hover:bg-panel2 ${
        entry.dir && !editing ? "cursor-pointer" : ""
      }`}
    >
      <Icon
        name={entry.dir ? "folder" : "file"}
        size={15}
        className={`shrink-0 ${entry.dir ? "text-(--color-accent)/80" : "text-dim"}`}
      />
      {editing ? (
        <input
          autoFocus
          value={name}
          onClick={(ev) => ev.stopPropagation()}
          onChange={(ev) => setName(ev.target.value)}
          onKeyDown={(ev) => {
            if (ev.key === "Enter") commit(ev);
            if (ev.key === "Escape") cancel(ev);
          }}
          onBlur={commit}
          className="field data min-w-0 flex-1"
        />
      ) : (
        <span className="min-w-0 flex-1 truncate text-[13px] text-fg">{entry.name}</span>
      )}
      <div className="flex shrink-0 items-center gap-0.5">
        {pendingSync && !editing && (
          <span
            title="Edited on Mac — waiting to sync back to phone"
            className="flex h-7 w-7 shrink-0 items-center justify-center text-(--color-accent)"
          >
            <Icon name="reload" size={13} className="spinner" />
          </span>
        )}
        {editing ? (
          <button
            onClick={commit}
            title="Rename"
            className="flex h-7 w-7 items-center justify-center rounded-lg text-ok transition-colors hover:bg-panel3"
          >
            <Icon name="check" size={14} />
          </button>
        ) : (
          <>
            {!entry.dir && (
              <button
                onClick={(ev) => {
                  ev.stopPropagation();
                  onDownload(entry);
                }}
                disabled={busy}
                title="Save to Mac Downloads"
                className="btn-icon opacity-0 transition-opacity group-hover:opacity-100 disabled:opacity-100"
              >
                <Icon name="download" size={14} className={busy ? "spinner text-(--color-accent)" : ""} />
              </button>
            )}
            <button
              onClick={startEdit}
              title="Rename"
              className="btn-icon opacity-0 transition-opacity group-hover:opacity-100"
            >
              <Icon name="edit" size={14} />
            </button>
            <button
              onClick={(ev) => {
                ev.stopPropagation();
                onDelete(entry);
              }}
              title="Delete from phone"
              className="flex h-7 w-7 items-center justify-center rounded-lg text-dim opacity-0 transition-all hover:bg-[color-mix(in_srgb,var(--color-bad)_12%,transparent)] hover:text-bad group-hover:opacity-100"
            >
              <Icon name="trash" size={14} />
            </button>
          </>
        )}
      </div>
    </div>
  );
});

/* Memoised. This was originally defence against the phone's 1 Hz now-playing
   push re-rendering every view; that push no longer reaches `App` at all (it
   lives in `lib/mediaStore`, read only by the two components that show it). The
   memo stays because `App` still re-renders for its own reasons — an arriving
   notification, a toast appearing and expiring, a transfer's progress — and
   none of those change this view's props. All props here are primitives or
   stable useCallback refs, so the comparison is sound. */
export default memo(FilesView);
