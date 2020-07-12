/*
 * Copyright (C) 2013 4th Line GmbH, Switzerland
 *
 * The contents of this file are subject to the terms of either the GNU
 * Lesser General Public License Version 2 or later ("LGPL") or the
 * Common Development and Distribution License Version 1 or later
 * ("CDDL") (collectively, the "License"). You may not use this file
 * except in compliance with the License. See LICENSE.txt for more
 * information.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.fourthline.cling.transport.impl.jetty;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.ByteBufferContentProvider;
import org.eclipse.jetty.client.util.StringContentProvider;
import org.eclipse.jetty.http.HttpFields;
import org.fourthline.cling.model.message.*;
import org.fourthline.cling.model.message.header.ContentTypeHeader;
import org.fourthline.cling.model.message.header.UpnpHeader;
import org.fourthline.cling.transport.spi.InitializationException;
import org.fourthline.cling.transport.spi.StreamClient;
import org.fourthline.cling.transport.spi.StreamClientConfiguration;
import org.seamless.util.MimeType;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementation based on Jetty 8 client API.
 * <p>
 * This implementation works on Android, dependencies are the <code>jetty-client</code>
 * Maven module.
 * </p>
 *
 * @author Christian Bauer
 */
public class JettyStreamClientImpl implements StreamClient<StreamClientConfiguration> {

    final private static Logger log = Logger.getLogger(StreamClient.class.getName());

    final protected StreamClientConfigurationImpl configuration;
    final protected HttpClient client;

    public JettyStreamClientImpl(StreamClientConfigurationImpl configuration) throws InitializationException {
        this.configuration = configuration;

        log.info("Starting Jetty HttpClient...");
        client = new HttpClient();

        // These are some safety settings, we should never run into these timeouts as we
        // do our own expiration checking
        // These are some safety settings, we should never run into these timeouts as we
        // do our own expiration checking
        int timeout = (configuration.getTimeoutSeconds() + 5) * 1000;
        client.setStopTimeout(timeout);
        client.setIdleTimeout(timeout);
        client.setAddressResolutionTimeout(timeout);
        client.setConnectTimeout((configuration.getTimeoutSeconds() + 5) * 1000);

//        client.setMaxRetries(configuration.getRequestRetryCount());

        try {
            client.start();
        } catch (Exception ex) {
            throw new InitializationException(
                    "Could not start Jetty HTTP client: " + ex, ex
            );
        }
    }

    @Override
    public StreamClientConfigurationImpl getConfiguration() {
        return configuration;
    }

    @Override
    public StreamResponseMessage sendRequest(StreamRequestMessage message) throws InterruptedException {
        final UpnpRequest requestOperation = message.getOperation();

        Request request = client.newRequest(requestOperation.getURI().toString());
        request.method(requestOperation.getHttpMethodName());

        // Headers
        UpnpHeaders headers = message.getHeaders();
        // TODO Always add the Host header
        // TODO: ? setRequestHeader(UpnpHeader.Type.HOST.getHttpName(), );
        // Add the default user agent if not already set on the message
        if (!headers.containsKey(UpnpHeader.Type.USER_AGENT)) {
            String value = getConfiguration().getUserAgentValue(
                    message.getUdaMajorVersion(),
                    message.getUdaMinorVersion());

            request.header(UpnpHeader.Type.USER_AGENT.getHttpName(), value);
        }
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String v : entry.getValue()) {
                String headerName = entry.getKey();
                if (log.isLoggable(Level.FINE))
                    log.fine("Setting header '" + headerName + "': " + v);
                request.header(headerName, v);
            }
        }

        // Body
        if (message.hasBody()) {
            if (message.getBodyType() == UpnpMessage.BodyType.STRING) {
                MimeType contentType =
                        message.getContentTypeHeader() != null
                                ? message.getContentTypeHeader().getValue()
                                : ContentTypeHeader.DEFAULT_CONTENT_TYPE_UTF8;

                String charset =
                        message.getContentTypeCharset() != null
                                ? message.getContentTypeCharset()
                                : "UTF-8";
                request.header("content-type", contentType.toString());

                StringContentProvider contentProvider = new StringContentProvider(message.getBodyString(), charset);

                request.header("content-length", String.valueOf(contentProvider.getLength()));
                request.content(contentProvider, contentType.toString());

            } else {
                if (log.isLoggable(Level.FINE))
                    log.fine("Writing binary request body: " + message);

                if (message.getContentTypeHeader() == null)
                    throw new RuntimeException("Missing content type header in request message: " + message);

                MimeType contentType = message.getContentTypeHeader().getValue();

                request.header("content-type", contentType.toString());

                byte[] bytes = message.getBodyBytes();
                ByteBuffer buffer;
                buffer = ByteBuffer.allocate(bytes.length);

                ByteBufferContentProvider contentProvider = new ByteBufferContentProvider(buffer);

                request.header("content-length", String.valueOf(contentProvider.getLength()));
                request.content(contentProvider, contentType.toString());
                request.content(contentProvider, contentType.toString());
            }
        }

        try {
            ContentResponse response = request.send();

            // Status
            UpnpResponse responseOperation =
                    new UpnpResponse(
                            response.getStatus(),
                            UpnpResponse.Status.getByStatusCode(response.getStatus()).getStatusMsg()
                    );

            if (log.isLoggable(Level.FINE))
                log.fine("Received response: " + responseOperation);

            StreamResponseMessage responseMessage = new StreamResponseMessage(responseOperation);

            // Headers
            UpnpHeaders upnpHeaders = new UpnpHeaders();
            HttpFields responseFields = response.getHeaders();

            for (String name : responseFields.getFieldNamesCollection()) {
                for (String value : responseFields.getValuesList(name)) {
                    headers.add(name, value);
                }
            }
            responseMessage.setHeaders(headers);

            // Body
            byte[] bytes = response.getContent();
            if (bytes != null && bytes.length > 0 && responseMessage.isContentTypeMissingOrText()) {

                if (log.isLoggable(Level.FINE))
                    log.fine("Response contains textual entity body, converting then setting string on message");
                try {
                    responseMessage.setBodyCharacters(bytes);
                } catch (UnsupportedEncodingException ex) {
                    throw new RuntimeException("Unsupported character encoding: " + ex, ex);
                }

            } else if (bytes != null && bytes.length > 0) {

                if (log.isLoggable(Level.FINE))
                    log.fine("Response contains binary entity body, setting bytes on message");
                responseMessage.setBody(UpnpMessage.BodyType.BYTES, bytes);

            } else {
                if (log.isLoggable(Level.FINE))
                    log.fine("Response did not contain entity body");
            }

            if (log.isLoggable(Level.FINE))
                log.fine("Response message complete: " + responseMessage);

            return responseMessage;
        } catch (Exception e) {
            if (log.isLoggable(Level.SEVERE))
                log.severe("Failed to send response");

            return null;
        }
    }

    @Override
    public void stop() {
        try {
            client.stop();
        } catch (Exception ex) {
            log.info("Error stopping HTTP client: " + ex);
        }
    }
}


