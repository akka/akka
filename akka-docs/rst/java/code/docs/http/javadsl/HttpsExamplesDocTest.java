/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.AbstractActor;
import akka.actor.ActorSystem;
import akka.http.javadsl.*;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.Pair;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import com.typesafe.sslconfig.akka.AkkaSSLConfig;
import scala.concurrent.ExecutionContextExecutor;
import scala.util.Try;

import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.ConnectHttp.toHost;
import static akka.pattern.PatternsCS.pipe;

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
