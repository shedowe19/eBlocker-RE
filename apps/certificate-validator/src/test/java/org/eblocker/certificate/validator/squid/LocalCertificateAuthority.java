/*
 * Copyright 2020 eBlocker Open Source UG (haftungsbeschraenkt)
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be
 * approved by the European Commission - subsequent versions of the EUPL
 * (the "License"); You may not use this work except in compliance with
 * the License. You may obtain a copy of the License at:
 *
 *   https://joinup.ec.europa.eu/page/eupl-text-11-12
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */
package org.eblocker.certificate.validator.squid;

import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.CRLDistPoint;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.DistributionPoint;
import org.bouncycastle.asn1.x509.DistributionPointName;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509v2CRLBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

/** Fresh certificates and a signed CRL served only on loopback; no external CA or network. */
final class LocalCertificateAuthority {
    static final LocalCertificateAuthority INSTANCE = create();

    final X509Certificate root;
    final X509Certificate valid;
    final X509Certificate expired;
    final X509Certificate revoked;
    final Path rootPath;

    private LocalCertificateAuthority() throws Exception {
        Instant now = Instant.now();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair issuerKey = generator.generateKeyPair();
        KeyPair leafKey = generator.generateKeyPair();
        X500Name issuer = new X500Name("CN=eBlocker ephemeral test CA");
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey.getPrivate());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String crlUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/issuer.crl";
        root = certificate(issuer, issuer, issuerKey, signer, 1, now.minus(1, ChronoUnit.DAYS),
                now.plus(2, ChronoUnit.DAYS), true, crlUrl);
        X500Name subject = new X500Name("CN=validator.test");
        valid = certificate(issuer, subject, leafKey, signer, 2, now.minus(1, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS), false, crlUrl);
        expired = certificate(issuer, subject, leafKey, signer, 3, now.minus(2, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.DAYS), false, crlUrl);
        revoked = certificate(issuer, subject, leafKey, signer, 4, now.minus(1, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS), false, crlUrl);

        X509v2CRLBuilder crl = new X509v2CRLBuilder(issuer, Date.from(now.minus(1, ChronoUnit.HOURS)));
        crl.setNextUpdate(Date.from(now.plus(1, ChronoUnit.DAYS)));
        crl.addCRLEntry(revoked.getSerialNumber(), Date.from(now.minus(2, ChronoUnit.HOURS)), CRLReason.keyCompromise);
        byte[] encodedCrl = crl.build(signer).getEncoded();
        server.createContext("/issuer.crl", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/pkix-crl");
            exchange.sendResponseHeaders(200, encodedCrl.length);
            try (var out = exchange.getResponseBody()) {
                out.write(encodedCrl);
            }
        });
        var executor = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "test-crl-response");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        // The HTTP dispatcher inherits daemon status from the thread that starts it.
        FutureTask<Void> start = new FutureTask<>(server::start, null);
        Thread starter = new Thread(start, "test-crl-start");
        starter.setDaemon(true);
        starter.start();
        start.get();

        Path directory = Files.createTempDirectory("eblocker-test-ca-");
        rootPath = directory.resolve("root.pem");
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(rootPath))) {
            writer.writeObject(root);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop(0);
            executor.shutdownNow();
            try {
                Files.deleteIfExists(directory.resolve("crl.cache"));
                Files.deleteIfExists(rootPath);
                Files.deleteIfExists(directory);
            } catch (java.io.IOException ignored) {
                // Temporary files are also cleaned up by the test environment.
            }
        }, "test-ca-cleanup"));
    }

    private static X509Certificate certificate(X500Name issuer, X500Name subject, KeyPair key,
            ContentSigner signer, long serial, Instant from, Instant to, boolean ca, String crlUrl) throws Exception {
        var builder = new JcaX509v3CertificateBuilder(issuer, BigInteger.valueOf(serial), Date.from(from),
                Date.from(to), subject, key.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(ca
                ? KeyUsage.keyCertSign | KeyUsage.cRLSign : KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        if (!ca) {
            builder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, "validator.test")));
            var name = new DistributionPointName(new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, crlUrl)));
            builder.addExtension(Extension.cRLDistributionPoints, false,
                    new CRLDistPoint(new DistributionPoint[]{new DistributionPoint(name, null, null)}));
        }
        return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
    }

    private static LocalCertificateAuthority create() {
        try {
            return new LocalCertificateAuthority();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
