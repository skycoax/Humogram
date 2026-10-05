#!/usr/bin/env python3
"""
Store-readiness checks for Humogram.

WHY THIS EXISTS

A Play policy review found sixteen blocking problems in this fork, and every
one of them was invisible to the compiler. The build was green while the chat
list was headed by Telegram's registered wordmark, while READ_CALL_LOG shipped
in the release manifest, and while the screen carrying the unofficial-app
disclosure, the GPL source offer and the privacy policy was declared and never
opened from anywhere.

That class of defect does not come back through testing, because there is
nothing to test: the app behaves exactly as written. It comes back through a
merge, an upstream rebase, or somebody restoring a file. So the findings are
written down here as executable assertions.

WHAT IT IS NOT

Not a substitute for reading the policies. It encodes the specific mistakes
this codebase actually made, which is a much narrower thing than "the app
complies with Google Play". A green run means these particular regressions have
not returned.

The same goes for what we declared in Play Console. The content rating was
rejected because the questionnaire described an app the code had outgrown, and
the target API and Billing Library deadlines passed with nothing in the build
that knew about them. docs/play-console.md records the declarations; the checks
below hold the code to them and to Google's yearly minimums.

RUN

    python tools/play_policy_check.py

Exit code 0 if every check passes, 1 otherwise. Warnings (a Play deadline that
is coming up) print but do not fail. No dependencies, so it runs in CI, in a git
hook, or by hand; every release build runs it too (see build.gradle).
"""

import datetime
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, 'TMessagesProj', 'src', 'main', 'res')
JAVA = os.path.join(ROOT, 'TMessagesProj', 'src', 'main', 'java')
MANIFEST = os.path.join(ROOT, 'TMessagesProj', 'src', 'main', 'AndroidManifest.xml')

failures = []
warnings = []
checks_run = 0


def check(name, condition, detail=''):
    global checks_run
    checks_run += 1
    if condition:
        print('  ok   %s' % name)
    else:
        print('  FAIL %s' % name)
        if detail:
            print('       %s' % detail)
        failures.append(name)


def warn(name, detail=''):
    print('  warn %s' % name)
    if detail:
        print('       %s' % detail)
    warnings.append(name)


def read(path):
    with open(path, encoding='utf-8', errors='replace') as handle:
        return handle.read()


def read_bytes(path):
    with open(path, 'rb') as handle:
        return handle.read()


def java_files():
    for base, _, names in os.walk(JAVA):
        for name in names:
            if name.endswith('.java'):
                yield os.path.join(base, name)


def value_files():
    for name in sorted(os.listdir(RES)):
        if name == 'values' or name.startswith('values-'):
            path = os.path.join(RES, name, 'strings.xml')
            if os.path.exists(path):
                yield path


def string_value(path, key):
    match = re.search(r'<string name="%s"[^>]*>(.*?)</string>' % re.escape(key), read(path), re.S)
    return match.group(1) if match else None


# ---------------------------------------------------------------- impersonation

def check_no_telegram_wordmark_drawables():
    """Telegram's wordmark must not be drawn as this app's identity.

    The drawables may still sit in res/ (upstream ships them and deleting them
    breaks unrelated layouts); what must not exist is a reference from code.
    """
    hits = []
    for path in java_files():
        body = read(path)
        for line_no, line in enumerate(body.splitlines(), 1):
            if 'R.drawable.telegram_logo' in line and not line.strip().startswith('//'):
                hits.append('%s:%d' % (os.path.relpath(path, ROOT), line_no))
    check('no code draws Telegram\'s wordmark', not hits, ', '.join(hits))


def check_identity_bypasses_cloud_strings():
    """The app's own name must not be read through LocaleController.

    LocaleController resolves from Telegram's downloaded language pack before
    it looks at res/, and that pack defines AppName as "Telegram". Renaming the
    app in strings.xml therefore worked only until the pack synced -- the fork
    called itself Humogram on first launch and Telegram from then on, which is
    the impersonation problem the rename was supposed to fix.

    So the identity surfaces must read the resource directly.
    """
    sites = [
        ('org/telegram/ui/DialogsActivity.java', 'chat list title'),
        ('org/telegram/ui/IntroActivity.java', 'first-launch title'),
    ]
    for rel, what in sites:
        path = os.path.join(JAVA, *rel.split('/'))
        if not os.path.exists(path):
            continue
        offenders = [
            '%s:%d' % (rel, n)
            for n, line in enumerate(read(path).splitlines(), 1)
            if ('R.string.AppName' in line or 'R.string.Page1Title' in line)
            and 'getResources()' not in line
            and not line.strip().startswith('//')
        ]
        check('%s does not go through the language pack' % what, not offenders,
              ', '.join(offenders))


