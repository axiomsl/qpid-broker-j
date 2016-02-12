/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.qpid.server.util;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.transport.TransportException;
import org.apache.qpid.transport.network.security.ssl.SSLUtil;

public class ConnectionBuilder
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionBuilder.class);
    private final URL _url;
    private int _connectTimeout;
    private int _readTimeout;
    private TrustManager[] _trustMangers;
    private List<String> _enabledTlsProtocols;
    private List<String> _disabledTlsProtocols;
    private List<String> _enabledCipherSuites;
    private List<String> _disabledCipherSuites;


    public ConnectionBuilder(final URL url)
    {
        _url = url;
    }

    public ConnectionBuilder setConnectTimeout(final int timeout)
    {
        _connectTimeout = timeout;
        return this;
    }

    public ConnectionBuilder setReadTimeout(final int readTimeout)
    {
        _readTimeout = readTimeout;
        return this;
    }

    public ConnectionBuilder setTrustMangers(final TrustManager[] trustMangers)
    {
        _trustMangers = trustMangers;
        return this;
    }

    public ConnectionBuilder setEnabledTlsProtocols(final List<String> enabledTlsProtocols)
    {
        _enabledTlsProtocols = enabledTlsProtocols;
        return this;
    }

    public ConnectionBuilder setDisabledTlsProtocols(final List<String> disabledTlsProtocols)
    {
        _disabledTlsProtocols = disabledTlsProtocols;
        return this;
    }

    public ConnectionBuilder setEnabledCipherSuites(final List<String> enabledCipherSuites)
    {
        _enabledCipherSuites = enabledCipherSuites;
        return this;
    }

    public ConnectionBuilder setDisabledCipherSuites(final List<String> disabledCipherSuites)
    {
        _disabledCipherSuites = disabledCipherSuites;
        return this;
    }

    public HttpURLConnection build() throws IOException
    {
        HttpURLConnection connection = (HttpURLConnection) _url.openConnection();
        connection.setConnectTimeout(_connectTimeout);
        connection.setReadTimeout(_readTimeout);

        if (_trustMangers != null && _trustMangers.length > 0)
        {
            HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
            final SSLContext sslContext;
            try
            {
                sslContext = SSLContext.getInstance("TLS");
                sslContext.init(null, _trustMangers, null);
            }
            catch (GeneralSecurityException e)
            {
                throw new ServerScopedRuntimeException("Cannot initialise TLS", e);
            }

            final SSLSocketFactory socketFactory = sslContext.getSocketFactory();
            httpsConnection.setSSLSocketFactory(socketFactory);
            httpsConnection.setHostnameVerifier(new HostnameVerifier()
            {
                @Override
                public boolean verify(final String hostname, final SSLSession sslSession)
                {
                    try
                    {
                        final Certificate cert = sslSession.getPeerCertificates()[0];
                        if (cert instanceof X509Certificate)
                        {
                            final X509Certificate x509Certificate = (X509Certificate) cert;
                            SSLUtil.verifyHostname(hostname, x509Certificate);
                            return true;
                        }
                        else
                        {
                            LOGGER.warn("Cannot verify peer's hostname as peer does not present a X509Certificate. "
                                        + "Presented certificate : {}", cert);
                        }
                    }
                    catch (SSLPeerUnverifiedException | TransportException e)
                    {
                        LOGGER.warn("Failed to verify peer's hostname (connecting to host {})", hostname, e);
                    }

                    return false;
                }
            });
        }

        if ((_enabledTlsProtocols != null && !_enabledTlsProtocols.isEmpty()) ||
            (_disabledTlsProtocols != null && !_disabledTlsProtocols.isEmpty()) ||
            (_enabledCipherSuites != null && !_enabledCipherSuites.isEmpty()) ||
            (_disabledCipherSuites != null && !_disabledCipherSuites.isEmpty()))
        {
            HttpsURLConnection httpsConnection = (HttpsURLConnection) connection;
            SSLSocketFactory originalSocketFactory = httpsConnection.getSSLSocketFactory();
            httpsConnection.setSSLSocketFactory(new CustomisedSSLSocketFactory(originalSocketFactory));
        }
        return connection;
    }

    private class CustomisedSSLSocketFactory extends SSLSocketFactory
    {
        private final SSLSocketFactory _wrappedSocketFactory;

        public CustomisedSSLSocketFactory(final SSLSocketFactory wrappedSocketFactory)
        {
            _wrappedSocketFactory = wrappedSocketFactory;
        }

        @Override
        public String[] getDefaultCipherSuites()
        {
            final List<String> defaultCipherSuites = Arrays.asList(_wrappedSocketFactory.getDefaultCipherSuites());
            if (_enabledCipherSuites != null && !_enabledCipherSuites.isEmpty())
            {
                defaultCipherSuites.retainAll(_enabledCipherSuites);
            }

            if (_disabledCipherSuites != null && !_disabledCipherSuites.isEmpty())
            {
                defaultCipherSuites.removeAll(_disabledCipherSuites);
            }
            return defaultCipherSuites.toArray(new String[defaultCipherSuites.size()]);
        }

        @Override
        public String[] getSupportedCipherSuites()
        {
            return _wrappedSocketFactory.getSupportedCipherSuites();
        }

        @Override
        public Socket createSocket(final Socket socket, final String host, final int port, final boolean autoClose)
                throws IOException
        {
            return _wrappedSocketFactory.createSocket(socket, host, port, autoClose);
        }

        @Override
        public Socket createSocket(final String host, final int port) throws IOException, UnknownHostException
        {
            final SSLSocket socket = (SSLSocket) _wrappedSocketFactory.createSocket(host, port);
            SSLUtil.updateEnabledCipherSuites(socket, _enabledCipherSuites, _disabledCipherSuites);
            SSLUtil.updateEnabledTlsProtocols(socket, _enabledTlsProtocols, _disabledTlsProtocols);
            return socket;
        }

        @Override
        public Socket createSocket(final String host, final int port, final InetAddress localhost, final int localPort)
                throws IOException, UnknownHostException
        {
            final SSLSocket socket = (SSLSocket) _wrappedSocketFactory.createSocket(host, port, localhost, localPort);
            SSLUtil.updateEnabledCipherSuites(socket, _enabledCipherSuites, _disabledCipherSuites);
            SSLUtil.updateEnabledTlsProtocols(socket, _enabledTlsProtocols, _disabledTlsProtocols);
            return socket;
        }

        @Override
        public Socket createSocket(final InetAddress host, final int port) throws IOException
        {
            final SSLSocket socket = (SSLSocket) _wrappedSocketFactory.createSocket(host, port);
            SSLUtil.updateEnabledCipherSuites(socket, _enabledCipherSuites, _disabledCipherSuites);
            SSLUtil.updateEnabledTlsProtocols(socket, _enabledTlsProtocols, _disabledTlsProtocols);
            return socket;
        }

        @Override
        public Socket createSocket(final InetAddress address,
                                   final int port,
                                   final InetAddress localAddress,
                                   final int localPort) throws IOException
        {
            final SSLSocket socket = (SSLSocket) _wrappedSocketFactory.createSocket(address, port, localAddress, localPort);
            SSLUtil.updateEnabledCipherSuites(socket, _enabledCipherSuites, _disabledCipherSuites);
            SSLUtil.updateEnabledTlsProtocols(socket, _enabledTlsProtocols, _disabledTlsProtocols);
            return socket;
        }
    }
}