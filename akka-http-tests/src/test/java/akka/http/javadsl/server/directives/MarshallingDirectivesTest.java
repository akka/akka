/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.RemoteAddress;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.model.headers.XForwardedFor;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;

import org.junit.Test;
import akka.http.javadsl.server.Unmarshaller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

public class MarshallingDirectivesTest extends JUnitRouteTest {

  @Test
  public void testEntityAsString() {
    TestRoute route =
      testRoute(
        entity(Unmarshaller.entityToString(), this::complete)
      );

    HttpRequest request =
      HttpRequest.POST("/")
        .withEntity("abcdef");
    route.run(request)
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("abcdef");
  }
}
