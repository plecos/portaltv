// Signs and checks the extension catalog with Ed25519. One file, no libraries; needs a JDK (15 or newer):
//
//   java scripts/CatalogSign.java keygen  <name>                       writes <name>.private and <name>.public
//   java scripts/CatalogSign.java stamp   catalog.json [--serial N] [--days 180]   sets serial, updated and expires
//   java scripts/CatalogSign.java sign    <name>.private <key-id> catalog.json [catalog.json.sig]
//   java scripts/CatalogSign.java verify  <name>.public  catalog.json [catalog.json.sig]
//
// The private key is the only thing that can publish a catalog Portal will accept. Keep it offline (not in a repository, not
// in CI). The public key (one line of base64) is what goes into Portal, in CatalogTrust.RELEASE_KEYS.
//
// The signature file is JSON: {"key_id": "...", "signature": "<base64 of the Ed25519 signature over the exact bytes of catalog.json>"}.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

public class CatalogSign {

    // The fixed start of an Ed25519 public key in X.509 form; the 32 key bytes follow.
    private static final byte[] X509_PREFIX = hex("302a300506032b6570032100");

    public static void main(String[] a) throws Exception {
        if (a.length < 2) usage();
        switch (a[0]) {
            case "keygen" -> keygen(a[1]);
            case "stamp" -> stamp(a);
            case "sign" -> {
                if (a.length < 4) usage();
                sign(a[1], a[2], Path.of(a[3]), a.length > 4 ? Path.of(a[4]) : Path.of(a[3] + ".sig"));
            }
            case "verify" -> {
                if (a.length < 3) usage();
                verify(a[1], Path.of(a[2]), a.length > 3 ? Path.of(a[3]) : Path.of(a[2] + ".sig"));
            }
            default -> usage();
        }
    }

    // Sets the three fields Portal checks for freshness: serial (one more than before, or --serial), updated (today) and
    // expires (today plus --days, 180 by default, at most 365). Run it just before signing, every time the catalog is published.
    private static void stamp(String[] a) throws Exception {
        Path file = Path.of(a[1]);
        long serial = -1;
        int days = 180;
        for (int i = 2; i + 1 < a.length; i += 2) {
            if (a[i].equals("--serial")) serial = Long.parseLong(a[i + 1]);
            else if (a[i].equals("--days")) days = Integer.parseInt(a[i + 1]);
            else usage();
        }
        if (days < 1 || days > 365) { System.err.println("--days must be from 1 to 365 (Portal refuses a catalog that is good for longer)."); System.exit(2); }
        String text = Files.readString(file);
        if (serial < 0) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"serial\"\\s*:\\s*(\\d+)").matcher(text);
            serial = m.find() ? Long.parseLong(m.group(1)) + 1 : 1;
        }
        text = text.replaceAll("\"(serial|updated|expires)\"\\s*:\\s*(\"[^\"]*\"|\\d+)\\s*,?\\s*", "");
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        int open = text.indexOf('{');
        if (open < 0) { System.err.println("That isn't a JSON object."); System.exit(2); }
        String fields = "\n  \"serial\": " + serial + ",\n  \"updated\": \"" + today + "\",\n  \"expires\": \"" + today.plusDays(days) + "\",";
        Files.writeString(file, text.substring(0, open + 1) + fields + text.substring(open + 1));
        System.out.println("Stamped " + file + ": serial " + serial + ", updated " + today + ", expires " + today.plusDays(days));
    }

    private static void keygen(String name) throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = pair.getPublic().getEncoded();
        byte[] raw = Arrays.copyOfRange(encoded, X509_PREFIX.length, encoded.length);
        Files.writeString(Path.of(name + ".private"), Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        Files.writeString(Path.of(name + ".public"), Base64.getEncoder().encodeToString(raw) + "\n");
        System.out.println("Wrote " + name + ".private (keep it offline) and " + name + ".public:");
        System.out.println(Base64.getEncoder().encodeToString(raw));
    }

    private static void sign(String privateFile, String keyId, Path catalog, Path out) throws Exception {
        // Portal raises its clock floor to a catalog's "updated" day and never lowers it, so a mistyped future date would pause
        // installs on every TV for good. Refuse to sign one more than a day ahead.
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"updated\"\\s*:\\s*\"(\\d{4}-\\d{2}-\\d{2})\"").matcher(Files.readString(catalog));
        if (m.find() && java.time.LocalDate.parse(m.group(1)).isAfter(java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1))) {
            System.err.println("Refusing to sign: \"updated\" is " + m.group(1) + ", which is in the future. Run stamp first.");
            System.exit(1);
        }
        PrivateKey key = KeyFactory.getInstance("Ed25519")
            .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(Files.readString(Path.of(privateFile)).trim())));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(Files.readAllBytes(catalog));
        String json = "{\"key_id\": \"" + keyId + "\", \"signature\": \"" + Base64.getEncoder().encodeToString(s.sign()) + "\"}\n";
        Files.writeString(out, json, StandardCharsets.UTF_8);
        System.out.println("Signed " + catalog + " as " + keyId + " -> " + out);
    }

    private static void verify(String publicFileOrKey, Path catalog, Path sig) throws Exception {
        Path p = Path.of(publicFileOrKey);
        String b64 = (Files.isRegularFile(p) ? Files.readString(p) : publicFileOrKey).trim();
        byte[] raw = Base64.getDecoder().decode(b64);
        byte[] x509 = new byte[X509_PREFIX.length + raw.length];
        System.arraycopy(X509_PREFIX, 0, x509, 0, X509_PREFIX.length);
        System.arraycopy(raw, 0, x509, X509_PREFIX.length, raw.length);
        PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
        String json = Files.readString(sig);
        String value = json.replaceAll("(?s).*\"signature\"\\s*:\\s*\"([^\"]*)\".*", "$1");
        Signature s = Signature.getInstance("Ed25519");
        s.initVerify(key);
        s.update(Files.readAllBytes(catalog));
        if (s.verify(Base64.getDecoder().decode(value))) System.out.println("Signature is valid.");
        else { System.out.println("Signature is NOT valid."); System.exit(1); }
    }

    private static void usage() {
        System.err.println("usage: java scripts/CatalogSign.java keygen <name> | sign <private> <key-id> <catalog.json> [out] | verify <public> <catalog.json> [sig]");
        System.exit(2);
    }

    private static byte[] hex(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
