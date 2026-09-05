# Locale catalogs

One file per language, exporting a `Catalog` — a flat map from the **English
string** to its translation.

```ts
// de.ts
import type { Catalog } from "../lib/i18n";

export const de: Catalog = {
  "Setup check": "Systemprüfung",
  "Ring my phone": "Mein Telefon klingeln lassen",
};
```

Then add one line to `LOCALES` in [`../lib/i18n.ts`](../lib/i18n.ts). That is the
whole procedure — there is no code to change, and the picker in Settings
appears on its own once a second language exists.

## Why the key is the English text

A missing entry returns the key, and the key is correct English. So a partial
catalog is a partially-translated app, never an app showing
`settings.system.health.title` to a user.

The cost: **editing English copy orphans that string's translations**, because
the key changed. Run `npm run i18n:check` to list orphans (translated strings no
longer used) and gaps (used strings with no translation).

## Placeholders

`{name}` is substituted at runtime and must survive translation:

```ts
"Sent {file} → Download": "{file} → Download gesendet",
```

Plurals come through `tn(one, other, count)` as two independent English strings,
both of which need their own entry — English's "+s" rule does not generalise.
