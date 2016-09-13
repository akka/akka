/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.model.headers.Authorization;

import java.util.Optional;

import org.junit.Test;

public class OAuth2AuthenticatorExample extends JUnitRouteTest {

    private final String hardcodedToken = "token";
    
    private Optional<String> authenticate(Optional<ProvidedCredentials> creds) {
        // this is where your actual authentication logic would go, looking up the user
        // based on the token or something in that direction
        
        // We will not allow anonymous access.
        return creds
            .filter(c -> c.verify(hardcodedToken))  // 
            .map(c -> c.identifier());              // Provide the "identifier" down to the inner route
                                                    // (for OAuth2, that's actually just the token)
    }

    @Test
    public void testOAuth2Authenticator() {
        //#oauth2-authenticator-java
        final Route route =
                authenticateOAuth2("My realm", this::authenticate, token ->
                    complete("The secret token is: " + token)
                );


        // tests:
        final HttpRequest okRequest =
            HttpRequest
                .GET("http://akka.io/")
                .addHeader(Host.create("akka.io"))
                .addHeader(Authorization.oauth2("token"));
        testRoute(route).run(okRequest).assertEntity("The secret token is: token");

        final HttpRequest badRequest =
                HttpRequest
                        .GET("http://akka.io/")
                        .addHeader(Host.create("akka.io"))
                        .addHeader(Authorization.oauth2("wrong"));
        testRoute(route).run(badRequest).assertStatusCode(401);

        //#oauth2-authenticator-java
    }


}