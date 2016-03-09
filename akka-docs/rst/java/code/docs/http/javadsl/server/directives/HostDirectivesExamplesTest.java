/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;

public class HostDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testListOfHost() {
    //#host1
    final Route matchListOfHosts = host(
        Arrays.asList("api.company.com", "rest.company.com"),
        () -> complete(StatusCodes.OK));

    testRoute(matchListOfHosts).run(HttpRequest.GET("/").addHeader(Host.create("api.company.com")))
        .assertStatusCode(StatusCodes.OK);
    //#host1
  }

  @Test
  public void testHostPredicate() {
    //#host2
    final Route shortOnly = host(hostname -> hostname.length() < 10,
        () -> complete(StatusCodes.OK));

    testRoute(shortOnly).run(HttpRequest.GET("/").addHeader(Host.create("short.com")))
        .assertStatusCode(StatusCodes.OK);

    testRoute(shortOnly).run(HttpRequest.GET("/").addHeader(Host.create("verylonghostname.com")))
        .assertStatusCode(StatusCodes.NOT_FOUND);
    //#host2
  }

  @Test
  public void testExtractHost() {
    //#extractHostname

    final Route route = extractHost(hn -> 
        complete("Hostname: " + hn));

    testRoute(route).run(HttpRequest.GET("/").addHeader(Host.create("company.com", 9090)))
        .assertEntity("Hostname: company.com");
    //#extractHostname
  }

  @Test
  public void testMatchAndExtractHost() {
    //#matchAndExtractHost

    final Route hostPrefixRoute = host(Pattern.compile("api|rest"), prefix -> 
        complete("Extracted prefix: " + prefix));

    final Route hostPartRoute = host(Pattern.compile("public.(my|your)company.com"), captured ->
        complete("You came through " + captured
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
    final Route hostRegex = host(Pattern.compile("server-([0-9]).company.(com|net|org)"), s ->
        // will not reach here
        complete(s)
    );
    //#failing-matchAndExtractHost
  }

}
