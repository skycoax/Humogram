package uz.jac.secure.android;

import android.content.Context;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.telegram.messenger.R;

import uz.jac.secure.core.model.SignalCodes;
import uz.jac.secure.core.model.Verdict;

/**
 * Turns a scan state into the words, colour and glyph the user sees.
 *
 * One class, used by all three surfaces — the message bubble, the chat-list
 * row, the interstitial — so a verdict cannot be amber in one place and red in
 * another. Every string it produces comes from resources, in uz-Latn first.
 *
 * <h3>Choosing what to say</h3>
 *
 * A scan routinely produces six or seven findings. Showing all of them produces
 * a wall of text nobody reads, and showing an arbitrary one produces "unusually
 * long filename" on a counterfeit banking app. So findings are ranked by
 * {@link #PRIORITY} and exactly one is explained: the most specific thing we
 * know, in one sentence, naming the consequence rather than the mechanism.
 *
 * The order is by how much it should change what the user does, not by
 * severity. {@code APK_IMPERSONATION_CERT_MISMATCH} outranks
 * {@code APK_SMS_AND_ACCESSIBILITY} even though both are HIGH, because "this is
 * a counterfeit Click app" tells someone what to do and "this app can read your
 * SMS" is a property many real apps also have.
 */
public final class ScanUi {

    private ScanUi() {
    }

    /** What to draw, in every surface. */
    public static final class Presentation {
        public final JacIcons.Glyph glyph;
        public final int colour;
        public final String title;
        /** The one-sentence explanation. Null when the title says it all. */
        public final String body;
        /** Show the "Keep me safe" / "Open anyway" pair. */
        public final boolean actionable;
        /** Short form for the chat-list row; never more than a few words. */
        public final String chip;
        /**
         * Take over the whole bubble instead of sitting under the file row.
         *
         * Reserved for MALICIOUS, and rationed on purpose. Covering the
         * sender's own text is right when that text is the lure — "send this to
         * your family" — and wrong the moment it fires on something legitimate,
         * because then it covers a message the user wanted to read. A scale
         * whose top step is common is a scale with no top step.
         */
        public final boolean overlay;

        Presentation(JacIcons.Glyph glyph, int colour, String title, String body, boolean actionable, String chip) {
            this(glyph, colour, title, body, actionable, chip, false);
        }

        Presentation(JacIcons.Glyph glyph, int colour, String title, String body,
                     boolean actionable, String chip, boolean overlay) {
            this.glyph = glyph;
            this.colour = colour;
            this.title = title;
            this.body = body;
            this.actionable = actionable;
            this.chip = chip;
            this.overlay = overlay;
        }
    }

    /**
     * Findings, most decision-changing first.
     *
     * A code absent from this list still counts towards the verdict — the
     * policy engine, not this table, decides that — it just never becomes the
     * sentence we lead with.
     */
    private static final List<String> PRIORITY = Arrays.asList(
            SignalCodes.APK_IMPERSONATION_CERT_MISMATCH,
            SignalCodes.APK_IMPERSONATION_PACKAGE_LOOKALIKE,
            SignalCodes.APK_IMPERSONATION_LABEL,
            SignalCodes.RTL_OVERRIDE,
            SignalCodes.EXECUTABLE_CONTENT,
            SignalCodes.MAGIC_MISMATCH,
            SignalCodes.DOUBLE_EXTENSION,
            SignalCodes.CONFUSABLE_NAME,
            SignalCodes.APK_DANGEROUS_PERMISSIONS,
            SignalCodes.APK_EVASIVE_STRUCTURE,
            SignalCodes.APK_SMS_AND_ACCESSIBILITY,
            SignalCodes.APK_OVERLAY_COMBO,
            SignalCodes.APK_DEVICE_ADMIN,
            SignalCodes.OOXML_MACRO,
            SignalCodes.OOXML_REMOTE_TEMPLATE,
            SignalCodes.PDF_JAVASCRIPT,
            SignalCodes.PDF_LAUNCH,
            SignalCodes.PDF_OPENACTION,
            SignalCodes.ARCHIVE_BOMB_RATIO,
            SignalCodes.ARCHIVE_BOMB_SIZE,
            SignalCodes.ARCHIVE_PATH_TRAVERSAL,
            SignalCodes.ARCHIVE_EXECUTABLE_INSIDE,
            SignalCodes.APK_UNSIGNED,
            SignalCodes.SPACE_PADDING,
            SignalCodes.APK_INSTALLABLE,
            SignalCodes.DANGEROUS_EXTENSION
    );

