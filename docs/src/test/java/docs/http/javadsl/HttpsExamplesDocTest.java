/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.stream.ActorMaterializer;
import com.typesafe.sslconfig.akka.AkkaSSLConfig;

@SuppressWarnings("unused")
public class HttpsExamplesDocTest {

  // compile only test
  public void testConstructRequest() {
    String unsafeHost = "example.com";
    //#disable-sni-connection
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);
    final Http http = Http.get(system);

    // WARNING: disabling SNI is a very bad idea, please don't unless you have a very good reason to.
    final AkkaSSLConfig defaultSSLConfig = AkkaSSLConfig.get(system);
    final AkkaSSLConfig badSslConfig = defaultSSLConfig
      .convertSettings(s -> s.withLoose(s.loose().withDisableSNI(true)));
    final HttpsConnectionContext badCtx = http.createClientHttpsContext(badSslConfig);

    http.outgoingConnection(ConnectHttp.toHostHttps(unsafeHost).withCustomHttpsContext(badCtx));
    //#disable-sni-connection
  }

}
