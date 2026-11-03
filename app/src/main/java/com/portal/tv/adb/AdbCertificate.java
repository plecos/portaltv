package com.portal.tv.adb;

import java.security.KeyPair;
import java.security.cert.Certificate;
import java.util.Date;
import java.util.Random;
import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateExtensions;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.KeyIdentifier;
import android.sun.security.x509.PrivateKeyUsageExtension;
import android.sun.security.x509.SubjectKeyIdentifierExtension;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

/**
 * Makes the self-signed certificate adb pairing asks for. It is Java because the library that provides these
 * classes (sun-security-android) is written for Java callers, and this keeps its odd API out of the Kotlin.
 */
final class AdbCertificate {
    private AdbCertificate() {}

    static Certificate create(KeyPair pair) throws Exception {
        String algorithm = "SHA512withRSA";
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 86_400_000);
        X500Name name = new X500Name("CN=Portal");
        CertificateExtensions extensions = new CertificateExtensions();
        extensions.set("SubjectKeyIdentifier",
                new SubjectKeyIdentifierExtension(new KeyIdentifier(pair.getPublic()).getIdentifier()));
        extensions.set("PrivateKeyUsage", new PrivateKeyUsageExtension(notBefore, notAfter));
        X509CertInfo info = new X509CertInfo();
        info.set("version", new CertificateVersion(2));
        info.set("serialNumber", new CertificateSerialNumber(new Random().nextInt() & Integer.MAX_VALUE));
        info.set("algorithmID", new CertificateAlgorithmId(AlgorithmId.get(algorithm)));
        info.set("subject", new CertificateSubjectName(name));
        info.set("key", new CertificateX509Key(pair.getPublic()));
        info.set("validity", new CertificateValidity(notBefore, notAfter));
        info.set("issuer", new CertificateIssuerName(name));
        info.set("extensions", extensions);
        X509CertImpl cert = new X509CertImpl(info);
        cert.sign(pair.getPrivate(), algorithm);
        return cert;
    }
}
