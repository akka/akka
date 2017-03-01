/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpCharsets;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.MediaType;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.settings.ParserSettings;
import akka.http.javadsl.settings.ServerSettings;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.stream.Materializer;
import akka.util.ByteString;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static org.junit.Assert.assertEquals;

public class CustomMediaTypesExampleTest extends JUnitRouteTest {

  @Test
  public void customMediaTypes() throws ExecutionException, InterruptedException {

    final ActorSystem system = system();
    final Materializer materializer = materializer();
    final String host = "127.0.0.1";

    //#application-custom-java
    // Define custom media type:
    final MediaType.WithFixedCharset applicationCustom =
      MediaTypes.customWithFixedCharset("application", "custom", // The new Media Type name
        HttpCharsets.UTF_8, // The charset used
        new HashMap<>(), // Empty parameters
        false); // No arbitrary subtypes are allowed

    // Add custom media type to parser settings:
    final ParserSettings parserSettings = ParserSettings.create(system)
      .withCustomMediaTypes(applicationCustom);
    final ServerSettings serverSettings = ServerSettings.create(system)
      .withParserSettings(parserSettings);

    final Route route = extractRequest(req ->
      complete(req.entity().getContentType().toString() + " = "
        + req.entity().getContentType().getClass())
    );

    final CompletionStage<ServerBinding> binding = Http.get(system)
      .bindAndHandle(route.flow(system, materializer),
        ConnectHttp.toHost(host, 0),
        serverSettings,
        system.log(),
        materializer);

    //#application-custom-java
    final ServerBinding serverBinding = binding.toCompletableFuture().get();

    final int port = serverBinding.localAddress().getPort();

    final HttpResponse response = Http.get(system)
      .singleRequest(HttpRequest
        .GET("http://" + host + ":" + port + "/")
        .withEntity(applicationCustom.toContentType(), "~~example~=~value~~"), materializer)
      .toCompletableFuture()
      .get();

    assertEquals(StatusCodes.OK, response.status());
    final String body = response.entity().toStrict(1000, materializer).toCompletableFuture().get()
      .getDataBytes().runFold(ByteString.empty(), (a, b) -> a.$plus$plus(b), materializer)
      .toCompletableFuture().get().utf8String();
    assertEquals("application/custom = class akka.http.scaladsl.model.ContentType$WithFixedCharset", body); // it's the Scala DSL package because it's the only instance of the Java DSL
  }

}
