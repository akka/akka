/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.Function;

public class CustomDirectivesExamplesTest extends JUnitRouteTest {

  //#labeling-1
  public Route getOrPut(Supplier<Route> inner) {
    return get(inner).orElse(put(inner));
  }
  //#

  @Test
  public void testLabeling() {
    // tests:

    //#labeling-2
    Route route = getOrPut(() -> complete("ok"));
    //#

    testRoute(route).run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.OK);

    testRoute(route).run(HttpRequest.PUT("/"))
      .assertStatusCode(StatusCodes.OK);

  }


  public static class MyCredentials {
    private final String userId;
    private final String secret;

    public MyCredentials(String userId, String secret) {
      this.userId = userId;
      this.secret = secret;
    }

    public String getUserId() {
      return userId;
    }

    public boolean safeSecretVerification(String correct) {
      // of course this is not what you would do in a real app
      return correct.equals(secret);
    }

  }
  public static enum MyRole {
    USER,
    ADMIN
  }

  //#composition-1
  // the composed custom directive
  /**
   * @param authenticate A function returns a set of roles for the credentials of a user
   * @param inner Inner route to execute if the provided credentials has the given role
   *              if not, the request is completed with a
   */
  public Route headerBasedAuth(Function<MyCredentials, Set<MyRole>> authenticate, MyRole requiredRole, Supplier<Route> inner) {
    return headerValueByName("X-My-User-Id", (userId) -> {
      return headerValueByName("X-My-User-Secret", (secret) -> {
        Set<MyRole> userRoles = authenticate.apply(new MyCredentials(userId, secret));
        if (userRoles.contains(requiredRole)) {
          return inner.get();
        } else {
          return complete(StatusCodes.FORBIDDEN, "Role " + requiredRole + " required for access");
        }
      });
    });
  }
  //#

  @Test
  public void testComposition() {
    // tests:

    //#composition-2
    // a function for authentication
    Function<MyCredentials, Set<MyRole>> authLogic =
      (credentials) -> {
        if (credentials.userId.equals("admin") && credentials.safeSecretVerification("secret"))
          return new HashSet<>(Arrays.asList(MyRole.USER, MyRole.ADMIN));
        else
          return Collections.emptySet();
      };

    // and then using the custom route
    Route route = get(() ->
      path("admin", () ->
        headerBasedAuth(authLogic, MyRole.ADMIN, () -> complete(StatusCodes.OK, "admin stuff"))
      )
    );
    //#


    testRoute(route).run(HttpRequest.GET("/admin"))
      .assertStatusCode(StatusCodes.BAD_REQUEST);

    testRoute(route).run(HttpRequest.GET("/admin").addHeaders(
      Arrays.asList(RawHeader.create("X-My-User-Id", "user"), RawHeader.create("X-My-User-Secret", "wrong"))))
      .assertStatusCode(StatusCodes.FORBIDDEN);

    testRoute(route).run(HttpRequest.GET("/admin").addHeaders(
      Arrays.asList(RawHeader.create("X-My-User-Id", "admin"), RawHeader.create("X-My-User-Secret", "secret"))))
      .assertStatusCode(StatusCodes.OK);

  }

}
