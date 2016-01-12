/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import akka.japi.Option;
import akka.japi.Util;
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
      final scala.Option<scala.collection.immutable.Seq<String>> ecs;
        if (enabledCipherSuites.isDefined()) ecs = scala.Option.apply(Util.immutableSeq(enabledCipherSuites.get()));
        else ecs = scala.Option.empty();
      final scala.Option<scala.collection.immutable.Seq<String>> ep;
        if(enabledProtocols.isDefined()) ep = scala.Option.apply(Util.immutableSeq(enabledProtocols.get()));
        else ep = scala.Option.empty();
      return new akka.http.scaladsl.HttpsContext(sslContext,
        ecs,
        ep,
        clientAuth.asScala(),
        sslParameters.asScala());
    }
}