    /**
     * Code to sentence, and how many args that sentence consumes.
     *
     * The arity is the contract with {@code jacsecure-core}: a template wanting
     * two args on a finding that carries none would throw at format time, deep
     * inside a draw pass, so {@link #explain} checks before formatting and
     * falls back rather than crashing a message list.
     */
    private static final Map<String, Template> TEMPLATES = new HashMap<>();

    private static final class Template {
        final int stringRes;
        final int argCount;

        Template(int stringRes, int argCount) {
            this.stringRes = stringRes;
            this.argCount = argCount;
        }
    }

    static {
        TEMPLATES.put(SignalCodes.APK_IMPERSONATION_CERT_MISMATCH, new Template(R.string.jac_reason_fake_bank, 1));
        TEMPLATES.put(SignalCodes.APK_IMPERSONATION_PACKAGE_LOOKALIKE, new Template(R.string.jac_reason_fake_bank, 1));
        TEMPLATES.put(SignalCodes.APK_IMPERSONATION_LABEL, new Template(R.string.jac_reason_impersonation_label, 1));
        TEMPLATES.put(SignalCodes.RTL_OVERRIDE, new Template(R.string.jac_reason_rtl, 0));
        TEMPLATES.put(SignalCodes.BIDI_CONTROL, new Template(R.string.jac_reason_rtl, 0));
        TEMPLATES.put(SignalCodes.EXECUTABLE_CONTENT, new Template(R.string.jac_reason_magic_mismatch, 2));
        TEMPLATES.put(SignalCodes.MAGIC_MISMATCH, new Template(R.string.jac_reason_magic_mismatch, 2));
        TEMPLATES.put(SignalCodes.DOUBLE_EXTENSION, new Template(R.string.jac_reason_double_ext, 0));
        TEMPLATES.put(SignalCodes.CONFUSABLE_NAME, new Template(R.string.jac_reason_confusable_name, 1));
        TEMPLATES.put(SignalCodes.APK_DANGEROUS_PERMISSIONS, new Template(R.string.jac_reason_sms_accessibility, 0));
        TEMPLATES.put(SignalCodes.APK_SMS_AND_ACCESSIBILITY, new Template(R.string.jac_reason_sms_accessibility, 0));
        TEMPLATES.put(SignalCodes.APK_OVERLAY_COMBO, new Template(R.string.jac_reason_overlay, 0));
        TEMPLATES.put(SignalCodes.APK_DEVICE_ADMIN, new Template(R.string.jac_reason_device_admin, 0));
        TEMPLATES.put(SignalCodes.APK_UNSIGNED, new Template(R.string.jac_reason_unsigned, 0));
        TEMPLATES.put(SignalCodes.APK_INSTALLABLE, new Template(R.string.jac_reason_apk_installable, 0));
        // One sentence for both scanners: the device scan page words it too.
        TEMPLATES.put(SignalCodes.APK_EVASIVE_STRUCTURE, new Template(R.string.jac_devscan_reason_apk_evasive_structure, 0));
        TEMPLATES.put(SignalCodes.OOXML_MACRO, new Template(R.string.jac_reason_macro, 0));
        TEMPLATES.put(SignalCodes.OOXML_REMOTE_TEMPLATE, new Template(R.string.jac_reason_remote_template, 0));
        TEMPLATES.put(SignalCodes.PDF_JAVASCRIPT, new Template(R.string.jac_reason_macro, 0));
        TEMPLATES.put(SignalCodes.PDF_OPENACTION, new Template(R.string.jac_reason_macro, 0));
        TEMPLATES.put(SignalCodes.PDF_LAUNCH, new Template(R.string.jac_reason_pdf_launch, 0));
        TEMPLATES.put(SignalCodes.ARCHIVE_BOMB_RATIO, new Template(R.string.jac_reason_archive_bomb, 0));
        TEMPLATES.put(SignalCodes.ARCHIVE_BOMB_SIZE, new Template(R.string.jac_reason_archive_bomb, 0));
        TEMPLATES.put(SignalCodes.ARCHIVE_PATH_TRAVERSAL, new Template(R.string.jac_reason_archive_traversal, 0));
        TEMPLATES.put(SignalCodes.ARCHIVE_EXECUTABLE_INSIDE, new Template(R.string.jac_reason_archive_executable, 0));
        TEMPLATES.put(SignalCodes.SPACE_PADDING, new Template(R.string.jac_reason_space_padding, 0));
        TEMPLATES.put(SignalCodes.DANGEROUS_EXTENSION, new Template(R.string.jac_reason_dangerous_extension, 0));
    }

