/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.Arrays;
import java.util.regex.Pattern;

import akka.http.javadsl.model.HttpMethod;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.*;
import akka.http.javadsl.testkit.JUnitRouteTest;

import org.junit.Test;

public class HostDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testListOfHost() {
    //#host1
    final Route matchListOfHosts = host(
        Arrays.asList("api.company.com", "rest.company.com"),
        completeWithStatus(StatusCodes.OK));

    testRoute(matchListOfHosts).run(HttpRequest.GET("/").addHeader(Host.create("api.company.com")))
        .assertStatusCode(StatusCodes.OK);
    //#host1
  }

  @Test
  public void testHostPredicate() {
    //#host2
    final Route shortOnly = host(hostname -> hostname.length() < 10,
        completeWithStatus(StatusCodes.OK));

    testRoute(shortOnly).run(HttpRequest.GET("/").addHeader(Host.create("short.com")))
        .assertStatusCode(StatusCodes.OK);

    testRoute(shortOnly).run(HttpRequest.GET("/").addHeader(Host.create("verylonghostname.com")))
        .assertStatusCode(StatusCodes.NOT_FOUND);
    //#host2
  }

  @Test
  public void testExtractHost() {
    //#extractHostname
    final RequestVal<String> host = RequestVals.host();

    final Route route = handleWith1(host,
        (ctx, hn) -> ctx.complete("Hostname: " + hn));

    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("company.com", 9090)))
        .assertEntity("Hostname: company.com");
    //#extractHostname
  }

  @Test
  public void testMatchAndExtractHost() {
    //#matchAndExtractHost
    final RequestVal<String> hostPrefix = RequestVals
        .matchAndExtractHost(Pattern.compile("api|rest"));

    final Route hostPrefixRoute = handleWith1(hostPrefix,
        (ctx, prefix) -> ctx.complete("Extracted prefix: " + prefix));

    final RequestVal<String> hostPart = RequestVals.matchAndExtractHost(Pattern
        .compile("public.(my|your)company.com"));

    final Route hostPartRoute = handleWith1(
        hostPart,
        (ctx, captured) -> ctx.complete("You came through " + captured
            + " company"));

    final Route route = route(hostPrefixRoute, hostPartRoute);

    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("api.company.com")))
        .assertStatusCode(StatusCodes.OK).assertEntity("Extracted prefix: api");

    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("public.mycompany.com")))
        .assertStatusCode(StatusCodes.OK)
        .assertEntity("You came through my company");
    //#matchAndExtractHost
  }

  @SuppressWarnings("unused")
  @Test(expected = IllegalArgumentException.class)
  public void testFailingMatchAndExtractHost() {
    //#failing-matchAndExtractHost
    // this will throw IllegalArgumentException
    final RequestVal<String> hostRegex = RequestVals
        .matchAndExtractHost(Pattern
            .compile("server-([0-9]).company.(com|net|org)"));
    //#failing-matchAndExtractHost
  }

}
