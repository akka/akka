/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import org.junit.Test;

import java.util.ArrayList;
import java.util.regex.Pattern;

public class HostDirectivesTest extends JUnitRouteTest {
  @Test
  public void testHostFilterBySingleName() {
    TestRoute route = testRoute(host("example.org", () -> complete("OK!")));

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK!");

    route
      .run(HttpRequest.create().withUri(Uri.create("https://other.org")))
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }

  @Test
  public void testHostFilterByNames() {
    ArrayList<String> hosts = new ArrayList<String>();
    hosts.add("example.org");
    hosts.add("example2.org");
    TestRoute route = testRoute(host(hosts, () -> complete("OK!")));

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK!");

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example2.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK!");

    route
      .run(HttpRequest.create().withUri(Uri.create("https://other.org")))
      .assertStatusCode(404);
  }

  @Test
  public void testHostFilterByPredicate() {
    TestRoute route = testRoute(host(hostName -> hostName.contains("ample"), () -> complete("OK!")));

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("OK!");

    route
      .run(HttpRequest.create().withUri(Uri.create("https://other.org")))
      .assertStatusCode(StatusCodes.NOT_FOUND);
  }


  @Test
  public void testHostExtraction() {
    TestRoute route = testRoute(extractHost(this::complete));

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("example.org");
  }

  @Test
  public void testHostPatternExtraction() {
    TestRoute route =
      testRoute(host(Pattern.compile(".*\\.([^.]*)"), this::complete));

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("org");

    route
      .run(HttpRequest.create().withUri(Uri.create("http://example.de")))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("de");
  }

}
