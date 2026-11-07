// Signs and checks the extension catalog with Ed25519. One file, no libraries; needs a JDK (15 or newer):
//
//   java scripts/CatalogSign.java keygen  <name>                       writes <name>.private and <name>.public
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