def check_app_name_is_ours():
    for path in value_files():
        for key in ('AppName', 'AppNameBeta', 'app_name', 'Page1Title'):
            value = string_value(path, key)
            if value is None:
                continue
            rel = os.path.relpath(path, ROOT)
            check('%s in %s is not Telegram' % (key, os.path.basename(os.path.dirname(path))),
                  'telegram' not in value.lower(),
                  '%s says %r' % (rel, value))


def check_single_launcher_identity():
    """One launcher alias. Alternative icons were Telegram's plane in liveries.

    A third-party app that can dress itself as Telegram is the Impersonation
    policy squarely, and the enum in LauncherIconController must stay in step
    with the manifest -- setIcon() calls setComponentEnabledSetting on every
    value, so an enum entry without an alias throws.
    """
    manifest = read(MANIFEST)
    # Only aliases that put an entry in the launcher count. CallsActivity is an
    # activity-alias too, and counting it made this check fail on a correct
    # manifest -- a check that cries wolf gets switched off, so it has to be
    # exact about what it is looking for.
    aliases = [
        re.search(r'android:name="([^"]+)"', block).group(1)
        for block in re.findall(r'<activity-alias\b.*?</activity-alias>', manifest, re.S)
        if 'android.intent.category.LAUNCHER' in block and re.search(r'android:name="([^"]+)"', block)
    ]
    check('exactly one launcher activity-alias', len(aliases) == 1, 'found: %r' % (aliases,))

    controller = os.path.join(JAVA, 'org', 'telegram', 'ui', 'LauncherIconController.java')
    if os.path.exists(controller):
        body = read(controller)
        enum_block = re.search(r'public enum LauncherIcon \{(.*?)\}', body, re.S)
        entries = re.findall(r'^\s{8}([A-Z_]+)\(', enum_block.group(1), re.M) if enum_block else []
        check('LauncherIcon enum matches the manifest', len(entries) == len(aliases),
              'enum %r vs aliases %r' % (entries, aliases))


def check_splash_is_ours():
    for name in os.listdir(RES):
        path = os.path.join(RES, name, 'styles.xml')
        if not os.path.exists(path):
            continue
        body = read(path)
        check('splash icon in %s is not Telegram\'s' % name,
              'tg_splash' not in body,
              '%s references tg_splash' % os.path.relpath(path, ROOT))


def check_notification_icon_replaced():
    """The status-bar icon is the most-seen asset in the app.

    Checked by size rather than by name: the file keeps upstream's name, so the
    only evidence that it is ours is that the bytes changed. A stale copy of
    Telegram's plane would sail through a name check.
    """
    for density in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi'):
        path = os.path.join(RES, 'drawable-%s' % density, 'notification.webp')
        if not os.path.exists(path):
            continue
        head = read_bytes(path)[:16]
        check('notification.webp (%s) is a real image' % density,
              head[:4] == b'RIFF' and head[8:12] == b'WEBP',
              '%s is not a WebP file' % os.path.relpath(path, ROOT))


# ------------------------------------------------------------------ permissions

RESTRICTED = [
    ('android.permission.READ_CALL_LOG',
     'Play restricts Call Log access to default handlers; it was used only to '
     'auto-read an SMS-verification call and login works without it.'),
]


def check_restricted_permissions():
    manifests = [MANIFEST]
    config = os.path.join(ROOT, 'TMessagesProj', 'config')
    for base, _, names in os.walk(config):
        manifests += [os.path.join(base, n) for n in names if n.endswith('.xml')]
    for permission, why in RESTRICTED:
        offenders = [os.path.relpath(p, ROOT) for p in manifests
                     if permission in read(p)]
        check('%s is not declared' % permission.rsplit('.', 1)[-1], not offenders,
              '%s -- %s' % (', '.join(offenders), why))


# --------------------------------------------------------------------- honesty

