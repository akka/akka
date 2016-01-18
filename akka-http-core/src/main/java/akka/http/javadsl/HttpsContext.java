/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

import akka.japi.Util;
import akka.stream.io.ClientAuth;

import java.util.Collection;
import java.util.Optional;

import scala.compat.java8.OptionConverters;

public abstract class HttpsContext {
    
    public abstract SSLContext getSslContext();

    public abstract Optional<Collection<String>> getEnabledCipherSuites();

    public abstract Optional<Collection<String>> getEnabledProtocols();

    public abstract Optional<ClientAuth> getClientAuth();

    public abstract Optional<SSLParameters> getSslParameters();

    //#http-context-creation
    public static HttpsContext create(SSLContext sslContext,
                                      Optional<Collection<String>> enabledCipherSuites,
                                      Optional<Collection<String>> enabledProtocols,
                                      Optional<ClientAuth> clientAuth,
                                      Optional<SSLParameters> sslParameters)
    //#http-context-creation
    {
      final scala.Option<scala.collection.immutable.Seq<String>> ecs;
        if (enabledCipherSuites.isPresent()) ecs = scala.Option.apply(Util.immutableSeq(enabledCipherSuites.get()));
        else ecs = scala.Option.empty();
      final scala.Option<scala.collection.immutable.Seq<String>> ep;
        if(enabledProtocols.isPresent()) ep = scala.Option.apply(Util.immutableSeq(enabledProtocols.get()));
        else ep = scala.Option.empty();
      return new akka.http.scaladsl.HttpsContext(sslContext,
        ecs,
        ep,
        OptionConverters.toScala(clientAuth),
        OptionConverters.toScala(sslParameters));
    }
}
