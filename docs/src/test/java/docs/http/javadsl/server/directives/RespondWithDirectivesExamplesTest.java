/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.Arrays;
import java.util.List;
import akka.http.javadsl.model.HttpHeader;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.HttpOrigin;
import akka.http.javadsl.model.headers.Origin;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

public class RespondWithDirectivesExamplesTest extends JUnitRouteTest {

    @Test
    public void testRespondWithHeader() {
        //#respondWithHeader
        final Route route = path("foo", () ->
                respondWithHeader(RawHeader.create("Funky-Muppet", "gonzo"), () ->
                        complete("beep")));

        testRoute(route).run(HttpRequest.GET("/foo"))
                .assertHeaderExists("Funky-Muppet", "gonzo")
                .assertEntity("beep");
        //#respondWithHeader
    }

    @Test
    public void testRespondWithDefaultHeader() {
        //#respondWithDefaultHeader
        //custom headers
        final RawHeader blippy = RawHeader.create("X-Fish-Name", "Blippy");
        final RawHeader elTonno = RawHeader.create("X-Fish-Name", "El Tonno");

        // format: OFF
        // by default always include the Blippy header,
        // unless a more specific X-Fish-Name is given by the inner route
        final Route route =
                respondWithDefaultHeader(blippy, () ->                      // blippy
                        respondWithHeader(elTonno, () ->                    // / el tonno
                                path("el-tonno", () ->                      // | /
                                        complete("¡Ay blippy!")             // | |- el tonno
                                ).orElse(                                   // | |
                                        path("los-tonnos", () ->            // | |
                                                complete("¡Ay ay blippy!")  // | |- el tonno
                                        )                                   // | |
                                )                                           // | |
                        ).orElse(                                           // | x
                                complete("Blip!")                           // |- blippy
                        )                                                   // x
                );
        //format: ON

        testRoute(route).run(HttpRequest.GET("/"))
                .assertHeaderExists("X-Fish-Name", "Blippy")
                .assertEntity("Blip!");

        testRoute(route).run(HttpRequest.GET("/el-tonno"))
                .assertHeaderExists("X-Fish-Name", "El Tonno")
                .assertEntity("¡Ay blippy!");

        testRoute(route).run(HttpRequest.GET("/los-tonnos"))
                .assertHeaderExists("X-Fish-Name", "El Tonno")
                .assertEntity("¡Ay ay blippy!");
        //#respondWithDefaultHeader
    }

    @Test
    public void respondWithHeaders() {
        //#respondWithHeaders
        final HttpHeader gonzo = RawHeader.create("Funky-Muppet", "gonzo");
        final HttpHeader akka = Origin.create(HttpOrigin.parse("http://akka.io"));

        final Route route = path("foo", () ->
                respondWithHeaders(Arrays.asList(gonzo, akka), () ->
                        complete("beep")
                )
        );

        testRoute(route).run(HttpRequest.GET("/foo"))
                .assertHeaderExists("Funky-Muppet", "gonzo")
                .assertHeaderExists("Origin", "http://akka.io")
                .assertEntity("beep");

        //#respondWithHeaders
    }

    @Test
    public void testRespondWithDefaultHeaders() {
        //#respondWithDefaultHeaders
        //custom headers
        final RawHeader blippy = RawHeader.create("X-Fish-Name", "Blippy");
        final HttpHeader akka = Origin.create(HttpOrigin.parse("http://akka.io"));
        final List<HttpHeader> defaultHeaders = Arrays.asList(blippy, akka);
        final RawHeader elTonno = RawHeader.create("X-Fish-Name", "El Tonno");

        // format: OFF
        // by default always include the Blippy and Akka headers,
        // unless a more specific X-Fish-Name is given by the inner route
        final Route route =
                respondWithDefaultHeaders(defaultHeaders, () ->            // blippy and akka
                        respondWithHeader(elTonno, () ->                   // / el tonno
                                path("el-tonno", () ->                     // | /
                                        complete("¡Ay blippy!")            // | |- el tonno
                                ).orElse(                                  // | |
                                        path("los-tonnos", () ->           // | |
                                                complete("¡Ay ay blippy!") // | |- el tonno
                                        )                                  // | |
                                )                                          // | |
                        ).orElse(                                          // | x
                                complete("Blip!")                          // |- blippy and akka
                        )                                                  // x
                );
        //format: ON

        testRoute(route).run(HttpRequest.GET("/"))
                .assertHeaderExists("X-Fish-Name", "Blippy")
                .assertHeaderExists("Origin", "http://akka.io")
                .assertEntity("Blip!");

        testRoute(route).run(HttpRequest.GET("/el-tonno"))
                .assertHeaderExists("X-Fish-Name", "El Tonno")
                .assertEntity("¡Ay blippy!");

        testRoute(route).run(HttpRequest.GET("/los-tonnos"))
                .assertHeaderExists("X-Fish-Name", "El Tonno")
                .assertEntity("¡Ay ay blippy!");
        //#respondWithDefaultHeaders
    }
}
