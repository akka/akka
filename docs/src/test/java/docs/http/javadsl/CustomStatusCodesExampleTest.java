/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.ConnectionContext;
import akka.http.javadsl.Http;
import akka.http.javadsl.HttpsConnectionContext;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaType;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.settings.ClientConnectionSettings;
import akka.http.javadsl.settings.ConnectionPoolSettings;
import akka.http.javadsl.settings.ParserSettings;
import akka.http.javadsl.settings.ServerSettings;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.stream.Materializer;
import akka.util.ByteString;
import org.junit.Test;

import javax.net.ssl.SSLContext;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

public class CustomStatusCodesExampleTest extends JUnitRouteTest {

  @Test
  public void customStatusCodes() throws ExecutionException, InterruptedException, NoSuchAlgorithmException {

    final ActorSystem system = system();
    final Materializer materializer = materializer();
    final String host = "127.0.0.1";

    //#application-custom-java
    // Define custom status code:
    final StatusCode leetCode = StatusCodes.custom(777, // Our custom status code
      "LeetCode", // Our custom reason
      "Some reason", // Our custom default message
      true, // It should be considered a success response
      false);// Does not allow entities

    // Add custom method to parser settings:
    final ParserSettings parserSettings = ParserSettings.create(system)
      .withCustomStatusCodes(leetCode);
    final ServerSettings serverSettings = ServerSettings.create(system)
      .withParserSettings(parserSettings);

    final ClientConnectionSettings clientConSettings = ClientConnectionSettings.create(system)
      .withParserSettings(parserSettings);
    final ConnectionPoolSettings clientSettings = ConnectionPoolSettings.create(system)
      .withConnectionSettings(clientConSettings);

    final Route route = extractRequest(req ->
      complete(HttpResponse.create().withStatus(leetCode))
    );

    // Use serverSettings in server:
    final CompletionStage<ServerBinding> binding = Http.get(system)
      .bindAndHandle(route.flow(system, materializer),
        ConnectHttp.toHost(host, 0),
        serverSettings,
        system.log(),
        materializer);

    final ServerBinding serverBinding = binding.toCompletableFuture().get();

    final int port = serverBinding.localAddress().getPort();

    // Use clientSettings in client:
    final HttpResponse response = Http.get(system)
      .singleRequest(HttpRequest
        .GET("http://" + host + ":" + port + "/"),
        ConnectionContext.https(SSLContext.getDefault()),
        clientSettings,
        system.log(),
        materializer)
      .toCompletableFuture()
      .get();

    // Check we get the right code back
    assertEquals(leetCode, response.status());
    //#application-custom-java
  }

}
