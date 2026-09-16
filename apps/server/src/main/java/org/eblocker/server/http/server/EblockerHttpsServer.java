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
package org.eblocker.server.http.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import io.netty.channel.Channel;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.handler.ssl.SslContext;
import org.eblocker.server.common.data.IpAddressModule;
import org.eblocker.server.common.data.systemstatus.SubSystem;
import org.eblocker.server.common.exceptions.ServiceNotAvailableException;
import org.eblocker.server.common.network.BaseURLs;
import org.eblocker.server.common.startup.SubSystemInit;
import org.eblocker.server.common.startup.SubSystemService;
import org.eblocker.server.common.startup.SubSystemShutdown;
import org.eblocker.server.common.status.StartupStatusReporter;
import org.eblocker.server.http.exceptions.restexpress.ServiceNotAvailableServiceException;
import org.eblocker.server.http.server.routes.EblockerRoutes;
import org.eblocker.server.http.security.DashboardAuthorizationProcessor;
import org.eblocker.server.http.security.SecurityProcessor;
import org.restexpress.Request;
import org.restexpress.RestExpress;
import org.restexpress.pipeline.Preprocessor;
import org.restexpress.response.RawResponseWrapper;
import org.restexpress.serialization.NullSerializationProvider;
import org.restexpress.serialization.SerializationProvider;
import org.restexpress.serialization.json.JacksonJsonProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;

/**
 * This RestExpress server provides a REST JSON API for the toolbar
 * and admin interface.
 * <p>
 * It also serves static files.
 * <p>
 * The server responds to both http and https requests. If setting up the SSL context
 * has failed, however, only http requests are processed. This allows the user to see
 * error logs and probably reset the unit to a usable state.
 */
@Singleton
@SubSystemService(SubSystem.HTTP_SERVER)
public class EblockerHttpsServer implements Preprocessor {
    private static final Logger log = LoggerFactory.getLogger(EblockerHttpsServer.class);
    private static final Logger STATUS = LoggerFactory.getLogger("STATUS");

    private final RestExpress server;
    private final int httpPort;
    private final int httpsPort;

    private final SSLContextHandler sslContextHandler;
    private final StartupStatusReporter startupStatusReporter;
    private final BaseURLs baseUrls;

    private Channel httpsChannel;

    @Inject
    public EblockerHttpsServer(@Named("httpPort") int httpPort,
                               @Named("httpsPort") int httpsPort,
                               @Named("http.server.useSystemOut") boolean useSystemOut,
                               @Named("http.server.maxContentSize") int maxContentSize,

                               SecurityProcessor securityProcessor,
                               DashboardAuthorizationProcessor dashboardAuthorizationProcessor,
                               StartupStatusReporter startupStatusReporter,
                               BaseURLs baseUrls,

                               SSLContextHandler sslContextHandler,
                               EblockerRoutes routes
    ) {
        // Set up SerializationProvider to make sure special characters like "&"
        // are transported to the frontend without encoding
        // Adding processors mimicks the functionality of DefaultSerializationProvider
        SerializationProvider serializationProvider = new NullSerializationProvider();
        serializationProvider.add(new JacksonJsonProcessor(false) {
            @Override
            protected void initializeMapper(ObjectMapper mapper) {
                super.initializeMapper(mapper);
                mapper.registerModule(new JavaTimeModule());
                mapper.registerModule(new IpAddressModule());
                mapper.enable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            }
        }, new RawResponseWrapper(), true);

        RestExpress.setDefaultSerializationProvider(serializationProvider);
        server = new RestExpress()
                .setUseSystemOut(useSystemOut)
                .addPreprocessor(this)
                .addPreprocessor(securityProcessor)
                .addPreprocessor(dashboardAuthorizationProcessor)
                .addPostprocessor(new CacheControlPostProcessor())
                .addFinallyProcessor(new ExceptionLogger())
                .setExecutorThreadCount(2 * Runtime.getRuntime().availableProcessors())
                .setMaxContentSize(maxContentSize)
                .noCompression()
                .mapException(ServiceNotAvailableException.class, ServiceNotAvailableServiceException.class);

        this.httpPort = httpPort;
        this.httpsPort = httpsPort;

        this.sslContextHandler = sslContextHandler;
        this.startupStatusReporter = startupStatusReporter;
        this.baseUrls = baseUrls;

        routes.register(server);

        //observe ssl context for changes
        sslContextHandler.addContextChangeListener(new SSLContextHandler.SslContextChangeListener() {
            @Override
            public void onEnable() {
                updateSSLContext();
            }

            @Override
            public void onDisable() {
                unbindHttpsChannel();
            }
        });
    }

    /**
     * Binds to the http port and starts the server.
     */
    @SubSystemInit
    public void run() throws Throwable {
        log.info("Binding on http port {}", httpPort);
        server.bind(httpPort);
        startupStatusReporter.portConnected("HTTP", baseUrls.getHttpURL());
    }

    @SubSystemShutdown
    public void stop() {
        server.shutdown();
        STATUS.info("HTTP server stopped");
    }

    /**
     * Call this method to inform the HTTP server that its IP address changed.
     * This will generate a new SSL certificate for the HTTPS server and use this from now on.
     */
    private void updateSSLContext() {
        SSLContext newSSLContext = sslContextHandler.getSSLContext();
        if (newSSLContext != null) {
            SslContext nettySslContext = new JdkSslContext(newSSLContext, false, ClientAuth.NONE);
            log.info("Refreshing SSLContext of HTTPS server...and rebinding.");
            server.setSSLContext(nettySslContext);
            boolean httpsUpdate = httpsChannel != null;
            if (httpsUpdate) {
                unbindHttpsChannel();
            }
            log.info("Binding on https port {}", httpsPort);
            try {
                httpsChannel = server.bind(httpsPort);
            } catch (Throwable t) {
                log.error("failed to bind to {}", httpsPort, t);
                return;
            }
            if (!httpsUpdate) {
                startupStatusReporter.portConnected("HTTPS", baseUrls.getHttpsURL());
            }
        }
    }

    private void unbindHttpsChannel() {
        if (httpsChannel != null) {
            log.info("Unbinding open HTTPS port");
            httpsChannel.close().awaitUninterruptibly();
        }
    }

    /**
     * Preprocessor
     */
    @Override
    public void process(Request request) {
        request.putAttachment("transactionIdentifier", new HttpTransactionIdentifier(request));
    }
}
