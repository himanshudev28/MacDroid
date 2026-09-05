import { useCallback, useEffect, useRef, useState, memo } from "react";
import Icon from "../Icon";
import EmptyState from "../EmptyState";
import { fmtDuration } from "../../lib/ui";
import { photosList, photoThumb, photoOpen, fsPull, type MediaItem } from "../../lib/bridge";
import { t, useT } from "../../lib/i18n";

const CONCURRENCY = 3;
const PAGE = 500; // matches the Electron client's single 500-item page

/// Phase 6 — the photo/video grid. Thumbnails stream in lazily (Intersection
/// observer, 3-at-a-time) exactly like the Electron PhotosView; clicking a tile
/// pulls the full-res file to a temp dir and opens it in Preview/QuickTime.
function PhotosView({
  linked,
  onToast,
}: {
  linked: boolean;
  onToast: (kind: "ok" | "bad" | "info", text: string) => void;
}) {
  // Memoised: its props do not change when only the language does, so without
  // its own subscription it would keep rendering the old strings.
  useT();
  const [items, setItems] = useState<MediaItem[]>([]);
  const [thumbs, setThumbs] = useState<Record<string, string>>({}); // path -> dataURL | "err"
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const queueRef = useRef<MediaItem[]>([]);
  const activeRef = useRef(0);
  const requestedRef = useRef<Set<string>>(new Set());
  const aliveRef = useRef(true);

  const pump = useCallback(() => {
    while (activeRef.current < CONCURRENCY && queueRef.current.length) {
      const item = queueRef.current.shift()!;
      activeRef.current++;
      photoThumb(item.id, item.kind)
        .then((dataUrl) => {
          if (!aliveRef.current) return;
          setThumbs((t) => ({ ...t, [item.path]: dataUrl }));
        })
        .catch(() => {
          if (!aliveRef.current) return;
          setThumbs((t) => ({ ...t, [item.path]: "err" }));
        })
        .finally(() => {
          activeRef.current--;
          pump();
        });
    }
  }, []);

  const requestThumb = useCallback(
    (item: MediaItem) => {
      if (requestedRef.current.has(item.path)) return;
      requestedRef.current.add(item.path);
      queueRef.current.push(item);
      pump();
    },
    [pump]
  );

  const load = useCallback(async () => {
    if (!linked) return;
    setLoading(true);
    setErr(null);
    setThumbs({});
    queueRef.current = [];
    requestedRef.current = new Set();
    try {
      setItems(await photosList(0, PAGE));
    } catch (e) {
      setErr(String(e));
    } finally {
      setLoading(false);
    }
  }, [linked]);

  useEffect(() => {
    aliveRef.current = true;
    if (linked) load();
    return () => {
      aliveRef.current = false;
      queueRef.current = [];
    };
  }, [linked, load]);

  const download = async (item: MediaItem) => {
    try {
      await fsPull(item.path, item.name);
      onToast("ok", `Saved to Downloads — ${item.name}`);
    } catch (e) {
      onToast("bad", String(e));
    }
  };

  const openFull = async (item: MediaItem) => {
    onToast("info", `Opening ${item.name}…`);
    try {
      await photoOpen(item.path, item.name);
    } catch (e) {
      onToast("bad", String(e));
    }
  };

  if (!linked) {
    return (
      <EmptyState
        icon="image"
        title={t("No phone linked")}
        body={t("Link your phone from the Dashboard to browse its photos and videos.")}
      />
    );
  }

  if (err) {
    return (
      <EmptyState
        icon="image"
        title={t("Can't read photos")}
        body={err}
        action={{ label: t("Try again"), onClick: load }}
      />
    );
  }

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="flex shrink-0 items-center justify-between gap-3 px-6 pt-5 pb-4">
        <div className="flex items-baseline gap-2.5">
          <h1 className="font-display text-[17px] font-semibold text-fg">{t("Photos")}</h1>
          <span className="text-[12px] text-dim">
            {items.length} items
            {loading ? " · scanning…" : ""}
          </span>
        </div>
        <button onClick={load} title={t("Refresh")} className="btn-icon">
          <Icon name="reload" size={14} className={loading ? "spinner" : ""} />
        </button>
      </div>

      {items.length === 0 && !loading ? (
        <EmptyState
          icon="image"
          title={t("No photos yet")}
          body={t("Photos and videos you take on your phone will show up here.")}
        />
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto px-6 pb-6">
          <div className="rise-fast grid grid-cols-[repeat(auto-fill,minmax(120px,1fr))] gap-2">
            {items.map((it) => (
              <Tile
                key={it.path}
                it={it}
                thumb={thumbs[it.path]}
                onVisible={requestThumb}
                onOpen={() => openFull(it)}
                onDownload={() => download(it)}
              />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function Tile({
  it,
  thumb,
  onVisible,
  onOpen,
  onDownload,
}: {
  it: MediaItem;
  thumb?: string;
  onVisible: (it: MediaItem) => void;
  onOpen: () => void;
  onDownload: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const isVideo = it.kind === "video";

  useEffect(() => {
    if (thumb) return;
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          onVisible(it);
          io.disconnect();
        }
      },
      { rootMargin: "300px" }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [it, thumb, onVisible]);

  return (
    <div
      ref={ref}
      onClick={onOpen}
      title={isVideo ? t("Open video") : t("Open photo")}
      className="group relative aspect-square cursor-pointer overflow-hidden rounded-lg border border-line bg-panel2"
    >
      {thumb && thumb !== "err" ? (
        <img src={thumb} alt={it.name} className="h-full w-full object-cover" />
      ) : (
        <div className="flex h-full w-full items-center justify-center">
          {thumb === "err" ? (
            <Icon name={isVideo ? "film" : "image"} size={18} className="text-faint" />
          ) : (
            <div className="h-5 w-5 animate-pulse rounded-full bg-line" />
          )}
        </div>
      )}

      {isVideo && (
        <>
          <div className="pointer-events-none absolute inset-0 flex items-center justify-center">
            <span className="rounded-full bg-ink/55 p-2 backdrop-blur-sm">
              <Icon name="play" size={16} className="text-fg" fill="currentColor" strokeWidth={0} />
            </span>
          </div>
          {it.duration ? (
            <span className="pointer-events-none absolute bottom-1.5 start-1.5 rounded-sm bg-ink/75 px-1.5 py-0.5 font-mono text-[10px] tabular-nums text-fg backdrop-blur-sm">
              {fmtDuration(it.duration)}
            </span>
          ) : null}
        </>
      )}

      <button
        onClick={(e) => {
          e.stopPropagation();
          onDownload();
        }}
        title={isVideo ? t("Save video to Downloads") : t("Save to Downloads")}
        className="absolute bottom-1.5 end-1.5 flex h-7 w-7 items-center justify-center rounded-lg bg-ink/75 text-fg opacity-0 backdrop-blur-sm transition-opacity hover:bg-ink/90 group-hover:opacity-100"
      >
        <Icon name="download" size={13} />
      </button>
    </div>
  );
}

/* Memoised. This was originally defence against the phone's 1 Hz now-playing
   push re-rendering every view; that push no longer reaches `App` at all (it
   lives in `lib/mediaStore`, read only by the two components that show it). The
   memo stays because `App` still re-renders for its own reasons — an arriving
   notification, a toast appearing and expiring, a transfer's progress — and
   none of those change this view's props. All props here are primitives or
   stable useCallback refs, so the comparison is sound. */
export default memo(PhotosView);
