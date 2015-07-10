/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import akka.japi.Option;
import akka.stream.io.ClientAuth;

import java.util.Collection;

public abstract class HttpsContext {
    
    public abstract SSLContext getSslContext();

    public abstract Option<Collection<String>> getEnabledCipherSuites();

    public abstract Option<Collection<String>> getEnabledProtocols();

    public abstract Option<ClientAuth> getClientAuth();

    public abstract Option<SSLParameters> getSslParameters();

    //#http-context-creation
    public static HttpsContext create(SSLContext sslContext,
                                      Option<Collection<String>> enabledCipherSuites,
                                      Option<Collection<String>> enabledProtocols,
                                      Option<ClientAuth> clientAuth,
                                      Option<SSLParameters> sslParameters)
    //#http-context-creation
    {
        return akka.http.scaladsl.HttpsContext.create(sslContext, enabledCipherSuites, enabledProtocols,
                clientAuth, sslParameters);
    }
}
