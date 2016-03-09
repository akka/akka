/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.dispatch.Futures;
import akka.dispatch.Mapper;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.RequestEntity;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.japi.function.Function;
import akka.stream.javadsl.Sink;
import akka.util.ByteString;
import org.junit.Test;
import scala.concurrent.ExecutionContext$;
import scala.concurrent.forkjoin.ForkJoinPool;

public class RouteDirectivesTest extends JUnitRouteTest {
  @Test
  public void testRedirection() {
    Uri targetUri = Uri.create("http://example.com");
    TestRoute route =
      testRoute(
        redirect(targetUri, StatusCodes.FOUND)
      );

    route
      .run(HttpRequest.create())
      .assertStatusCode(302)
      .assertHeaderExists(Location.create(targetUri));
  }

  @Test
  public void testEntitySizeLimit() {
    TestRoute route =
      testRoute(
        path("no-limit", () ->
          extractEntity(entity ->
            extractMaterializer(mat ->
              onSuccess(() -> entity
                .withoutSizeLimit()
                .getDataBytes()
                .runWith(Sink.<ByteString>head(), mat),
                bytes -> complete(bytes.utf8String())
              )
            )
          )
        ),
        path("limit-5", () ->
          extractEntity(entity ->
            extractMaterializer(mat ->
              onSuccess(() -> entity
                .withSizeLimit(5)
                .getDataBytes()
                .runWith(Sink.<ByteString>head(), mat),
                bytes -> complete(bytes.utf8String())
              )
            )
          )
        )
      );

    route
      .run(HttpRequest.create("/no-limit").withEntity("1234567890"))
      .assertStatusCode(200)
      .assertEntity("1234567890");

    route
      .run(HttpRequest.create("/limit-5").withEntity("12345"))
      .assertStatusCode(200)
      .assertEntity("12345");
    route
      .run(HttpRequest.create("/limit-5").withEntity("1234567890"))
      .assertStatusCode(500)
    .assertEntity("There was an internal server error.");
  }
}