    public static Presentation present(Context context, ScanStateStore.State state) {
        if (state == null) {
            return null;
        }
        // Not checked yet, and it installs an app. "Nothing known about an
        // installer" is the state in which people lose their bank accounts,
        // and a blank bubble reads as approval — so unknown renders exactly
        // like dangerous until a scan says otherwise.
        if (state.unchecked) {
            // Red, not amber, and worded as a finding rather than a caution.
            // Product decision -- see ChatMessageCell#jacIsInstaller for what it
            // costs and why it was taken anyway.
            return new Presentation(
                    JacIcons.Glyph.VIRUS,
                    JacTheme.danger(context),
                    JacStrings.get(context, R.string.jac_unchecked_title),
                    JacStrings.get(context, R.string.jac_unchecked_body),
                    false,
                    JacStrings.get(context, R.string.jac_unchecked_title));
        }
        if (state.scanning) {
            // Danger red, not neutral. "Being checked" is not a verdict, and
            // the rule this product runs on is that the absence of a verdict IS
            // the dangerous condition: the file was suspect before the scan
            // started and stays suspect until the scan says otherwise. A file
            // that turned calm-grey the moment checking began would read as
            // progress towards safety — reassurance the scanner has not earned
            // yet. The spinner glyph still says work is happening; the colour
            // says do not touch it meanwhile.
            return new Presentation(
                    JacIcons.Glyph.SPINNER,
                    JacTheme.danger(context),
                    JacStrings.get(context, R.string.jac_scan_scanning),
                    null,
                    false,
                    JacStrings.get(context, R.string.jac_scan_scanning));
        }
        // A check that ran out of time is a warning, not a result. Rendering it
        // as the ordinary UNKNOWN ("checked, nothing known") would turn a
        // failure into reassurance.
        if (state.verdict == Verdict.UNKNOWN && state.detail != null && state.detail.contains("timed out")) {
            return new Presentation(
                    JacIcons.Glyph.WARNING,
                    JacTheme.warning(context),
                    JacStrings.get(context, R.string.jac_scan_timeout_title),
                    JacStrings.get(context, R.string.jac_scan_timeout_body),
                    false,
                    JacStrings.get(context, R.string.jac_scan_timeout_title));
        }

        ScanFinding lead = leadFinding(state);
        String explanation = explain(context, lead, state.detail);

        // Order matters: overridden is checked before the verdict, because an
        // overridden file is still MALICIOUS and must not render as a live
        // block with a "Keep me safe" button that no longer does anything.
        if (state.overridden) {
            return new Presentation(
                    JacIcons.Glyph.WARNING,
                    JacTheme.danger(context),
                    JacStrings.get(context, R.string.jac_scan_overridden),
                    explanation,
                    false,
                    JacStrings.get(context, R.string.jac_chip_dangerous));
        }

        switch (state.verdict) {
            case MALICIOUS:
                return new Presentation(
                        JacIcons.Glyph.BLOCK,
                        JacTheme.danger(context),
                        JacStrings.get(context, R.string.jac_scan_dangerous),
                        explanation,
                        // No buttons. Removed at the product owner's direction:
                        // a warning with an "open anyway" next to it reads as a
                        // choice, and offering the choice at all is what makes
                        // taking it feel normal. The verdict states what the
                        // file is and stops there.
                        false,
                        JacStrings.get(context, R.string.jac_chip_dangerous));

            case SUSPICIOUS:
                // An installable package gets the buttons even though it is not
                // quarantined.
                //
                // Without them the verdict is a label: it says "this installs an
                // app", the file remains one tap away, and the tap that opens it
                // is the same one the user was already reaching for. A sentence
                // the user reads on their way to doing the thing anyway is not a
                // barrier.
                //
                // The pair is what makes it a decision — "Keep me safe" is the
                // large, filled, default option, and continuing costs a
                // deliberate reach for the quieter one. We stop short of
                // quarantining: ScanPolicy reserves that for the unambiguous,
                // and a quarantine that fires on every APK teaches people to
                // click past it, which is worse than not having it.
                // Amber, not red. Red is reserved for what the scanner is sure
                // about — a malicious file, or an installer nobody has checked
                // yet — while SUSPICIOUS means "looks off, not proven bad". Two
                // colours the user can tell apart at a glance are worth more
                // than one loud one, and the distinction is exactly danger vs
                // caution.
                return new Presentation(
                        JacIcons.Glyph.WARNING,
                        JacTheme.warning(context),
                        suspiciousTitle(context, lead),
                        explanation,
                        false,
                        JacStrings.get(context, R.string.jac_chip_suspicious));

            case CLEAN:
                return new Presentation(
                        JacIcons.Glyph.CHECK,
                        JacTheme.success(context),
                        JacStrings.get(context, R.string.jac_scan_clean),
                        null,
                        false,
                        JacStrings.get(context, R.string.jac_chip_clean));

            case UNKNOWN:
            default:
                // "We checked here and asked nobody" is the normal outcome for
                // every safe file in a secret chat, and it is not the same
                // claim as "no source recognised this". Rendering them
                // identically would either overstate the first or alarm on the
                // second; they get different words and different glyphs.
                boolean localOnly = "local_only".equals(state.source);
                return new Presentation(
                        localOnly ? JacIcons.Glyph.DEVICE : JacIcons.Glyph.CHECK,
                        JacTheme.neutral(context),
                        JacStrings.get(context, localOnly ? R.string.jac_scan_local_only : R.string.jac_scan_unknown),
                        explanation,
                        false,
                        JacStrings.get(context, localOnly ? R.string.jac_chip_local : R.string.jac_chip_unknown));
        }
    }

