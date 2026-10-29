# Device virus scanner — owner notes

The scanner has no page of its own: it is the top of the Humogram page
(`HumogramSettingsActivity`, rows from `DeviceScanSection`), which the device
security card ("Qurilma xavfsizligi") on the main Settings screen and the
Humogram row both open. It checks the installed
apps Android lets Humogram see, plus APK files in Humogram's own download
folders. Runs at every launch (foreground, after the passcode), and on
"Scan now".

One engine, and it runs only on the phone: rules in `jacsecure-core`
(`core/device/InstalledAppPolicy.kt`) and the bundled indicator list
`assets/device-threat-intel.json`. Nothing leaves the phone — there is no
network step anywhere in a scan, and no setting that adds one. Its one
setting, "Scan for viruses at launch", sits in the page's Security card next to
link protection.

Results stay in `getNoBackupFilesDir()/jac-devscan/` (`report.json`,
`cache.json`): app-private, excluded from backups, gone on uninstall or clear
data. Files found under Telegram's cache root, where secret-chat documents
live, are checked and can be deleted from the page, but nothing about them is
written to disk.

Earlier builds had an optional cloud check. A `report.json` written by one may
still carry `mode` and `networkStatus` fields, and a `cache.json` a `remote`
section of server answers; both are ignored when read and dropped at the next
scan's write.

## What it was tested on

An emulator (API 35, 110 apps): a first scan of the installed apps takes under
a second, a repeat scan with files about 2.6 s. A crafted sideloaded app named
"Payme" with SMS and call permissions granted is rated dangerous; the stock
apps raise nothing.

Real lure files forwarded over Telegram ("toydan fotolar (33.jpg).apk" and the
like) are packed so that ordinary ZIP readers refuse them: a fake "encrypted"
flag, decoy entries named `AndroidManifest.xml/..xml`. `core/apk/RawZip.kt`
reads them the way Android does, so they are analysed instead of reported as
"damaged", and the packing itself is a HIGH finding (`APK_EVASIVE_STRUCTURE`).
A loose installer that asks for SMS access is HIGH too (`APK_REQUESTS_SMS`).

Not yet tested: the scanner page and the launch dialogs on a signed-in account
(the emulator's Telegram session had been revoked), and any real phone.

## Known limits

- An app with no launcher icon, accessibility service, notification listener or
  SMS component is invisible to us (no `QUERY_ALL_PACKAGES`, on purpose).
- No brand has a pinned signing certificate yet (`uz-brand-allowlist.json`), so
  "signed by someone else" can never fire; a sideloaded app using a bank's own
  package id is reported by origin instead. Pin with
  `tools/allowlist-provision.mjs` from a genuine APK.
- `STORE_CERT_SHA256` is empty, so apps installed by RuStore or Amazon Appstore
  count as "not from a store" (listed under "Worth a look", not as threats).
- Split installs are fingerprinted from `base.apk` only.
- A sample newer than the bundled `device-threat-intel.json` is caught by the
  heuristics, or by the owner's deny list once it is on it (below), or not at
  all until an app update brings new indicators.
- An app installed outside a store the long way round — a bank's own site, a
  phone-transfer tool (Smart Switch, Mi Mover, Phone Clone), Google Play
  sideloaded onto a Huawei/Honor phone without Google services — is judged as
  sideloaded, and a genuine messenger with SMS and calls granted comes out
  DANGEROUS (seen with WhatsApp, 2026-10-26). The owner's allow list fixes it
  per app; the rules themselves still treat those installers as unknown.

## The owner's app lists (2026-10-27)

`InstalledAppPolicy` also judges with `AppLists`, the `apps` section of the
link guard's signed document (`LinkGuard.appLists()`; the scan takes the list
current when it starts, and the APK-file verdict cache is stamped with its
version):

- **allow** = package + publisher's signing certificate. A match skips every
  rule: risk NONE, source `owner_allowlist`. Same package, other signer: the
  rules run as before.
