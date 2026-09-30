<div align="center">

<img src="https://raw.githubusercontent.com/wlmosv-png/TGAutoSign/master/docs/banner.png" width="720" alt="TGAutoSign">

# TGAutoSign

<p align="center"><a href="https://github.com/wlmosv-png/TGAutoSign/blob/master/README.md">中文</a> · <b>English</b></p>

**Tap once. Signed every day.**

Tap the bot's check-in button in Telegram once — then never think about it again.
The control panel lives inside Telegram: send `/jmb` in any chat.

[![Latest Release](https://img.shields.io/github/v/release/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign?label=release&color=blue)](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/total?label=downloads&color=brightgreen)](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases/latest)
[![API](https://img.shields.io/badge/libxposed-API%20102-8A2BE2)](https://github.com/LSPosed/LSPlant)
[![License](https://img.shields.io/badge/license-GPLv3-green)](LICENSE)
[![Telegram Group](https://img.shields.io/badge/Telegram-Join%20Group-26A5E4?logo=telegram&logoColor=white)](https://t.me/+V2Oyu8pSubs4ZjE0)

**⬇️ [Download the latest APK](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases/latest)** · [Source repo](https://github.com/wlmosv-png/TGAutoSign) · [Changelog](https://github.com/wlmosv-png/TGAutoSign/blob/master/CHANGELOG.md)

by **wlmosv** · if it's useful, [star the source repo ⭐](https://github.com/wlmosv-png/TGAutoSign)

</div>

---

## 🔐 Trust & Risk

<sub>A module that can act on your Telegram account owes you a straight answer. Everything here can be verified independently.</sub>

| | Concern | What we do |
| --- | --- | --- |
| 🔌 | **Permissions** | No storage, notification or install permissions are requested. The single `INTERNET` permission is used only by the built-in update check, which can be turned off |
| 🏠 | **Where data lives** | Targets, signed-state and account slots stay in Telegram's local storage on your device — they never leave it |
| 🌐 | **The only network call** | One GitHub public API request every 12 hours to check for a new version. It carries no account or sign-in data |
| 🛡️ | **Rate-limit handling** | Sign-in slots are randomised within a window and spaced across targets; backoff 5m → 15m → 45m → 2h → 4h; `FLOOD_WAIT` is obeyed literally |
| 📦 | **Anti-tampering** | Each release ships `sha256sum.txt`; the built-in updater verifies byte-for-byte and aborts on mismatch |

### ✅ Verify it yourself

| What | How |
| --- | --- |
| **Network reach** | Exactly one class opens a connection: `app/src/main/java/.../update/UpdateChecker.java`. Read it |
| **APK integrity** | Compare against the release's `sha256sum.txt`: `sha256sum TGAutoSign-*.apk` |
| **Signing key not swapped** | Compare the fingerprint below, or run `keytool -printcert -jarfile TGAutoSign-*.apk` |

**Official signing certificate**

```
SHA-256  AF:55:24:CD:55:4A:E6:ED:E3:EC:27:47:C7:BE:D1:59:
         B0:9B:C4:F6:A4:32:44:A6:8B:46:22:E9:46:32:25:C8
SHA-1    58:A2:B4:D3:F2:83:9A:92:7A:D4:0C:23:51:FE:43:AB:6F:D3:FB:C3
Subject  CN=wlmosv, OU=TGAutoSign, O=wlmosv, C=CN
Valid    2026-09-07 → 2054-01-23
```

> <sub>This fingerprint corresponds to the release key in use since September 2026 and is long-lived. Any future key rotation will be announced in the release notes.</sub>
> **Download only from the two sources below.** We do not vouch for repacked builds on third-party sites — compare the fingerprint above.

- [LSPosed Module Repo](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases/latest)
- [Source repo Releases](https://github.com/wlmosv-png/TGAutoSign/releases)

### ⚙️ Requirements

| | Requirement | Notes |
| --- | --- | --- |
| ✅ | **root + LSPosed** | libxposed **API 102** or newer, i.e. a recent LSPosed.<br>Where to look: LSPosed Manager → Settings → About → API version |
| ⛔ | **Non-root devices** | Not supported — there is no root-free variant |
| ⛔ | **Telegram X** | Not supported — different core classes, the module will not inject |
| ⛔ | **Standalone server deployment** | Not supported — this is a client-side design |

<sub>**Reading the support matrix**: ✅ **tested** = verified on a real device; **statically verified** = class structures compared, not run; **whitelisted** = package name known, unverified. Kept separate on purpose.</sub>

> ⚠️ **Risk notice**
> Any Telegram automation carries some risk of account restriction. This module uses client-side check-ins, randomised slots and backoff, and is deliberately gentle — but it makes **no zero-risk claim**.


---

## 📱 Screenshots

**English UI** — built in on English devices, no setup required

| Main panel | Main menu |
| --- | --- |
| ![Main panel](https://raw.githubusercontent.com/wlmosv-png/TGAutoSign/master/docs/screenshots/main-en-dark.jpg) | ![Main menu](https://raw.githubusercontent.com/wlmosv-png/TGAutoSign/master/docs/screenshots/menu-en-dark.jpg) |
| Targets | Settings |
| ![Targets](https://raw.githubusercontent.com/wlmosv-png/TGAutoSign/master/docs/screenshots/targets-en-dark.jpg) | ![Settings](https://raw.githubusercontent.com/wlmosv-png/TGAutoSign/master/docs/screenshots/settings-en-dark.jpg) |

---

## ✨ What it does

- **Tap once, it learns** — tap the bot's check-in button once and it replays daily, automatically. It keeps up even when the button's callback data changes.
- **Text commands too** — set a bot ID plus a command (e.g. `/checkin`) and it sends on schedule.
- **Group & channel check-ins** — group IDs are supported; replies are read from group messages.
- **Your schedule** — signing window · per-target random slots · minimum spacing · make-up deadline (23:00 by default) · pause a target for a week.
- **Rate-limit friendly** — on failure it backs off 5m → 15m → 45m → 2h → 4h, waits out `FLOOD_WAIT` exactly as the server asks, and catches up when the network returns.
- **Block what you don't want** — keyword / regex rules (matched against bot replies and button labels) · block a whole bot · freeze a single target. Network-learned hits wait for your confirmation.
- **Multi-account, fully isolated** — targets and signed-state are kept per account; targets can be copied across accounts.
- **Daily summary** — one message a day to your own Saved Messages (no system notifications); an extra alert after 3 consecutive failing days.
- **Cross-client sync** — the official client and third-party clients share targets and signed state.
- **Bilingual UI** — English out of the box on English devices; switchable in Settings.
- **Local by design** — no server, no telemetry, nothing reported.

---

## 🖥️ Supported clients

The module matches by host package first, then falls back to flag-class capability — it only injects on a match.

| Client | Package | Status |
| --- | --- | --- |
| Telegram (Play / default) | `org.telegram.messenger` | ✅ tested on 12.10.4 |
| Telegram (direct APK) | `org.telegram.messenger.web` | ✅ statically verified |
| Nagram (NextAlone) | `xyz.nextalone.nagram` | ✅ tested on 12.10.3 |
| Nagram XF | `fork.risin42.nagramx` | ✅ tested (dec46b0) |
| ExteraLess (ExteraGram fork) | `com.exteraless.app` | ✅ tested on 12.10.1 |
| Nekogram | `tw.nekomimi.nekogram` | ✅ tested on 12.10.3 |
| Mercurygram | `it.belloworld.mercurygram` | ✅ tested on 12.10.3.1 |
| Nagram / NagramX / NagramNX | `nu.gpu.nagram` etc. | whitelisted, not tested |
| Other Telegram-Android forks | any | injects if flag classes are intact |
| Telegram X | — | ❌ not injected (different core) |

Adapted for the whole Telegram **12.10.x** line, including 12.10.4.

> ⚠️ **Scope**: LSPosed only enables the official client by default. If you use a third-party client, tick **that client** in the module's scope as well.

---

## 🚀 30-second setup

1. Install the APK → **LSPosed** → **Modules** → enable **TGAutoSign**
2. Tick your Telegram client under **Scope**
3. Fully stop Telegram, then reopen it
4. Send `/jmb` in any chat → go to the bot chat and **tap its check-in button once**

> Done — it signs daily from then on. Send `/jmb` anytime to check status or change settings.

---

## ❓ FAQ

**Tapped the button but nothing was added**

Check the auto-learn switch under Settings → Learning, or add the target manually via `/jmb` → Add target. If it still won't add, make sure the scope is ticked and fully restart Telegram.

**The bot uses buttons instead of text — does that work?**

Yes. Tapping once teaches it (the list shows a type badge) and it replays daily. Buttons that merely open a web page or a game (no callback data) are never mis-learned.

**It works on the official client but not my third-party one**

Those clients are supported (see the matrix above), but scope only includes the official client by default. Tick your client in LSPosed, then fully restart Telegram.

**Signing isn't working — what do I do?**

Run `/jmb` → **Self-check** to verify the reflection anchors, then check the built-in log view for injection and signing records. Still stuck? Export the log together with your client name and version, and open an issue.

**How do I migrate to a new phone or account?**

On the old device: `/jmb` → **Export**. On the new one, drop the json into `Android/data/<client package>/files/tgautosign/` and import it. Import merges — it never wipes.

**Update says "signature mismatch"**

You installed a debug build. Uninstall it, then install the official APK from this page. Official builds upgrade over each other in place, and your data is kept.

---

## 📜 更新日志 · Changelog

### v1.6.1 (124) — 2026-09-28

**Fixed**: Targets multiplied automatically: the module's own callbacks were misread as user learning. · Sent-state rollback caused duplicate sign-ins. · Non-check-in buttons entered the learning scope. · Incorrect account attribution in network-layer learning. · Account index clamping was too broad. · Disabled accounts still performed sign-ins. · Batch sign-in re-sent targets already signed. · The account overview omitted accounts on non-contiguous slots. · Buttons clearly labelled as check-ins were blocked (regression introduced in the previous build).

**New**: Two more supported clients, and a corrected default scope. · Cleanup logging for mislearned entries.

**Tooling**: README changelog generator supports block-style bilingual entries. · New build gate: README changelog must stay in sync. · Pure-logic unit tests grew from 174 to 297.

### v1.6.0 (123) — 2026-09-26

**Architecture**: Account isolation moved from convention to structure. · Six single-responsibility classes extracted from the core file. · Pure logic is now unit-testable. · Storage keys consolidated into a single source of truth. · Flush policy made explicit.

**Fixed**: Account crossover — all six paths now pin the account. · Out-of-range guard read another account's partition (important). · Failed targets were signed over and over (important). · Duplicate scheduling within one account (important). · A successful check-in was revoked and shown as "backoff" (important). · Panel waiting always timed out into the fallback (important). · Same class of bug, full sweep: three more places treated success as failure. · The "pending" pool is now per-account, with automatic migration. · The state used to be write-only: once set there was no way to clear it apart from a manual test… · Reply verdict could land on the wrong account (important). · A full-account round reported one account's tally as another's. · Clearing config left state behind, resurrecting it on re-add (important). · Clearing config wrongly deleted the per-account pending pool. · Stale buttons caused an endless retry loop. · Silent robots made the module wait out a timeout every day. · Crash when saving in the settings screen. · Opening "Logs" stuttered. · Only the first startup line reached the log file. · Tapping a bot button within 30 s of a cold start did nothing. · The make-up window became all day when it crossed midnight. · Configs synced from another client did not apply locally. · Only the first of several bot buttons was learned. · Chain ids in the log could repeat across accounts. · Two instances ran in parallel after a hot reload. · Numeric headings rendered as wrong glyphs in the startup log. · Decorative text showed tofu boxes. · The "pending" retry button used the wrong account.

**New**: Detailed version info in logs and the diagnostics bundle. · Host friendly names follow the UI language.

**Tooling**: The in-repo build.sh was missing three gates. · Unit assertions grew from 115 to 174. · The wiring checker listed a method that no longer existed.

[Full changelog →](https://github.com/wlmosv-png/TGAutoSign/blob/master/CHANGELOG.md)

## 🔗 Download

| Channel | Where |
| --- | --- |
| **This repo's Releases** (*recommended*) | [releases/latest](https://github.com/Xposed-Modules-Repo/io.github.wlmosv_png.tgautosign/releases/latest) |
| Source repo Releases | [wlmosv-png/TGAutoSign](https://github.com/wlmosv-png/TGAutoSign/releases) |
| In-app | Send ` /jmb ` in Telegram → Check for updates |

> Both repos ship byte-identical APKs (with `sha256sum.txt`), signed by the same
> release key — installing over keeps your data.
> Fingerprint and verification steps: see **Trust & Risk** above.

---

## ⚖️ License

Licensed under **GPLv3**. For personal use with your own accounts.
Please respect Telegram's Terms of Service and each group's / bot's rules.

---

<p align="center"><sub>Made by wlmosv · <a href="https://github.com/wlmosv-png/TGAutoSign">a ⭐ keeps it going</a></sub></p>