    /**
     * A suspicious file gets a title describing the trick, not the verdict.
     *
     * "Suspicious file" says nothing actionable. "This file name hides its
     * type" is a sentence someone can check against the name they are looking
     * at, and it is what makes them not tap.
     */
    private static String suspiciousTitle(Context context, ScanFinding lead) {
        if (lead == null) {
            return JacStrings.get(context, R.string.jac_scan_suspicious);
        }
        if (SignalCodes.MAGIC_MISMATCH.equals(lead.code)
                || SignalCodes.EXECUTABLE_CONTENT.equals(lead.code)
                || SignalCodes.DOUBLE_EXTENSION.equals(lead.code)
                || SignalCodes.RTL_OVERRIDE.equals(lead.code)
                || SignalCodes.BIDI_CONTROL.equals(lead.code)
                || SignalCodes.SPACE_PADDING.equals(lead.code)) {
            return JacStrings.get(context, R.string.jac_scan_suspicious_masquerade);
        }
        if (SignalCodes.APK_INSTALLABLE.equals(lead.code)
                || SignalCodes.APK_DANGEROUS_PERMISSIONS.equals(lead.code)
                || SignalCodes.APK_SMS_AND_ACCESSIBILITY.equals(lead.code)
                || SignalCodes.APK_OVERLAY_COMBO.equals(lead.code)
                || SignalCodes.APK_DEVICE_ADMIN.equals(lead.code)) {
            return JacStrings.get(context, R.string.jac_scan_suspicious_installer);
        }
        if (SignalCodes.APK_IMPERSONATION_LABEL.equals(lead.code)) {
            return JacStrings.get(context, R.string.jac_scan_suspicious_impersonation);
        }
        return JacStrings.get(context, R.string.jac_scan_suspicious);
    }

    /**
     * Link templates, keyed by the technique name the scanner reports.
     *
     * Kept separate from {@link #TEMPLATES} because the two vocabularies are
     * separate: link codes are {@code BrandTechnique} names and URL flags,
     * file codes are {@code SignalCodes}. Merging them into one map would
     * invite a future collision between two enums that nothing keeps distinct.
     */
    private static final Map<String, Template> LINK_TEMPLATES = new HashMap<>();