- **deny** = ThreatIntel families (APK hash, certificate, package). Matched
  after the bundled intel and just as decisive; the chat-file gate
  (`ScannerBootstrap`'s installer intel) uses it too.
- Any indicator, bundled or listed, beats an allow entry.

The details dialog of an app flagged by the rules (NOTICE to DANGEROUS, not
MALICIOUS, not a system app) offers "Send for review" (`ReviewRequest`): on
confirm it sends the package, the signing-certificate SHA-256s read at that
moment, the label and the version to `lists.skycoax.uz/v1/requests`, where the
owner can allow or deny it from the admin page.

## Privacy policy and Data safety

Play counts data as collected only when it leaves the device, so Data safety
declares nothing for the scanner (`data.scanner_sends = no` in
`docs/play-console.md`). The installed-app inventory is still personal and
sensitive data under Play's User Data policy however it is obtained, so the
privacy policy (`docs/privacy-policy.md` and the copy on hg.skycoax.uz/privacy)
must keep describing what the scanner reads on the phone and that it sends
none of it. "Send for review" is the user's own action, declared on its own
(`data.review_requests`): it sends one app's package, signer, name and
version, only when the user taps and confirms.

`Tools/play_policy_check.py` stops a release build if code that reads
`jac_api_base_url` comes back while `data.scanner_sends` says `no`. That string
no longer exists; adding a network step means re-adding it, the declarations,
the privacy policy and the About text (`jac_about_scanner_body`) together.

## Before sign-in (2026-10-16)

The welcome screen has a "Check phone" button under "Start Messaging"
(`IntroActivity`, a few marked lines). It opens `PhoneCheckActivity`: the
scanner section alone (`DeviceScanSection` with the protection percentage left
out, since that counts the account's cloud password), which starts a scan at
once. Back, or "Continue to sign in", returns to the welcome screen.

Nothing in that path needs an account: `DeviceScanner` reads the package
manager and Humogram's folders, the verdicts come from the bundled core, and
the report and every scanner setting are local files (`HumogramConfig`). The
package visibility comes from the manifest's static `<queries>`, which applies
from install, signed in or not.

## The threat alarm

`DeviceScanner` announces each threat (SUSPICIOUS or worse) the moment it
finds it (`Listener.onThreat`), not at the end of the scan. `ThreatAlert`,
registered for every scan, turns that into:

- one modal with a row per threat: icon, name, package (or "APK file"), the
  kind of threat, and a red "Delete" (the system uninstaller for an app, a
  checked delete for a file). "Details" opens the scanner page (the Humogram
  page signed in, the phone check before); "Ignore" closes it and never alarms
  again for those app versions or files. More threats found while it is up
  join its list.
- the alarm (`JacAlarm`, looping until the user answers, at most 30 s) and a
  vibration;
- when Humogram is not in front (or is behind its passcode): a heads-up
  notification on the high-importance channel "Threats found on the phone" and
  one pass of the alarm. Opening the app shows the modal
  (`LaunchActivity.onResume`, and the chat list's resume after the passcode).

The launch scan's Bulletins skip whatever the alarm already said.

## Alarm sounds

Five, in `res/raw/alarm_*.ogg`, generated by `Tools/alarm_sounds.py` (Python +
numpy, ffmpeg/libvorbis): radar, klaxon, air-raid siren, "red level",
evacuation (temporal-three). Synthesised from oscillators, no recording of any
phone maker's alarm; 44.1 kHz mono, 3-4 s, decoded peak -1 dBFS, seamless
loops. Played with `AudioAttributes.USAGE_ALARM` (alarm volume, not media).
The Humogram page has "Sound on threat" (off = vibration only) and "Alarm
sound", a page with the five and a "Play" button. Silent mode is respected:
vibration only.

## APK verdicts (2026-10-16)

Until this date every APK in a chat was drawn red and stopped at open, whether
or not anything had been found in it (`APK_INSTALLABLE` was HIGH, the chat
cell painted every installer red, and the open gate warned on every
installer). That is gone. An APK in a chat is now judged on what the analysis
actually finds:

- the manifest, permissions, signer, bank impersonation and evasive packing
  (`ApkAnalyzer`, as before);
- the device scanner's known-malware list (SHA-256 of the file, its signing
  certificate, its package) via `ScanEngine.InstallerIntel` — the same
  `device-threat-intel.json` the installed-app scan uses. A match is MALICIOUS.
- being an APK is INFO only, and so is the code every APK contains
  (`classes.dex`, `.so`); an installer packed inside the APK still counts.

An APK that was read and where nothing fired is CLEAN (green, "Checked —
nothing harmful found"). One with no verdict yet is neutral ("Not checked
yet"); tapping it to install runs the check first, behind a spinner, and only a
finding stops it. Threats are red everywhere (chat bubble, chat list, link
interstitial, scanner rows and status); yellow is left only for "could not
check" and the security-checkup level.

Limit: the known-malware list is only as new as the app update that shipped
it. A real reputation check (for example VirusTotal by SHA-256) would need a
network step, which this product deliberately does not have (see "Privacy
policy and Data safety" above for everything that would have to change).

## If QUERY_ALL_PACKAGES is ever added

Today the scanner does not hold it and needs no declaration. If it is added to
see apps the targeted `<queries>` cannot (an app with no icon, accessibility
service, notification listener or SMS component), Play asks for a permission
declaration. Ready text for the form (Policy > App content > Sensitive
permissions > QUERY_ALL_PACKAGES), "Core functionality: Antivirus / device
security":

> Humogram includes an on-device antivirus. Its "Check phone" scan, available
> on the first screen before sign-in and in Settings, inspects every installed
> app for malware: known-malware fingerprints (SHA-256 of the APK and signing
> certificate) bundled in the app, and the capabilities banking trojans abuse
> (accessibility services, SMS access, notification access, device admin,
> hidden launcher icons, installs from outside an app store). Malware hides
> precisely by having no launcher icon or other visible component, so a scan
> limited to targeted queries misses the apps that matter most. The list of
> installed apps is processed only on the device and is never transmitted; the
> user can remove a threat from the scan result with the system uninstaller.

Play grants this to apps whose core purpose is device security; Humogram is
also a messenger, so a rejection is possible. Ship it in a separate release so
a rejection does not hold up anything else, and update the privacy policy and
Data safety wording ("installed apps", on-device only) in the same release.