def check_no_unevidenced_virus_claim():
    """A heuristic may not assert infection.

    jac_scan_suspicious_installer is shown for a SUSPICIOUS verdict on any
    installable package. It said "This file has a virus", which is a claim
    about somebody else's software that this app cannot support.
    """
    for path in value_files():
        value = string_value(path, 'jac_scan_suspicious_installer')
        if value is None:
            continue
        lowered = value.lower()
        claims = any(word in lowered for word in ('virus', 'вирус', 'virusi'))
        check('suspicious-installer wording is factual (%s)' % os.path.basename(os.path.dirname(path)),
              not claims, '%s says %r' % (os.path.relpath(path, ROOT), value))


def check_disclosure_reachable():
    """The About screen carries three obligations, so it must be openable.

    It was declared in the manifest and started from nowhere: the unofficial
    disclosure, the GPLv3 source offer and the privacy policy all shipped as
    dead code.
    """
    starts = []
    for path in java_files():
        if path.endswith('AboutActivity.java'):
            continue
        if 'AboutActivity.class' in read(path):
            starts.append(os.path.relpath(path, ROOT))
    check('About screen is started from somewhere', starts,
          'nothing calls startActivity(..., AboutActivity.class)')


def check_privacy_policy_names_this_app():
    policy = os.path.join(ROOT, 'docs', 'privacy-policy.md')
    if not os.path.exists(policy):
        check('privacy policy exists', False, 'docs/privacy-policy.md is missing')
        return
    body = read(policy)
    check('privacy policy names this app', 'Humogram' in body)
    check('privacy policy does not name the old brand', 'SkySecure' not in body)


def check_no_user_content_in_release_logs():
    """Logging a filename is a disclosure the privacy policy does not make.

    Guarded by BuildVars.LOGS_ENABLED rather than deleted: the diagnostic is
    worth having while developing.
    """
    scanner = os.path.join(JAVA, 'uz', 'jac', 'secure', 'android')
    unguarded = []
    for base, _, names in os.walk(scanner):
        for name in names:
            if not name.endswith('.java'):
                continue
            path = os.path.join(base, name)
            lines = read(path).splitlines()
            for line_no, line in enumerate(lines, 1):
                if 'Log.d(' not in line:
                    continue
                window = '\n'.join(lines[max(0, line_no - 6):line_no])
                if 'LOGS_ENABLED' not in window and 'DEBUG_VERSION' not in window:
                    unguarded.append('%s:%d' % (os.path.relpath(path, ROOT), line_no))
    check('scanner logging is debug-only', not unguarded, ', '.join(unguarded))


# ------------------------------------------------------------------- packaging

def check_release_ships_every_abi():
    """A single-architecture release installs on the phone you tested with.

    build.gradle carries a task-graph guard; this asserts the guard is still
    there, because losing it costs a release nobody can install.
    """
    body = read(os.path.join(ROOT, 'build.gradle'))
    check('release ABI guard is present',
          'jacAbi' in body and 'GradleException' in body,
          'the -PjacAbi release guard is gone from build.gradle')


# Play raises the minimum target API and Play Billing Library every 31 August
# and says so in Policy status only weeks ahead. This check used to require
# targetSdk 35 and nothing about billing, so it stayed green while 69929 and
# 69919 fell below the 2026 minimums. A row whose date has passed fails; a row
# within WARN_DAYS of its date warns.
#
# The 2027 row is Google's usual cadence, not an announcement. If Policy status
# gives a different number or date, or Play granted more time, edit the row --
# do not delete it.
PLAY_REQUIREMENTS = [
    # from          target API  Billing Library  source
    ('2026-08-31', 36,          (8, 0, 0),       'enforced in Play Console Policy status'),
    ('2027-08-31', 37,          (9, 0, 0),       'expected; confirm in Play Console Policy status'),
]
WARN_DAYS = 180

# The library module and the app module that is uploaded to Play.
SHIPPED_MODULES = ['TMessagesProj', 'TMessagesProj_App']


def declared_sdk(module, key):
    match = re.search(r'%s\s+(\d+)' % key, read(os.path.join(ROOT, module, 'build.gradle')))
    return int(match.group(1)) if match else None


