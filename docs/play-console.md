# Play Console declarations

What we told Google about Humogram (`uz.humogram.app`) in Play Console, and why.
Play Console is the source of truth; this file is the record kept next to the
code, so a change to the app can be compared with what we declared.
`Tools/play_policy_check.py` reads the `iarc.*`, `audience.*` and `data.*` lines
below and fails when the code ships a feature a declaration denies. Every
release build runs it (see `build.gradle`).

## Why this file exists

In September 2026 three policy issues had one cause: the declarations and the
code drifted apart, and nothing compared them.

- **Content rating**, rejected 2026-09-25. The August questionnaire described
  a communication app for people you already know, with moderated chat, and
  rated it ESRB Everyone. Groups of 200 000, channels, 18+ media and gift
  crafting had been in the code all along. Re-taken on 2026-09-25; the result
  now matches the official Telegram app (US Mature 17+, DE USK 18, generic 12+).
- **Billing Library 8.0.0** and **target API 36**, enforced 2026-08-31. Fixed
  in 69939, but 69929 (closed testing) and 69919 (internal testing) kept both
  warnings open: Play checks every track you publish to, not only production.

## Content rating (IARC questionnaire)

Policy and programs > App content > Content ratings > Start new questionnaire.
Re-take it whenever a line below stops being true, and change the line in the
same commit.

```
iarc.category        = social   # groups up to 200 000, channels, public search
iarc.dating          = no
iarc.nudity          = yes      # public channels; 18+ media behind "Show 18+ Content"
iarc.nudity_primary  = no
iarc.violence        = no       # Telegram's terms forbid promoting violence in public
iarc.location        = yes      # location and live-location sharing
iarc.digital_goods   = yes      # Premium, Stars and gifts through Google Play Billing
iarc.random_items    = yes      # gift upgrades (random attributes), gift crafting ("success chance")
iarc.block           = yes
iarc.report          = yes
iarc.moderation      = no       # nobody moderates private chats and groups
iarc.friends_only    = no

audience.min_age     = 13       # target audience 13-15, 16-17, 18+; privacy policy "Children"
```

Result on 2026-09-25: ESRB Mature 17+, PEGI Parental guidance, USK 18,
ClassInd 18, IARC generic, Russia and South Korea 12+.

`violence = yes` was tried and not submitted: it rates the app 18+ everywhere,
Uzbekistan included, where Telegram itself is 12+.

## Data safety: the scanner

Play counts data as collected only when it is transmitted off the device. The
whole scanner stays on the phone, so it collects and shares nothing, and Data
safety declares nothing for it. The privacy policy (site and `docs/`, updated
2026-10-03) and the About screen (`jac_about_scanner_body`) must say the same.
That covers:

- **Files and links in chats.** Checked on the phone against rules and a
  database inside the app; nothing is sent.
- **Virus scanner for installed apps** (`DeviceAppCollector`, `DeviceScanner`).
  It sees the apps Android makes visible through the manifest's targeted
  `<queries>`: launcher apps, apps with an accessibility service, notification
  listener or SMS role, and stores and installers. It does not hold
  `QUERY_ALL_PACKAGES`, so no permission declaration is needed for it (a
  ready declaration text, should it ever be added, is in
  `docs/device-scanner.md`). It also runs before sign-in ("Check phone" on
  the welcome screen), still entirely on the phone. Per
  app it reads the package name, label, version, installer, granted SMS and
  call permissions, whether accessibility, notification access, device admin,
  default SMS or draw-over-apps is active, whether the launcher icon is hidden,
  and the signing-certificate fingerprint. For apps installed outside a store
  it also reads SHA-256/SHA-1/MD5 of the installer, and it reads APKs in
  Humogram's own download and cache folders. All of it is matched against
  rules and known-malware fingerprints that ship in the app. Results go to
  `getNoBackupFilesDir()`, which is app-private, excluded from backups, and
  gone on uninstall or clear data. There is no cloud check. Data safety
  declares neither "Installed apps" nor "Files and docs", because none of it
  leaves the phone.

