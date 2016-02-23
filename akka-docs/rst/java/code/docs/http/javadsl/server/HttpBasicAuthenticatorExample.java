/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import java.util.Optional;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.Handler1;
import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.RouteResult;
import akka.http.javadsl.server.values.BasicCredentials;
import akka.http.javadsl.server.values.HttpBasicAuthenticator;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.model.headers.Authorization;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.junit.Test;
import scala.Option;
import scala.concurrent.Future;

public class HttpBasicAuthenticatorExample extends JUnitRouteTest {


    @Test
    public void testBasicAuthenticator() {
        //#basic-authenticator-java
        final HttpBasicAuthenticator<String> authentication = new HttpBasicAuthenticator<String>("My realm") {

            private final String hardcodedPassword = "correcthorsebatterystaple";

            public CompletionStage<Optional<String>> authenticate(BasicCredentials credentials) {
                // this is where your actual authentication logic would go
                if (credentials.available() && // no anonymous access
                    credentials.verify(hardcodedPassword)) {
                    return authenticateAs(credentials.identifier());
                } else {
                    return refuseAccess();
                }
            }
        };

        final Route route =
            authentication.route(
                handleWith1(
                    authentication,
                    new Handler1<String>() {
                        public RouteResult apply(RequestContext ctx, String user) {
                            return ctx.complete("Hello " + user + "!");
                        }

                    }
                )
            );


        // tests:
        final HttpRequest okRequest =
            HttpRequest
                .GET("http://akka.io/")
                .addHeader(Host.create("akka.io"))
                .addHeader(Authorization.basic("randal", "correcthorsebatterystaple"));
        testRoute(route).run(okRequest).assertEntity("Hello randal!");

        final HttpRequest badRequest =
                HttpRequest
                        .GET("http://akka.io/")
                        .addHeader(Host.create("akka.io"))
                        .addHeader(Authorization.basic("randal", "123abc"));
        testRoute(route).run(badRequest).assertStatusCode(401);

        //#basic-authenticator-java
    }


}