def declared_billing_versions():
    found = []
    for name in sorted(os.listdir(ROOT)):
        path = os.path.join(ROOT, name, 'build.gradle')
        if not os.path.exists(path):
            continue
        pattern = r'com\.android\.billingclient:billing(?:-ktx)?:(\d+)\.(\d+)\.(\d+)'
        for match in re.finditer(pattern, read(path)):
            found.append((os.path.relpath(path, ROOT), tuple(int(g) for g in match.groups())))
    return found


def dotted(version):
    return '.'.join(str(part) for part in version)


def check_play_api_and_billing():
    billing = declared_billing_versions()
    check('Play Billing Library is declared', billing,
          'no com.android.billingclient:billing dependency in any build.gradle')
    for module in SHIPPED_MODULES:
        target = declared_sdk(module, 'targetSdkVersion')
        compile_sdk = declared_sdk(module, 'compileSdkVersion')
        check('%s compiles against its target SDK' % module,
              target and compile_sdk and compile_sdk >= target,
              'compileSdkVersion %s, targetSdkVersion %s' % (compile_sdk, target))

    today = datetime.date.today()
    for since, api, library, source in PLAY_REQUIREMENTS:
        days = (datetime.date.fromisoformat(since) - today).days
        name = 'target API %d and Billing %s (Play, from %s)' % (api, dotted(library), since)
        if days > WARN_DAYS:
            print('  next %s, %d days away' % (name, days))
            continue
        problems = []
        for module in SHIPPED_MODULES:
            target = declared_sdk(module, 'targetSdkVersion')
            if target is None or target < api:
                problems.append('%s targets API %s' % (module, target))
        for path, version in billing:
            if version < library:
                problems.append('%s uses Billing %s' % (path, dotted(version)))
        detail = '; '.join(problems) + ' -- ' + source
        if days <= 0:
            check(name, not problems, detail)
        elif problems:
            warn('%s, in %d days' % (name, days), detail)
        else:
            check(name, True)


def check_no_brand_leftovers():
    """The old brand must not survive anywhere a user can read it."""
    offenders = []
    for path in value_files():
        body = read(path)
        for line_no, line in enumerate(body.splitlines(), 1):
            if 'SkySecure' in line and 'github.com' not in line:
                offenders.append('%s:%d' % (os.path.relpath(path, ROOT), line_no))
    check('no SkySecure strings remain', not offenders, ', '.join(offenders))


# ---------------------------------------------------------------- declarations

DECLARATIONS = os.path.join(ROOT, 'docs', 'play-console.md')


def declared_answers():
    answers = {}
    if os.path.exists(DECLARATIONS):
        pattern = r'^((?:iarc|audience|data)\.\w+)\s*=\s*(\S+)'
        for match in re.finditer(pattern, read(DECLARATIONS), re.M):
            answers[match.group(1)] = match.group(2)
    return answers


# A questionnaire answer the code can prove: (answer, the value it forces, string
# keys whose presence means the feature ships). The rejection of 2026-09-25 was
# this drift exactly -- "communication with people you already know" while
# NewChannel and 200 000-member groups were in the build.
IARC_EVIDENCE = [
    ('iarc.category', 'social', ['NewChannel']),
    ('iarc.nudity', 'yes', ['ShowSensitiveContent']),
    ('iarc.location', 'yes', ['SendLiveLocation', 'ShareLocation']),
    ('iarc.random_items', 'yes', ['GiftCraftTitle', 'Gift2UpgradeButton']),
    ('iarc.block', 'yes', ['BlockUser']),
    ('iarc.report', 'yes', ['ReportChat']),
]


def check_content_rating_matches_code():
    answers = declared_answers()
    check('Play Console declarations are recorded', answers,
          'docs/play-console.md is missing or has no iarc.* lines')
    if not answers:
        return
    strings = read(os.path.join(RES, 'values', 'strings.xml'))
    proven = [(key, forced, [k for k in keys if 'name="%s"' % k in strings])
              for key, forced, keys in IARC_EVIDENCE]
    if declared_billing_versions():
        proven.append(('iarc.digital_goods', 'yes', ['Play Billing']))
    for key, forced, evidence in proven:
        if not evidence:
            continue
        check('IARC %s is %s (the app has %s)' % (key.split('.', 1)[1], forced, evidence[0]),
              answers.get(key) == forced,
              'docs/play-console.md says %r -- re-take the questionnaire, then update the file'
              % answers.get(key))


