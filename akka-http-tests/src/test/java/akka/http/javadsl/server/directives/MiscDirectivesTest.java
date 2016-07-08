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
import akka.http.javadsl.model.headers.XRealIp;
import akka.http.javadsl.server.Unmarshaller;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

public class MiscDirectivesTest extends JUnitRouteTest {

  static boolean isShort(String str) {
    return str.length() < 5;
  }

  static boolean hasShortPath(Uri uri) {
    return uri.path().toString().length() < 5;
  }

  @Test
  public void testValidateUri() {
    TestRoute route = testRoute(
      extractUri(uri ->
        validate(() -> hasShortPath(uri), "Path too long!",
          () -> complete("OK!")
        )
      )
    );

    route
      .run(HttpRequest.create().withUri(Uri.create("/abc")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK!");

    route
      .run(HttpRequest.create().withUri(Uri.create("/abcdefghijkl")))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertEntity("Path too long!");
  }

  @Test
  public void testClientIpExtraction() throws UnknownHostException {
    TestRoute route = testRoute(extractClientIP(ip -> complete(ip.toString())));

    route
      .run(HttpRequest.create().addHeader(XForwardedFor.create(RemoteAddress.create(InetAddress.getByName("127.0.0.2")))))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("127.0.0.2");

    route
      .run(HttpRequest.create().addHeader(akka.http.javadsl.model.headers.RemoteAddress.create(RemoteAddress.create(InetAddress.getByName("127.0.0.3")))))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("127.0.0.3");

    route
      .run(HttpRequest.create().addHeader(XRealIp.create(RemoteAddress.create(InetAddress.getByName("127.0.0.4")))))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("127.0.0.4");

    route
      .run(HttpRequest.create())
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testWithSizeLimit() {
    TestRoute route = testRoute(withSizeLimit(500, () ->
      entity(Unmarshaller.entityToString(), (entity) -> complete("ok"))
    ));

    route
      .run(withEntityOfSize(500))
      .assertStatusCode(StatusCodes.OK);

    route
      .run(withEntityOfSize(501))
      .assertStatusCode(StatusCodes.BAD_REQUEST);

  }

  private HttpRequest withEntityOfSize(int sizeLimit) {
    char[] charArray = new char[sizeLimit];
    Arrays.fill(charArray, '0');
    return HttpRequest.POST("/").withEntity(new String(charArray));
  }

}
