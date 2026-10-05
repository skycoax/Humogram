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
- Only what ships in the APK can recognise malware: a sample newer than the
  bundled `device-threat-intel.json` is caught by the heuristics or not at all,
  until an app update brings new indicators.

## Privacy policy and Data safety

Play counts data as collected only when it leaves the device, so Data safety
declares nothing for the scanner (`data.scanner_sends = no` in
`docs/play-console.md`). The installed-app inventory is still personal and
sensitive data under Play's User Data policy however it is obtained, so the
privacy policy (`docs/privacy-policy.md` and the copy on hg.skycoax.uz/privacy)
must keep describing what the scanner reads on the phone and that it sends
none of it.

`Tools/play_policy_check.py` stops a release build if code that reads
`jac_api_base_url` comes back while `data.scanner_sends` says `no`. That string
no longer exists; adding a network step means re-adding it, the declarations,
the privacy policy and the About text (`jac_about_scanner_body`) together.