def check_scanner_disclosure_matches_code():
    """Code that talks to our backend needs declarations that say so.

    69939 sends nothing: no shipped code reads jac_api_base_url, and Data
    safety, the privacy policy and the About screen all say "on the device
    only". The device scanner's cloud check reads it. Shipping that under the
    current declarations is the Data safety version of the content-rating
    rejection, so it stops the release until they are rewritten.
    """
    declared = declared_answers().get('data.scanner_sends')
    senders = sorted(os.path.relpath(p, ROOT) for p in java_files()
                     if 'R.string.jac_api_base_url' in read(p))
    if senders:
        check('scanner network use is declared (data.scanner_sends = yes)', declared == 'yes',
              '%s send(s) to jac_api_base_url, but docs/play-console.md says %r. Update Data safety, '
              'the privacy policy (site and docs/) and jac_about_scanner_body first.'
              % (', '.join(senders), declared))
    policy = os.path.join(ROOT, 'docs', 'privacy-policy.md')
    if declared == 'yes' and os.path.exists(policy):
        check('privacy policy no longer says the scanner sends nothing',
              'collects and sends nothing' not in read(policy),
              'docs/privacy-policy.md still says "Our scanner collects and sends nothing"')


def check_linkguard_disclosure_matches_code():
    """Code that downloads the link guard's list needs declarations that say so.

    The list is pulled from our own host, so from the first build that ships
    the link guard the developer does operate a server the app contacts, and
    that server sees an IP address and the app's build number. Nothing about
    the user's links is sent -- but "no server" and "contacts nothing of ours"
    stop being true, in the privacy policy, in Data safety and on the About
    screen alike.

    The scanner check above cannot see this: the link guard reads its own
    string, jac_linkguard_lists_url, precisely so that it never touches the
    scanner backend. So it gets its own check, keyed on its own string, and
    like the scanner's it stops the release until the declarations are
    rewritten.
    """
    users = sorted(os.path.relpath(p, ROOT) for p in java_files()
                   if 'R.string.jac_linkguard_lists_url' in read(p))
    if not users:
        return
    url = string_value(os.path.join(RES, 'values', 'strings.xml'), 'jac_linkguard_lists_url') or ''
    host = re.sub(r'^[a-z]+://', '', url.strip()).split('/')[0]
    check('link guard list URL names a host', bool(host),
          'jac_linkguard_lists_url is missing or empty in values/strings.xml')
    if not host:
        return
    todo = ('Do all three, then record it: (1) docs/privacy-policy.md and the site copy -- say the app '
            'downloads a public list from %s and what that request reveals, and drop "operates no '
            'server"; (2) Play Console Data safety; (3) docs/play-console.md -- '
            'data.linkguard_list_download = declared.' % host)
    declared = declared_answers().get('data.linkguard_list_download')
    check('link guard list download is declared (data.linkguard_list_download = declared)',
          declared == 'declared',
          '%s download(s) the list from %s, but docs/play-console.md says %r. %s'
          % (', '.join(users), host, declared, todo))
    policy = os.path.join(ROOT, 'docs', 'privacy-policy.md')
    body = read(policy) if os.path.exists(policy) else ''
    check('privacy policy names the list server (%s)' % host, host in body,
          'docs/privacy-policy.md does not mention %s. %s' % (host, todo))
    check('privacy policy no longer says the developer operates no server',
          'operates no server' not in body,
          'docs/privacy-policy.md still says "operates no server", and the app now downloads from %s. %s'
          % (host, todo))
    for path in value_files():
        about = string_value(path, 'jac_about_scanner_body')
        if about is None:
            continue
        check('About text names the list server (%s)' % os.path.basename(os.path.dirname(path)),
              host in about,
              'jac_about_scanner_body in %s does not mention %s' % (os.path.relpath(path, ROOT), host))


def app_package():
    match = re.search(r'^APP_PACKAGE\s*=\s*(\S+)', read(os.path.join(ROOT, 'gradle.properties')), re.M)
    return match.group(1) if match else None


