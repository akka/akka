/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.event.LoggingAdapter;
import akka.event.NoLogging;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.settings.ParserSettings;
import akka.http.javadsl.settings.ServerSettings;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import org.junit.Test;
import static org.junit.Assert.assertEquals;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static akka.http.javadsl.model.HttpProtocols.HTTP_1_0;
import static akka.http.javadsl.model.RequestEntityAcceptances.Expected;

public class CustomHttpMethodExamplesTest extends JUnitRouteTest {

  @Test
  public void testComposition() throws InterruptedException, ExecutionException {
    ActorSystem  system = system();
    Materializer materializer = materializer();
    LoggingAdapter loggingAdapter = NoLogging.getInstance();

    int    port = 9090;
    String host = "127.0.0.1";

    //#customHttpMethod
    HttpMethod BOLT =
      HttpMethods.createCustom("BOLT", false, true, Expected);
    final ParserSettings parserSettings =
      ParserSettings.create(system).withCustomMethods(BOLT);
    final ServerSettings serverSettings =
      ServerSettings.create(system).withParserSettings(parserSettings);

    final Route routes = route(
      extractMethod( method ->
        complete( "This is a " + method.name() + " request.")
      )
    );
    final Flow<HttpRequest, HttpResponse, NotUsed> handler = routes.flow(system, materializer);
    final Http http = Http.get(system);
    final CompletionStage<ServerBinding> binding =
      http.bindAndHandle(
        handler,
        ConnectHttp.toHost(host, port),
        serverSettings,
        loggingAdapter,
        materializer);

    HttpRequest request = HttpRequest.create()
      .withUri("http://" + host + ":" + Integer.toString(port))
      .withMethod(BOLT)
      .withProtocol(HTTP_1_0);

    CompletionStage<HttpResponse> response = http.singleRequest(request, materializer);
    //#customHttpMethod

    assertEquals(StatusCodes.OK, response.toCompletableFuture().get().status());
    assertEquals(
      "This is a BOLT request.",
      response.toCompletableFuture().get().entity().toStrict(3000, materializer).toCompletableFuture().get().getData().utf8String()
    );
  }
}