```
data.scanner_sends   = no       # scanner, chat checks and installed-app scan: nothing leaves the phone
```

If a scanner feature ever sends anything off the phone, do all of this before
that build goes to Play: declare it in Data safety, rewrite the scanner parts
of the privacy policy (site and `docs/`) and the About text, then set the line
above to `yes`. `play_policy_check.py` stops the release build while any
shipped code reads `jac_api_base_url` and this line says `no`.

## Data safety: the link guard's list

The link guard (`LinkGuard`) downloads one public, signed file of trusted and
blocked sites, `https://lists.skycoax.uz/v1/lists.json`
(`jac_linkguard_lists_url`), and matches links against it on the phone. It
fetches a few seconds after start-up, when the app returns to the foreground,
about every five minutes while it stays there, and when a warned link is
opened, throttled to the same interval. Since 2026-10-27 the same file also
carries the device scanner's app lists, so it is fetched while either link
protection or "Scan for viruses at launch" is on; with both switched off
nothing is fetched at all. The
request is the same GET for everyone. It carries the phone's IP address, as
every request does, `User-Agent: Humogram/<versionCode>`, and the ETag of the
list already held. It has no link, host, message, account or device
identifier, and no cookie, token or query.

On the server (`TelegramAPI/deploy/linkguard-lists/nginx.conf.template`), the
access log for `/v1/lists.json` uses `lg_anon`: time, status, bytes and user
agent, with no address. The error log for that location is
`error_log /dev/null crit;`, so no IP address is kept for list requests;
`test_lists.py` holds the template to both. The server is a VPS in Tashkent,
Uzbekistan (46.8.195.171, AIRNET LLC), and the policy says so.

Data safety answers for this request, worked out 2026-10-03:

- **Collected: no data type.** Every request carries an IP address, and Data
  safety has no IP-address type. Here the address is used only to answer the
  request. It does not derive a location, does not identify anyone, and is
  written to neither the access log nor the error log. The build number is
  the same for every install of a build, and the ETag is the same for
  everyone holding that list, so neither is user data.
- **Shared: nothing.**
- **Encrypted in transit: yes.** It is HTTPS only, and the app refuses a list
  URL that is not https.

The privacy policy (site and `docs/privacy-policy.md`, all three languages,
2026-10-03) says the developer runs one server, `lists.skycoax.uz`, that only
serves this list. It says the server is in Tashkent, Uzbekistan, what the
request carries, that nothing about links is sent, that no IP address is kept
for list requests, and how to switch the feature off. "Operates no server" is
gone from both copies.

```
data.linkguard_list_download = declared   # public list pulled from lists.skycoax.uz; nothing about links is sent
```

`declared` here means the privacy policy discloses the request and the Data
safety answers above cover it. Since they add no data type, the console form
needs no new entry. Play Console is still the source of truth: when the first
build with the link guard is submitted, open Data safety and confirm it still
reads this way. If anything is changed there, change this section in the same
commit. `play_policy_check.py` checks this line, that the policy names the
host and no longer says "operates no server", and that the About text
(`jac_about_scanner_body`) names the host.

## Data safety: review requests ("Send for review")

`ReviewRequest`, added 2026-10-27. Under a red link's warning and in a flagged
app's details, the user can tap "Send for review"; a dialog says exactly what
goes, and only on "Send" does the app POST to
`https://lists.skycoax.uz/v1/requests` (`jac_review_requests_url`):

- a site: its host name only (punycode) — no path, query or chat;
- an app: package name, SHA-256 of its signing certificate(s), label, version.

The request carries the IP address and `User-Agent: Humogram/<versionCode>`
as every request does, and nothing else: no account, phone number, device id
or cookie. On the server nginx blanks `X-Real-IP`/`X-Forwarded-For` and logs
`lg_anon` with `/dev/null` as the error log; `lists.py` stores the item, a count
per day and the first/last time in its review queue; once the owner decides,
the row stays as a record with the decision and is deleted a year after it was
last sent (2026-10-29; the privacy policy says the same). `test_lists.py` holds
both to that.