def check_push_disclosure_matches_code():
    """Push through our own Firebase project needs declarations that say so.

    The google-services plugin is applied only when TMessagesProj_App's
    google-services.json is ours -- it names our package (see build.gradle);
    the file upstream ships there is Telegram's and is ignored. Until then no
    push token is registered and nothing goes to Google. From the first build
    with our file, Firebase Cloud Messaging gets an installation id and a push
    token for the device, and Telegram's servers send each notification, end-
    to-end encrypted, through Google. That is a third party the privacy policy
    must name, and Data safety must cover the device id.

    Without our file it only warns: a messenger that is silent while closed is
    a product problem, not a policy one, but it should not ship unnoticed.
    """
    package = app_package()
    path = os.path.join(ROOT, 'TMessagesProj_App', 'google-services.json')
    ours = bool(package) and os.path.exists(path) and ('"%s"' % package) in read(path)
    if not ours:
        warn('push notifications are off: TMessagesProj_App/google-services.json is not ours',
             'messages arrive only while the app is open. See docs/push-notifications.md')
        return
    todo = ('Do all three, then record it: (1) docs/privacy-policy.md and the site copy -- name Google '
            'Firebase Cloud Messaging as the push channel and what it receives; (2) Play Console Data '
            'safety -- Device or other IDs, collected, for app functionality; (3) docs/play-console.md -- '
            'data.push_fcm = declared.')
    check('push through Firebase is declared (data.push_fcm = declared)',
          declared_answers().get('data.push_fcm') == 'declared',
          'google-services.json names %s, so this build registers for FCM. %s' % (package, todo))
    policy = os.path.join(ROOT, 'docs', 'privacy-policy.md')
    body = read(policy) if os.path.exists(policy) else ''
    check('privacy policy names Firebase Cloud Messaging', 'Firebase Cloud Messaging' in body,
          'docs/privacy-policy.md does not mention it. %s' % todo)
    # A push that cold-starts the process needs the default FirebaseApp before
    # our code runs; removing the provider brings back silent notifications.
    check('FirebaseInitProvider is not removed from the manifest',
          not re.search(r'FirebaseInitProvider"[^>]*tools:node="remove"', read(MANIFEST), re.S),
          'TMessagesProj/src/main/AndroidManifest.xml removes it -- see the comment there')


def check_age_limit_matches_policy():
    """The target audience and the privacy policy name the same minimum age.

    The policy said Telegram's minimum age applies, and Telegram states none.
    """
    minimum = declared_answers().get('audience.min_age')
    policy = os.path.join(ROOT, 'docs', 'privacy-policy.md')
    body = read(policy) if os.path.exists(policy) else ''
    section = re.search(r'^### \d+\. Children and age limits\n(.*?)(?=^### |\Z)', body, re.M | re.S)
    # The section also names 16 (an EU consent age), so match the sentence that
    # sets the minimum, not any number.
    check('privacy policy states the target audience\'s minimum age',
          minimum and section and re.search(r'aged\s+%s\s+and\s+over' % re.escape(minimum), section.group(1)),
          'audience.min_age is %s in docs/play-console.md; the English "Children and age '
          'limits" section of docs/privacy-policy.md is %s' % (minimum, 'there' if section else 'missing'))


def main():
    print('Humogram store-readiness checks\n')
    for group, fn in [
        ('impersonation', check_no_telegram_wordmark_drawables),
        ('impersonation', check_identity_bypasses_cloud_strings),
        ('impersonation', check_app_name_is_ours),
        ('impersonation', check_single_launcher_identity),
        ('impersonation', check_splash_is_ours),
        ('impersonation', check_notification_icon_replaced),
        ('permissions', check_restricted_permissions),
        ('honesty', check_no_unevidenced_virus_claim),
        ('disclosure', check_disclosure_reachable),
        ('disclosure', check_privacy_policy_names_this_app),
        ('privacy', check_no_user_content_in_release_logs),
        ('packaging', check_release_ships_every_abi),
        ('packaging', check_play_api_and_billing),
        ('branding', check_no_brand_leftovers),
        ('declarations', check_content_rating_matches_code),
        ('declarations', check_scanner_disclosure_matches_code),
        ('declarations', check_linkguard_disclosure_matches_code),
        ('declarations', check_push_disclosure_matches_code),
        ('declarations', check_age_limit_matches_policy),
    ]:
        fn()

    print('\n%d checks, %d failed, %d warnings' % (checks_run, len(failures), len(warnings)))
    if warnings:
        print('\nComing up:')
        for name in warnings:
            print('  - %s' % name)
    if failures:
        print('\nFailed:')
        for name in failures:
            print('  - %s' % name)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
