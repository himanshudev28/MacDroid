# Screenshots

The images [`README.md`](../../README.md) shows in its **📸 Screenshots** section.

They are copies of the `@2x` shots from the website repo,
[`himanshudev28/DroidDockWebsite`](https://github.com/himanshudev28/DroidDockWebsite),
under `public/shots/`. They were hot-linked from there at first, which meant a
rename in that repo silently emptied this one's README — so they live here
instead. 264 KB for the set.

The `@2x` suffix is dropped on the way in: there is no 1×/2× switching in a
README, so the high-DPI file is simply *the* file.

| Here | From the website repo | Size |
|---|---|---|
| `mac-dashboard.webp` | `public/shots/mac-dashboard@2x.webp` | 1120×720 |
| `mac-notifications.webp` | `public/shots/mac-notifications@2x.webp` | 1120×720 |
| `mac-mirror.webp` | `public/shots/mac-mirror@2x.webp` | 1120×720 |
| `mac-apps.webp` | `public/shots/mac-apps@2x.webp` | 1120×720 |
| `phone-home.webp` | `public/shots/phone-home@2x.webp` | 720×1560 |
| `phone-control.webp` | `public/shots/phone-control@2x.webp` | 720×1560 |
| `phone-clipboard.webp` | `public/shots/phone-clipboard@2x.webp` | 720×1560 |

To re-sync after reshooting them on the website:

```sh
SHOTS=https://raw.githubusercontent.com/himanshudev28/DroidDockWebsite/main/public/shots
for n in mac-dashboard mac-notifications mac-mirror mac-apps \
         phone-home phone-control phone-clipboard; do
  curl -sfL "$SHOTS/$n@2x.webp" -o "docs/shots/$n.webp"
done
```