    private static final List<String> LINK_PRIORITY = Arrays.asList(
            "HOMOGRAPH_SKELETON",
            "DIGIT_FOLD",
            "TYPO_DISTANCE",
            "SUBDOMAIN_SPOOF",
            "TLD_SWAP",
            "BRAND_IN_DOMAIN",
            "USERINFO_IN_URL",
            "BIDI_IN_URL",
            "RAW_IP",
            "UNPARSEABLE_URL"
    );

    static {
        LINK_TEMPLATES.put("HOMOGRAPH_SKELETON", new Template(R.string.jac_reason_homograph, 1));
        LINK_TEMPLATES.put("DIGIT_FOLD", new Template(R.string.jac_reason_homograph, 1));
        LINK_TEMPLATES.put("TYPO_DISTANCE", new Template(R.string.jac_reason_typo, 1));
        LINK_TEMPLATES.put("SUBDOMAIN_SPOOF", new Template(R.string.jac_reason_subdomain, 2));
        LINK_TEMPLATES.put("TLD_SWAP", new Template(R.string.jac_reason_tld_swap, 1));
        LINK_TEMPLATES.put("BRAND_IN_DOMAIN", new Template(R.string.jac_reason_brand_in_domain, 1));
        LINK_TEMPLATES.put("USERINFO_IN_URL", new Template(R.string.jac_reason_userinfo, 0));
        LINK_TEMPLATES.put("BIDI_IN_URL", new Template(R.string.jac_reason_userinfo, 0));
        LINK_TEMPLATES.put("RAW_IP", new Template(R.string.jac_reason_raw_ip, 0));
        LINK_TEMPLATES.put("UNPARSEABLE_URL", new Template(R.string.jac_reason_userinfo, 0));
        // The server's own verdicts arrive as signal strings alongside the
        // structural ones, and a blocklist hit is the most certain thing we can
        // say about a URL — so it is here even though nothing local emits it.
        LINK_TEMPLATES.put("blocklist", new Template(R.string.jac_reason_blocklist, 0));
    }

    /**
     * The sentence for the interstitial.
     *
     * @param signalCodes the verdict's signals, in the order the scanner
     *                    produced them
     * @param args        args of the first signal that has any; the scanner
     *                    attaches them to the brand-technique signal, which is
     *                    also the only link signal whose copy takes a variable
     * @param fallback    the scanner's English detail
     */
    public static String explainLink(Context context, String[] signalCodes, String[] args, String fallback) {
        if (signalCodes == null) {
            return fallback;
        }
        for (String wanted : LINK_PRIORITY) {
            for (String code : signalCodes) {
                if (wanted.equals(code)) {
                    return explain(context, new ScanFinding(code, args), fallback, LINK_TEMPLATES);
                }
            }
        }
        // Anything the server sent that we do have copy for, in its own order.
        for (String code : signalCodes) {
            if (LINK_TEMPLATES.containsKey(code)) {
                return explain(context, new ScanFinding(code, args), fallback, LINK_TEMPLATES);
            }
        }
        return fallback;
    }

    static ScanFinding leadFinding(ScanStateStore.State state) {
        for (String code : PRIORITY) {
            ScanFinding found = state.find(code);
            if (found != null) {
                return found;
            }
        }
        return state.findings.isEmpty() ? null : state.findings.get(0);
    }

    /**
     * The one sentence.
     *
     * Falls back to the core's English {@code detail} only when a code has no
     * template — which happens when the core gains a signal before the strings
     * catch up. English is a poor outcome; a blank verdict block is a worse
     * one, and a crash in {@code onDraw} is the worst.
     */
    static String explain(Context context, ScanFinding finding, String fallbackDetail) {
        return explain(context, finding, fallbackDetail, TEMPLATES);
    }

    private static String explain(Context context, ScanFinding finding, String fallbackDetail, Map<String, Template> templates) {
        if (finding == null) {
            return fallbackDetail;
        }
        Template template = templates.get(finding.code);
        if (template == null) {
            return fallbackDetail;
        }
        if (template.argCount == 0) {
            return JacStrings.get(context, template.stringRes);
        }
        if (finding.args.length < template.argCount) {
            return fallbackDetail;
        }
        Object[] args = new Object[template.argCount];
        System.arraycopy(finding.args, 0, args, 0, template.argCount);
        return JacStrings.get(context, template.stringRes, args);
    }
}
