/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.model.headers.OAuth2BearerToken;
import akka.http.javadsl.server.Handler1;
import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.RouteResult;
import akka.http.javadsl.server.values.BasicCredentials;
import akka.http.javadsl.server.values.HttpBasicAuthenticator;
import akka.http.javadsl.server.values.OAuth2Authenticator;
import akka.http.javadsl.server.values.OAuth2Credentials;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.model.headers.Authorization;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.junit.Test;
import scala.Option;
import scala.concurrent.Future;

public class OAuth2AuthenticatorExample extends JUnitRouteTest {


    @Test
    public void testOAuth2Authenticator() {
        //#oauth2-authenticator-java
        final OAuth2Authenticator<String> authentication = new OAuth2Authenticator<String>("My realm") {

            private final String hardcodedToken = "token";

            @Override
            public CompletionStage<Optional<String>> authenticate(OAuth2Credentials credentials) {
                // this is where your actual authentication logic would go, looking up the user
                // based on the token or something in that direction
                if (credentials.available() && // no anonymous access
                        credentials.verify(hardcodedToken)) {
                    // not a secret + identity pair, so this is actually the token
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
                        public RouteResult apply(RequestContext ctx, String token) {
                            return ctx.complete("The secret token is: " + token);
                        }

                    }
                )
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