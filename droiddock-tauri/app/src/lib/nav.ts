/// The app's navigation vocabulary, in one place so the rail, the keyboard
/// shortcuts and the views themselves can't drift apart.
///
/// All thirteen views survive the AirSync-style restructure — they moved from a
/// tall labelled list into a narrow icon rail so the phone card can own the
/// space a nav list used to. Grouping is unchanged: Connect / Conversations /
/// Library, the way a native Mac sidebar (Finder, Mail) groups.

export type ViewId =
  | "dashboard"
  | "apps"
  | "files"
  | "photos"
  | "messages"
  | "contacts"
  | "calls"
  | "notifications"
  | "clipboard"
  | "media"
  | "mirror"
  | "camera"
  | "devices"
  | "settings";

export type NavItem = {
  id: ViewId;
  label: string;
  /// Inline 24×24 stroke path — same convention as `Icon.tsx`/the old Sidebar.
  path: string;
  /// ⌘<n> accelerator, where defined.
  key?: string;
};

export const HOME: NavItem = {
  id: "dashboard",
  label: "Dashboard",
  key: "1",
  path: "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6",
};

export const GROUPS: { title: string; items: NavItem[] }[] = [
  {
    title: "Connect",
    items: [
      {
        id: "apps",
        label: "Apps",
        key: "2",
        path: "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z",
      },
      {
        id: "devices",
        label: "Devices",
        key: "3",
        path: "M9 2v2M15 2v2M9 20v2M15 20v2M2 9h2M2 15h2M20 9h2M20 15h2M7 5h10a2 2 0 012 2v10a2 2 0 01-2 2H7a2 2 0 01-2-2V7a2 2 0 012-2z M9 9h6v6H9z",
      },
      {
        id: "mirror",
        label: "Mirror",
        key: "4",
        path: "M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z",
      },
      {
        id: "camera",
        label: "Camera",
        key: "5",
        path: "M23 19a2 2 0 01-2 2H3a2 2 0 01-2-2V8a2 2 0 012-2h4l2-3h6l2 3h4a2 2 0 012 2z M12 17a4 4 0 100-8 4 4 0 000 8z",
      },
    ],
  },
  {
    title: "Conversations",
    items: [
      {
        id: "notifications",
        label: "Notifications",
        key: "6",
        path: "M15 17h5l-1.4-1.4A2 2 0 0118 14.2V11a6 6 0 10-12 0v3.2a2 2 0 01-.6 1.4L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9",
      },
      {
        id: "messages",
        label: "Messages",
        key: "7",
        path: "M8 10h.01M12 10h.01M16 10h.01M9 16H5a2 2 0 01-2-2V6a2 2 0 012-2h14a2 2 0 012 2v8a2 2 0 01-2 2h-5l-5 5v-5z",
      },
      {
        id: "calls",
        label: "Calls",
        key: "8",
        path: "M3 5a2 2 0 012-2h3.28a1 1 0 01.948.684l1.498 4.493a1 1 0 01-.502 1.21l-2.257 1.13a11.042 11.042 0 005.516 5.516l1.13-2.257a1 1 0 011.21-.502l4.493 1.498a1 1 0 01.684.949V19a2 2 0 01-2 2h-1C9.716 21 3 14.284 3 6V5z",
      },
      {
        id: "contacts",
        label: "Contacts",
        key: "9",
        path: "M17 20h5v-2a3 3 0 00-5.356-1.857M17 20H7m10 0v-2c0-.656-.126-1.283-.356-1.857M7 20H2v-2a3 3 0 015.356-1.857M7 20v-2c0-.656.126-1.283.356-1.857m0 0a5.002 5.002 0 019.288 0M15 7a3 3 0 11-6 0 3 3 0 016 0z",
      },
    ],
  },
  {
    title: "Library",
    items: [
      {
        id: "files",
        label: "Files",
        path: "M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z",
      },
      {
        id: "photos",
        label: "Photos",
        path: "M4 16l4.586-4.586a2 2 0 012.828 0L16 16m-2-2l1.586-1.586a2 2 0 012.828 0L20 14m-6-6h.01M6 20h12a2 2 0 002-2V6a2 2 0 00-2-2H6a2 2 0 00-2 2v12a2 2 0 002 2z",
      },
      {
        id: "media",
        label: "Media",
        path: "M9 19V6l12-2v13M9 19a3 3 0 11-6 0 3 3 0 016 0zM21 17a3 3 0 11-6 0 3 3 0 016 0z",
      },
      {
        id: "clipboard",
        label: "Clipboard",
        path: "M16 4h2a2 2 0 012 2v14a2 2 0 01-2 2H6a2 2 0 01-2-2V6a2 2 0 012-2h2M9 2h6a1 1 0 011 1v2a1 1 0 01-1 1H9a1 1 0 01-1-1V3a1 1 0 011-1z",
      },
    ],
  },
];

export const SETTINGS: NavItem = {
  id: "settings",
  label: "Settings",
  key: ",",
  path: "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z",
};

/// Flat list in rail order — used for ⌘<n> lookup and the title bar's label.
export const ALL_ITEMS: NavItem[] = [HOME, ...GROUPS.flatMap((g) => g.items), SETTINGS];

export const itemFor = (id: ViewId): NavItem =>
  ALL_ITEMS.find((i) => i.id === id) ?? HOME;
