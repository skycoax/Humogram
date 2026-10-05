package uz.jac.secure.android;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Enumeration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Who the list download trusts on Android older than 7.1.1.
 *
 * <p>The list host serves a Let's Encrypt certificate, and Let's Encrypt
 * chains end at the ISRG roots. ISRG Root X1 entered Android's system store in
 * 7.1.1 (X2 later still); the cross-signature from an older CA that used to
 * carry older phones expired in 2024 and is no longer served. So on Android
 * 5.0 to 7.0 the TLS handshake with the list host fails on every attempt, the
 * phone never receives a list, and it stays on the bundled one for as long as
 * the app is installed. A system store cannot be fixed from an app, and
 * {@code network-security-config} — which could add a trust anchor — is not
 * read before Android 7.0 at all.
 *
 * <p>So for this one connection, on those versions only, {@link LinkGuard}
 * uses a socket factory that trusts what the phone's system store trusts
 * <em>plus</em> the two ISRG roots below. It is set on the connection object
 * and nowhere else: it is never installed as a default, and no other request
 * the app makes — Telegram's, the browser's, the scanner's — sees it. From
 * 7.1.1 on nothing here is used and the platform's own trust, with the
 * network security config, applies as before.
 *
 * <h3>What this does and does not widen</h3>
 *
 * Two more roots, both of them in every current system store, for one host.
 * Only the system half of the phone's store is copied: CAs the user (or an
 * MDM profile) installed are left out, which is what
 * {@code jac_network_security_config.xml} asks for on the versions that read
 * it. The hostname is still checked by the platform's default verifier; this
 * class does not touch that. And transport is all it restores: the list is
 * accepted only if its ECDSA signature verifies against the keys compiled
 * into the app, so nobody who could abuse a trust anchor could forge a list
 * with it.
 *
 * <h3>The certificates</h3>
 *
 * The self-signed roots as Let's Encrypt publishes them
 * (letsencrypt.org/certs/isrgrootx1.pem and isrg-root-x2.pem). SHA-256
 * fingerprints, checked against the published values when they were added:
 *
 * <pre>
 *   ISRG Root X1  96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:
 *                 CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6   (RSA 4096, until 2035-06-04)
 *   ISRG Root X2  69:72:9B:8E:15:A8:6E:FC:17:7A:57:AF:B7:17:1D:FC:
 *                 64:AD:D2:8C:2F:CA:8C:F1:50:7E:34:45:3C:CB:14:70   (ECDSA P-384, until 2040-09-17)
 * </pre>
 *
 * Both, not one and a spare. As the host served it in September 2026 the
 * chain runs leaf ← YE1 ← Root YE ← ISRG Root X2 ← ISRG Root X1, the last two
 * links being cross-signatures: a phone that holds X2 stops there, one that
 * holds only X1 goes one further. Which of those cross-signatures Let's
 * Encrypt serves, and for how long, is theirs to decide; with both roots here
 * either chain still ends at an anchor on these phones without an app
 * release. (A chain that one day ends at Root YE or Root YR with no
 * cross-signature at all would need those roots added here.)
 */
final class LinkGuardTrust {

    private static final String ISRG_ROOT_X1 = ""
            + "-----BEGIN CERTIFICATE-----\n"
            + "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n"
            + "TzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2Vh\n"
            + "cmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4\n"
            + "WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJu\n"
            + "ZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBY\n"
            + "MTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygc\n"
            + "h77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+\n"
            + "0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6U\n"
            + "A5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sW\n"
            + "T8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyH\n"
            + "B5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UC\n"
            + "B5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUv\n"
            + "KBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWn\n"
            + "OlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTn\n"
            + "jh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbw\n"
            + "qHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CI\n"
            + "rU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNV\n"
            + "HRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkq\n"
            + "hkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZL\n"
            + "ubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ\n"
            + "3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KK\n"
            + "NFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5\n"
            + "ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7Ur\n"
            + "TkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdC\n"
            + "jNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVc\n"
            + "oyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq\n"
            + "4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPA\n"
            + "mRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57d\n"
            + "emyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=\n"
            + "-----END CERTIFICATE-----\n";

    private static final String ISRG_ROOT_X2 = ""
            + "-----BEGIN CERTIFICATE-----\n"
            + "MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQsw\n"
            + "CQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2gg\n"
            + "R3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00\n"
            + "MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBT\n"
            + "ZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYw\n"
            + "EAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW\n"
            + "+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9\n"
            + "ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T\n"
            + "AQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZI\n"
            + "zj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdW\n"
            + "tL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1\n"
            + "/q4AaOeMSQ+2b1tbFfLn\n"
            + "-----END CERTIFICATE-----\n";

    /** The Android keystore that lists the CAs the device trusts. */
    private static final String SYSTEM_STORE = "AndroidCAStore";
    /** Its aliases are {@code system:<hash>} for the ROM's CAs and {@code user:<hash>} for installed ones. */
    private static final String SYSTEM_ALIAS_PREFIX = "system:";

    /** Built on first use, on the link guard's worker; stays null if it cannot be built. */
    private static volatile SSLSocketFactory factory;

    private LinkGuardTrust() {
    }

    /**
     * The socket factory for the list download, or null if it could not be
     * built — the caller then connects with the platform's own, which on
     * these versions fails the handshake and is reported as such.
     */
    static SSLSocketFactory socketFactory() {
        SSLSocketFactory built = factory;
        if (built == null) {
            try {
                TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                trust.init(anchors(systemStore()));
                SSLContext context = SSLContext.getInstance("TLS");
                context.init(null, trust.getTrustManagers(), null);
                built = context.getSocketFactory();
                factory = built;
            } catch (Throwable t) {
                android.util.Log.w("jac", "link guard: trust for old Android not built: " + t.getClass().getSimpleName());
            }
        }
        return built;
    }

    /**
     * The trust anchors: every system CA in {@code system}, and the two ISRG
     * roots. With no system store to read, the ISRG roots alone — which is
     * still everything the list host needs.
     */
    static KeyStore anchors(KeyStore system) throws Exception {
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        anchors.load(null, null);
        if (system != null) {
            for (Enumeration<String> aliases = system.aliases(); aliases.hasMoreElements(); ) {
                String alias = aliases.nextElement();
                if (alias == null || !alias.startsWith(SYSTEM_ALIAS_PREFIX)) {
                    continue;
                }
                Certificate certificate = system.getCertificate(alias);
                if (certificate != null) {
                    anchors.setCertificateEntry(alias, certificate);
                }
            }
        }
        CertificateFactory x509 = CertificateFactory.getInstance("X.509");
        anchors.setCertificateEntry("isrg-root-x1", x509.generateCertificate(
                new ByteArrayInputStream(ISRG_ROOT_X1.getBytes("US-ASCII"))));
        anchors.setCertificateEntry("isrg-root-x2", x509.generateCertificate(
                new ByteArrayInputStream(ISRG_ROOT_X2.getBytes("US-ASCII"))));
        return anchors;
    }

    private static KeyStore systemStore() {
        try {
            KeyStore system = KeyStore.getInstance(SYSTEM_STORE);
            system.load(null, null);
            return system;
        } catch (Throwable t) {
            return null;
        }
    }
}