This is the first data that comes from the user rather than being the same for
everyone, so unlike the list download it IS collected in Play's sense. Answers
for the Data safety form, worked out 2026-10-27:

- **Web browsing → Web browsing history:** collected; not shared; processed
  ephemerally: no (queued until reviewed); **optional** (users choose whether
  to send); purposes: App functionality, Fraud prevention, security, and
  compliance.
- **App activity → Installed apps:** collected; not shared; not ephemeral;
  **optional**; purposes: App functionality, Fraud prevention, security, and
  compliance.
- **Encrypted in transit:** yes (HTTPS only; the app refuses a plain-http URL).
- **Deletion:** a request holds nothing that links it to a person, and it is
  deleted once reviewed; say so if the form asks how users can request deletion.

The privacy policy (site and `docs/`, three languages, 2026-10-27) has a "Send
for review" subsection under section 3, and sections 1, 2, 4, 5 and 6 mention
it. The About text (`jac_about_scanner_body`) says it in three languages.

```
data.review_requests = declared   # 1.0.12; the owner submits the Data safety form below before sending 1.0.12 to review
```

`pending` stops the release build (`play_policy_check.py`). Set to `declared`
on 2026-10-29 for the 1.0.12 bundle, on the owner's word that the Data safety
form with the two data types above is submitted, and the updated policy is
live on hg.skycoax.uz/privacy, **before 1.0.12 is sent to review**. Until both
are done, 1.0.12 must not go beyond the upload: a release that sends data the
form does not declare is what Play removes apps for.

## Data safety: push notifications

Off for now. The google-services plugin runs only when
`TMessagesProj_App/google-services.json` is ours (it names `uz.humogram.app`);
the file there today is Telegram's and is ignored. So no build registers for
Firebase Cloud Messaging, nothing goes to Google, and Data safety declares
nothing for push.

When our Firebase project goes in (owner steps: `docs/push-notifications.md`),
Firebase gives the app an installation ID and a push token, and Telegram's
servers send each notification through Google, encrypted with a key only the
phone and Telegram hold. Before that build goes to Play: name Firebase Cloud
Messaging in the privacy policy (site and `docs/`, all three languages; the
text is ready in `docs/push-notifications.md`), declare "Device or other IDs"
(collected, not shared, app functionality) in Data safety after checking
Google's own list at https://firebase.google.com/docs/android/play-data-disclosure,
then change the line below to `declared`. `play_policy_check.py` stops the
release build while the file is ours and this line says anything else, or
while the policy does not name Firebase Cloud Messaging.

```
data.push_fcm        = off      # no Firebase project of ours; push not registered
```

## Target audience and age

Target audience is 13-15, 16-17 and 18+. The privacy policy (`docs/privacy-policy.md`
and https://hg.skycoax.uz/privacy) states the same minimum, 13, and that 18+
media stays hidden until the user confirms they are 18. Change all three
together.

## Target API and Billing Library

Google raises both every year on 31 August and warns in Policy status only
weeks before. The rows live in `PLAY_REQUIREMENTS` in
`Tools/play_policy_check.py`: a row warns 180 days before its date and fails the
release build once the date has passed.

| From       | Target API | Billing Library | Source                          |
|------------|------------|-----------------|---------------------------------|
| 2026-08-31 | 36         | 8.0.0           | enforced (Policy status)        |
| 2027-08-31 | 37         | 9.0.0           | expected; confirm in the console |

Since 1.0.1 we ship minSdk 24 (Play's automatic protection requires it), so
Billing 9 (minSdk 23) is no longer blocked by our minSdk.

## Tracks

Every track with a release serves its build to users, and Play checks it. After
a production release is approved, promote the same build to internal and closed
testing (or stop using those tracks); otherwise the old bundles keep the policy
warnings open.

## Sign-in details (App access)

Login is by one-time code. Reviewers get the test number and a private relay
page that shows the latest code (`tg-relay` on the VPS). The relay must stay up
for the whole review. Its address is in Play Console only; never commit it.
