package com.bhawana.lms.support;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Generates ephemeral self-signed TLS material for Testcontainers tests (M10). The
 * certificate/key pair is created at test runtime into a temp directory — no private key
 * material is ever committed to the repository (the gitleaks scan is right to refuse that).
 *
 * <p>A single self-signed certificate doubles as the server's own trust anchor: it is
 * marked {@code CA:TRUE} with SANs for {@code localhost}/{@code 127.0.0.1}, so Redis can
 * use it as {@code --tls-ca-cert-file} and the JDK trust manager can anchor on it directly.
 */
public final class TestTlsMaterials {

    private TestTlsMaterials() {
    }

    /** A generated PEM pair on disk; {@link #caCertificate()} is what a client/server trusts. */
    public record Generated(Path certificate, Path privateKey) {

        /** The self-signed cert anchors its own chain — the CA file IS the server cert. */
        public Path caCertificate() {
            return certificate;
        }
    }

    /**
     * Generates a 2048-bit RSA keypair and a self-signed {@code CN=localhost} certificate
     * (SANs: {@code localhost}, {@code 127.0.0.1}; validity ~10 years) into a fresh temp
     * directory, registered for deletion at JVM exit.
     */
    public static Generated selfSignedLocalhost() {
        try {
            Path directory = Files.createTempDirectory("lms-test-tls-");
            directory.toFile().deleteOnExit();

            KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
            keyPairGenerator.initialize(2048);
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            Instant now = Instant.now();
            X500Name name = new X500Name("CN=localhost");
            X509v3CertificateBuilder builder = new X509v3CertificateBuilder(
                    name,
                    BigInteger.valueOf(now.toEpochMilli()),
                    Date.from(now.minus(Duration.ofDays(1))),
                    Date.from(now.plus(Duration.ofDays(3650))),
                    name,
                    SubjectPublicKeyInfo.getInstance(keyPair.getPublic().getEncoded()));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(
                    KeyUsage.digitalSignature | KeyUsage.keyEncipherment | KeyUsage.keyCertSign));
            builder.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(
                    new GeneralName[]{
                            new GeneralName(GeneralName.dNSName, "localhost"),
                            new GeneralName(GeneralName.iPAddress, "127.0.0.1")
                    }));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(keyPair.getPrivate());
            X509CertificateHolder holder = builder.build(signer);
            X509Certificate certificate = new JcaX509CertificateConverter()
                    .getCertificate(holder);

            Path certPath = writePem(directory.resolve("redis.crt"), certificate);
            Path keyPath = writePem(directory.resolve("redis.key"), keyPair.getPrivate());
            // The private key is 0600 — enough for the container's root-owned redis-server
            // to read via the bind copy, and visibly not a real credential.
            Files.setPosixFilePermissions(keyPath, PosixFilePermissions.fromString("rw-------"));
            certPath.toFile().deleteOnExit();
            keyPath.toFile().deleteOnExit();
            return new Generated(certPath, keyPath);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate test TLS material.", exception);
        }
    }

    private static Path writePem(Path path, Object pemObject) throws Exception {
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(path))) {
            writer.writeObject(pemObject);
        }
        return path;
    }
}
