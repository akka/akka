/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import java.util.Optional;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.model.headers.Authorization;

public class HttpBasicAuthenticatorExample extends JUnitRouteTest {

    private final String hardcodedPassword = "correcthorsebatterystaple";
    
    private Optional<String> authenticate(Optional<ProvidedCredentials> creds) {
        // this is where your actual authentication logic would go
        return creds
            .filter(c -> c.verify(hardcodedPassword))  // Only allow users that provide the right password
            .map(c -> c.identifier());                 // Provide the username down to the inner route
    }

    @Test
    public void testBasicAuthenticator() {
        //#basic-authenticator-java

        final Route route =
            authenticateBasic("My realm", this::authenticate, user ->
                complete("Hello " + user + "!")
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
