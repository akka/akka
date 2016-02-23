/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

import org.junit.Test;

import akka.http.javadsl.server.*;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Authorization;
import akka.http.javadsl.testkit.*;

public class HttpBasicAuthenticationTest extends JUnitRouteTest {
    HttpBasicAuthenticator<String> authenticatedUser =
        new HttpBasicAuthenticator<String>("test-realm") {
            @Override
            public CompletionStage<Optional<String>> authenticate(BasicCredentials credentials) {
                if (credentials.available() && // no anonymous access
                        credentials.identifier().equals("sina") &&
                        credentials.verify("1234"))
                    return authenticateAs("Sina");
                else return refuseAccess();
            }
        };

    OAuth2Authenticator<String> authenticatedToken =
        new OAuth2Authenticator<String>("test-realm") {
            @Override
            public CompletionStage<Optional<String>> authenticate(OAuth2Credentials credentials) {
                if (credentials.available() && // no anonymous access
                        credentials.identifier().equals("myToken") &&
                        credentials.verify("myToken"))
                    return authenticateAs("myToken");
                else return refuseAccess();
            }
        };

    Handler1<String> helloWorldHandler =
        new Handler1<String>() {
            @Override
            public RouteResult apply(RequestContext ctx, String identifier) {
                return ctx.complete("Identified as "+identifier+"!");
            }
        };

    TestRoute route =
        testRoute(
            path("basicSecure").route(
                authenticatedUser.route(
                    handleWith1(authenticatedUser, helloWorldHandler)
                )
            ),
            path("oauthSecure").route(
                authenticatedToken.route(
                    handleWith1(authenticatedToken, helloWorldHandler)
                )
            )
        );

    @Test
    public void testCorrectUser() {
        HttpRequest authenticatedRequest =
            HttpRequest.GET("/basicSecure")
                .addHeader(Authorization.basic("sina", "1234"));

        route.run(authenticatedRequest)
            .assertStatusCode(200)
            .assertEntity("Identified as Sina!");
    }
    @Test
    public void testCorrectToken() {
        HttpRequest authenticatedRequest =
          HttpRequest.GET("/oauthSecure")
            .addHeader(Authorization.oauth2("myToken"));

        route.run(authenticatedRequest)
          .assertStatusCode(200)
          .assertEntity("Identified as myToken!");
    }
    @Test
    public void testRejectAnonymousAccess() {
        route.run(HttpRequest.GET("/basicSecure"))
            .assertStatusCode(401)
            .assertEntity("The resource requires authentication, which was not supplied with the request")
            .assertHeaderExists("WWW-Authenticate", "Basic realm=\"test-realm\"");
    }
    @Test
    public void testRejectUnknownUser() {
        HttpRequest authenticatedRequest =
            HttpRequest.GET("/basicSecure")
                .addHeader(Authorization.basic("joe", "0000"));

        route.run(authenticatedRequest)
            .assertStatusCode(401)
            .assertEntity("The supplied authentication is invalid");
    }
    @Test
    public void testRejectWrongPassword() {
        HttpRequest authenticatedRequest =
            HttpRequest.GET("/basicSecure")
                .addHeader(Authorization.basic("sina", "1235"));

        route.run(authenticatedRequest)
            .assertStatusCode(401)
            .assertEntity("The supplied authentication is invalid");
    }
}
