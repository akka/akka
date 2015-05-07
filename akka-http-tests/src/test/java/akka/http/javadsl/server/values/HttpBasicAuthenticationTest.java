/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.values;

import org.junit.Test;
import scala.Option;
import scala.concurrent.Future;

import akka.http.javadsl.server.*;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Authorization;
import akka.http.javadsl.testkit.*;

public class HttpBasicAuthenticationTest extends JUnitRouteTest {
    HttpBasicAuthenticator<String> authenticatedUser =
        new HttpBasicAuthenticator<String>("test-realm") {
            @Override
            public Future<Option<String>> authenticate(BasicUserCredentials credentials) {
                if (credentials.available() && // no anonymous access
                        credentials.userName().equals("sina") &&
                        credentials.verifySecret("1234"))
                    return authenticateAs("Sina");
                else return refuseAccess();
            }
        };

    Handler1<String> helloWorldHandler =
        new Handler1<String>() {
            @Override
            public RouteResult handle(RequestContext ctx, String user) {
                return ctx.complete("Hello "+user+"!");
            }
        };

    TestRoute route =
        testRoute(
            path("secure").route(
                authenticatedUser.route(
                    handleWith(authenticatedUser, helloWorldHandler)
                )
            )
        );

    @Test
    public void testCorrectUser() {
        HttpRequest authenticatedRequest =
            HttpRequest.GET("/secure")
                .addHeader(Authorization.basic("sina", "1234"));

        route.run(authenticatedRequest)
            .assertStatusCode(200)
            .assertEntity("Hello Sina!");
    }
    @Test
    public void testRejectAnonymousAccess() {
        route.run(HttpRequest.GET("/secure"))
            .assertStatusCode(401)
            .assertEntity("The resource requires authentication, which was not supplied with the request")
            .assertHeaderExists("WWW-Authenticate", "Basic realm=\"test-realm\"");
    }
    @Test
    public void testRejectUnknownUser() {
        HttpRequest authenticatedRequest =
            HttpRequest.GET("/secure")
                .addHeader(Authorization.basic("joe", "0000"));

        route.run(authenticatedRequest)
            .assertStatusCode(401)
            .assertEntity("The supplied authentication is invalid");
    }
    @Test
    public void testRejectWrongPassword() {
        HttpRequest authenticatedRequest =
            HttpRequest.GET("/secure")
                .addHeader(Authorization.basic("sina", "1235"));

        route.run(authenticatedRequest)
            .assertStatusCode(401)
            .assertEntity("The supplied authentication is invalid");
    }
}
