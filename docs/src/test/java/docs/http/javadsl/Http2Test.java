/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.http.javadsl;

import java.util.concurrent.CompletionStage;
import akka.japi.Function;
import akka.actor.ExtendedActorSystem;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.stream.Materializer;

//#bindAndHandleAsync
import akka.http.javadsl.Http;
import static akka.http.javadsl.ConnectHttp.toHostHttps;

//#bindAndHandleAsync

class Http2Test {
  void testBindAndHandleAsync() {
    Function<HttpRequest, CompletionStage<HttpResponse>> asyncHandler = null;
    Materializer materializer = null;
    ExtendedActorSystem system = null;
    HttpsConnectionContext httpsConnectionContext = null;

    //#bindAndHandleAsync
    Http.get(system)
      .bindAndHandleAsync(
        asyncHandler,
        toHostHttps("127.0.0.1", 8443).withCustomHttpsContext(httpsConnectionContext),
        materializer);
    //#bindAndHandleAsync
  }
}
