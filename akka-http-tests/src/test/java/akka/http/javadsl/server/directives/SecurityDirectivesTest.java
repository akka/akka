/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import akka.http.javadsl.model.StatusCodes;
import org.junit.Test;

import scala.util.Left;
import scala.util.Right;
import akka.http.javadsl.server.*;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.Authorization;
import akka.http.javadsl.model.headers.BasicHttpCredentials;
import akka.http.javadsl.model.headers.HttpChallenge;
import akka.http.javadsl.testkit.*;

import static akka.http.javadsl.server.PathMatchers.*;

public class SecurityDirectivesTest extends JUnitRouteTest {

  // These authenticators don't have to be async; they're just written that way to test the API.
  private CompletionStage<Optional<String>> authenticateUser(Optional<ProvidedCredentials> creds) {
    return CompletableFuture.completedFuture(
      creds.filter(c ->
        c.identifier().equals("sina") && c.verify("1234")
      ).map(c ->
        "sina"
      ));
  }

  private CompletionStage<Optional<String>> authenticateToken(Optional<ProvidedCredentials> creds) {
    System.out.println(creds);

    return CompletableFuture.completedFuture(
      creds.filter(c ->
        c.verify("myToken")
      ).map(c ->
        "myToken"
      ));
  }

  public Route securedRoute(String identifier) {
    return complete("Identified as " + identifier + "!");
  }

  TestRoute route =
    testRoute(
      path("basicSecure", () ->
        authenticateBasicAsync("test-realm", this::authenticateUser, this::securedRoute)
      ),
      path("oauthSecure", () ->
        authenticateOAuth2Async("test-realm", this::authenticateToken, this::securedRoute)
      ),
      path(segment("authorize").slash(integerSegment()), (n) ->
        authorize(() -> n == 1, () -> complete("authorized"))
      ),
      path(segment("authorizeAsync").slash(integerSegment()), (n) ->
        authorizeAsync(() -> CompletableFuture.completedFuture(n == 1), () -> complete("authorized"))
      ),
      path(segment("authorizeWithRequestContext").slash(integerSegment()), (n) ->
        authorizeWithRequestContext((ctx) -> n == 1, () -> complete("authorized"))
      ),
      path(segment("authorizeAsyncWithRequestContext").slash(integerSegment()), (n) ->
        authorizeAsyncWithRequestContext((ctx) -> CompletableFuture.completedFuture(n == 1), () -> complete("authorized"))
      )
    );

  @Test
  public void testCorrectUser() {
    HttpRequest authenticatedRequest =
      HttpRequest.GET("/basicSecure")
        .addHeader(Authorization.basic("sina", "1234"));

    route.run(authenticatedRequest)
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Identified as sina!");
  }

  @Test
  public void testCorrectToken() {
    HttpRequest authenticatedRequest =
      HttpRequest.GET("/oauthSecure")
        .addHeader(Authorization.oauth2("myToken"));

    route.run(authenticatedRequest)
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Identified as myToken!");
  }

  @Test
  public void testRejectAnonymousAccess() {
    route.run(HttpRequest.GET("/basicSecure"))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The resource requires authentication, which was not supplied with the request")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"test-realm\",charset=UTF-8");
  }

  @Test
  public void testRejectUnknownUser() {
    HttpRequest authenticatedRequest =
      HttpRequest.GET("/basicSecure")
        .addHeader(Authorization.basic("joe", "0000"));

    route.run(authenticatedRequest)
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The supplied authentication is invalid");
  }

  @Test
  public void testRejectWrongPassword() {
    HttpRequest authenticatedRequest =
      HttpRequest.GET("/basicSecure")
        .addHeader(Authorization.basic("sina", "1235"));

    route.run(authenticatedRequest)
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The supplied authentication is invalid");
  }

  @Test
  public void testAuthenticateOrRejectWithChallenge() {
    TestRoute route = testRoute(
      path("basicSecure", () ->
        authenticateOrRejectWithChallenge(BasicHttpCredentials.class, cred -> {
          if (cred.isPresent()) {
            return CompletableFuture.completedFuture(Right.apply(cred.get().token()));
          } else {
            return CompletableFuture.completedFuture(Left.apply(HttpChallenge.create("Basic", "test-realm")));
          }
        }, this::securedRoute)
      )
    );

    Authorization auth = Authorization.basic("sina", "1234");
    HttpRequest authenticatedRequest =
      HttpRequest.GET("/basicSecure")
        .addHeader(auth);

    route.run(authenticatedRequest)
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Identified as " + auth.credentials().token() + "!");
  }

  @Test
  public void testAuthorize() {
    route.run(HttpRequest.GET("/authorize/1"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("authorized");

    route.run(HttpRequest.GET("/authorize/0"))
      .assertStatusCode(StatusCodes.FORBIDDEN);
  }

  @Test
  public void testAuthorizeAsync() {
    route.run(HttpRequest.GET("/authorizeAsync/1"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("authorized");

    route.run(HttpRequest.GET("/authorizeAsync/0"))
      .assertStatusCode(StatusCodes.FORBIDDEN);
  }

  @Test
  public void testAuthorizeWithRequestContext() {
    route.run(HttpRequest.GET("/authorizeWithRequestContext/1"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("authorized");

    route.run(HttpRequest.GET("/authorizeWithRequestContext/0"))
      .assertStatusCode(StatusCodes.FORBIDDEN);
  }


  @Test
  public void testAuthorizeAsyncWithRequestContext() {
    route.run(HttpRequest.GET("/authorizeAsyncWithRequestContext/1"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("authorized");

    route.run(HttpRequest.GET("/authorizeAsyncWithRequestContext/0"))
      .assertStatusCode(StatusCodes.FORBIDDEN);

  }

}
