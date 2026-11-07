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

## Demo GIF

`demo.gif` (960×540, 12 fps, ~37 s, ~6.7 MB) is the top of the README. It is
cut from the 3.0 tour video, `videos/droiddock-native-demo/renders/DroidDock-3.0-demo.mp4`
(made with demo data, not a real phone): the feature part, 5.5 s → 60.5 s,
played at 1.5× speed. Keep it under ~10 MB or GitHub may not show it.

```sh
SRC=videos/droiddock-native-demo/renders/DroidDock-3.0-demo.mp4
ffmpeg -ss 5.5 -to 60.5 -i "$SRC" -an -filter_complex \
  "[0:v]setpts=PTS/1.5,fps=12,scale=960:-1:flags=lanczos,split[a][b];\
   [a]palettegen=max_colors=128:stats_mode=diff[p];\
   [b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle" \
  docs/shots/demo.gif
```
