# Plan: stable code signature (self-signed certificate)

Status: **implemented** (2026-10-31). Identity `DroidDock Code Signing`, SHA-1
`EC684D33CEEC39388A90C293CB1FCE2EA9364900`, valid to 2046-10-26. Files in `~/.droiddock-release/`:
`codesign.p12` (+ `codesign-p12-password`), `codesign-cert.pem`, and a dedicated
`codesign.keychain-db` (+ `codesign-keychain-password`) for local builds. Local signed build:

    CODESIGN_ID=EC684D33CEEC39388A90C293CB1FCE2EA9364900 \
    CODESIGN_KEYCHAIN=~/.droiddock-release/codesign.keychain-db \
    CODESIGN_KEYCHAIN_PASSWORD="$(cat ~/.droiddock-release/codesign-keychain-password)" \
    droiddock-native/scripts/build.sh

CI (`release.yml`, "Import the code-signing certificate") needs secrets `DD_CODESIGN_P12_B64`
and `DD_CODESIGN_P12_PASSWORD` and **fails the release** without them (and without
`DD_UPDATE_SIGNING_KEY`): an unsigned or ad-hoc release would be refused by every installed
copy's updater. The updater (`check_signer` in `updater.rs`) refuses an update that doesn't
satisfy a certificate-signed install's requirement and shows the release-page fallback.
Verified: two different builds share the requirement (`codesign --verify -R`), a signed copy
launches, `build.sh` restores the keychain search list. Not verified: a real TCC grant kept
across an update (needs a person to grant Accessibility).

The rest of this file is the original plan.

## Problem

`droiddock-native/scripts/build.sh` signs ad-hoc (`CODESIGN_ID` unset → `-`). An ad-hoc signature's designated
requirement is the code hash (`cdhash H"…"`), so every build is a different app to macOS. TCC
records the Accessibility grant against that requirement, so after **every** update (manual or
in-app) remote clicks/keys silently stop until the user resets the permission (README, "Updating
the Mac app revokes its Accessibility permission").

## Fix

Sign every build with one long-lived self-signed code-signing certificate. The designated
requirement then becomes `identifier "com.droiddock.mac" and certificate leaf = H"<cert sha1>"`,
which is the same for every build signed with that certificate, so TCC grants survive updates.

What it does **not** change: Gatekeeper. A non-Apple certificate is still an "unidentified
developer"; first launch of a downloaded DMG still needs Privacy & Security → Open Anyway. Only a
paid Developer ID + notarization removes that.

Existing users re-grant Accessibility once, on the first build signed this way.

## Steps

1. **Create the identity (local, once).** `openssl req -x509 -newkey rsa:3072 -days 7300 -nodes
   -subj "/CN=DroidDock Code Signing" -addext "keyUsage=critical,digitalSignature"
   -addext "extendedKeyUsage=critical,codeSigning"`, export a password-protected `.p12`, store it
   in `~/.droiddock-release/` (next to the Android keystore and the update key; back them up
   together). Import into the login keychain. Check `security find-identity -p codesigning`;
   if codesign refuses an untrusted identity, add code-signing trust for that cert only
   (`security add-trusted-cert -p codeSign`) or sign by SHA-1 hash.
2. **Local builds.** `CODESIGN_ID="DroidDock Code Signing" scripts/build.sh` (build.sh already
   honours it; keep `--options runtime`).
3. **Verify before shipping.** Build twice with a code change in between; `codesign -d -r- ` must
   print the same designated requirement both times, and differ from the ad-hoc `cdhash` form.
   Then on this Mac: grant Accessibility to build A (bare binary rules from droiddock-native/CLAUDE.md §3 apply:
   test with a copy under a different bundle id, never the user's real app), replace it with build
   B, confirm the grant still works.
4. **CI.** Secrets `DD_CODESIGN_P12_B64` + `DD_CODESIGN_P12_PASSWORD`. In the mac job before
   "Build the app": create a temporary keychain, import the p12, `security
   set-key-partition-list -S apple-tool:,apple:,codesign:`, export `CODESIGN_ID`; delete the
   keychain in an `always()` step. Never echo the secrets. Without the secrets, fall back to ad-hoc
   with a `::warning::` (same pattern as the update-signing step).
5. **Updater hardening (small, after 4).** In `droiddock-native/core/src/updater.rs` `validate_bundle`, also require the
   new bundle to satisfy the running app's designated requirement (`codesign --verify -R
   '<our DR>'`) once releases are certificate-signed. That stops an update from swapping identity
   even with a valid Ed25519 signature.
6. **Docs.** README: replace "A Developer ID signature is the only thing that removes this" with
   the one-time re-grant note; CHANGELOG entry.

## Risks

- Lost certificate → next build has a new identity → users re-grant once. Keep the backup.
- Expiry: the requirement pins the certificate hash, not its dates; 20-year validity anyway.
- Keychain prompts for items created by the ad-hoc build may appear once after the switch.
