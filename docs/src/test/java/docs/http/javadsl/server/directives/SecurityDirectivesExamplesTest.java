/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.BasicHttpCredentials;
import akka.http.javadsl.model.headers.HttpChallenge;
import akka.http.javadsl.model.headers.HttpCredentials;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.japi.JavaPartialFunction;
import org.junit.Test;
import scala.PartialFunction;
import scala.util.Either;
import scala.util.Left;
import scala.util.Right;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.Optional;
import akka.japi.Option;

public class SecurityDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testAuthenticateBasic() {
    //#authenticateBasic
    final Function<Optional<ProvidedCredentials>, Optional<String>> myUserPassAuthenticator =
      credentials ->
        credentials.filter(c -> c.verify("p4ssw0rd")).map(ProvidedCredentials::identifier);

    final Route route = path("secured", () ->
      authenticateBasic("secure site", myUserPassAuthenticator, userName ->
        complete("The user is '" + userName + "'")
      )
    ).seal(system(), materializer());

    // tests:
    testRoute(route).run(HttpRequest.GET("/secured"))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The resource requires authentication, which was not supplied with the request")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");

    final HttpCredentials validCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(validCredentials))
      .assertEntity("The user is 'John'");

    final HttpCredentials invalidCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("Peter", "pan");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(invalidCredentials))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The supplied authentication is invalid")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");
    //#authenticateBasic
  }


  @Test
  public void testAuthenticateBasicPF() {
    //#authenticateBasicPF
    final PartialFunction<Optional<ProvidedCredentials>, String> myUserPassAuthenticator =
      new JavaPartialFunction<Optional<ProvidedCredentials>, String>() {
        @Override
        public String apply(Optional<ProvidedCredentials> opt, boolean isCheck) throws Exception {
          if (opt.filter(c -> (c != null) && c.verify("p4ssw0rd")).isPresent()) {
            if (isCheck) return null;
            else return opt.get().identifier();
          } else if (opt.filter(c -> (c != null) && c.verify("p4ssw0rd-special")).isPresent()) {
            if (isCheck) return null;
            else return opt.get().identifier() + "-admin";
          } else {
            throw noMatch();
          }
        }
      };

    final Route route = path("secured", () ->
      authenticateBasicPF("secure site", myUserPassAuthenticator, userName ->
        complete("The user is '" + userName + "'")
      )
    ).seal(system(), materializer());

    // tests:
    testRoute(route).run(HttpRequest.GET("/secured"))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The resource requires authentication, which was not supplied with the request")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");

    final HttpCredentials validCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(validCredentials))
      .assertEntity("The user is 'John'");

    final HttpCredentials validAdminCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd-special");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(validAdminCredentials))
      .assertEntity("The user is 'John-admin'");

    final HttpCredentials invalidCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("Peter", "pan");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(invalidCredentials))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The supplied authentication is invalid")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");
    //#authenticateBasicPF
  }

  @Test
  public void testAuthenticateBasicPFAsync() {
    //#authenticateBasicPFAsync
    class User {
      private final String id;
      public User(String id) {
        this.id = id;
      }
      public String getId() {
        return id;
      }
    }

    final PartialFunction<Optional<ProvidedCredentials>, CompletionStage<User>> myUserPassAuthenticator =
      new JavaPartialFunction<Optional<ProvidedCredentials>,CompletionStage<User>>() {
        @Override
        public CompletionStage<User> apply(Optional<ProvidedCredentials> opt, boolean isCheck) throws Exception {
          if (opt.filter(c -> (c != null) && c.verify("p4ssw0rd")).isPresent()) {
            if (isCheck) return CompletableFuture.completedFuture(null);
            else return CompletableFuture.completedFuture(new User(opt.get().identifier()));
          } else {
            throw noMatch();
          }
        }
      };

    final Route route = path("secured", () ->
      authenticateBasicPFAsync("secure site", myUserPassAuthenticator, user ->
        complete("The user is '" + user.getId() + "'"))
    ).seal(system(), materializer());

    // tests:
    testRoute(route).run(HttpRequest.GET("/secured"))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The resource requires authentication, which was not supplied with the request")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");

    final HttpCredentials validCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(validCredentials))
      .assertEntity("The user is 'John'");

    final HttpCredentials invalidCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("Peter", "pan");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(invalidCredentials))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The supplied authentication is invalid")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");
    //#authenticateBasicPFAsync
  }

  @Test
  public void testAuthenticateBasicAsync() {
    //#authenticateBasicAsync
    final Function<Optional<ProvidedCredentials>, CompletionStage<Optional<String>>> myUserPassAuthenticator = opt -> {
      if (opt.filter(c -> (c != null) && c.verify("p4ssw0rd")).isPresent()) {
        return CompletableFuture.completedFuture(Optional.of(opt.get().identifier()));
      } else {
        return CompletableFuture.completedFuture(Optional.empty());
      }
    };

    final Route route = path("secured", () ->
      authenticateBasicAsync("secure site", myUserPassAuthenticator, userName ->
        complete("The user is '" + userName + "'")
      )
    ).seal(system(), materializer());

    // tests:
    testRoute(route).run(HttpRequest.GET("/secured"))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The resource requires authentication, which was not supplied with the request")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");

    final HttpCredentials validCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(validCredentials))
      .assertEntity("The user is 'John'");

    final HttpCredentials invalidCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("Peter", "pan");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(invalidCredentials))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The supplied authentication is invalid")
      .assertHeaderExists("WWW-Authenticate", "Basic realm=\"secure site\",charset=UTF-8");
    //#authenticateBasicAsync
  }

  @Test
  public void testAuthenticateOrRejectWithChallenge() {
    //#authenticateOrRejectWithChallenge
    final HttpChallenge challenge = HttpChallenge.create("MyAuth", new Option.Some<>("MyRealm"));

    // your custom authentication logic:
    final Function<HttpCredentials, Boolean> auth = credentials -> true;

    final Function<Optional<HttpCredentials>, CompletionStage<Either<HttpChallenge, String>>> myUserPassAuthenticator =
      opt -> {
        if (opt.isPresent() && auth.apply(opt.get())) {
          return CompletableFuture.completedFuture(Right.apply("some-user-name-from-creds"));
        } else {
          return CompletableFuture.completedFuture(Left.apply(challenge));
        }
      };

    final Route route = path("secured", () ->
      authenticateOrRejectWithChallenge(myUserPassAuthenticator, userName ->
        complete("Authenticated!")
      )
    ).seal(system(), materializer());

    // tests:
    testRoute(route).run(HttpRequest.GET("/secured"))
      .assertStatusCode(StatusCodes.UNAUTHORIZED)
      .assertEntity("The resource requires authentication, which was not supplied with the request")
      .assertHeaderExists("WWW-Authenticate", "MyAuth realm=\"MyRealm\"");

    final HttpCredentials validCredentials =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/secured").addCredentials(validCredentials))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Authenticated!");
    //#authenticateOrRejectWithChallenge
  }

  @Test
  public void testAuthorize() {
    //#authorize
    class User {
      private final String name;
      public User(String name) {
        this.name = name;
      }
      public String getName() {
        return name;
      }
    }

    // authenticate the user:
    final Function<Optional<ProvidedCredentials>, Optional<User>> myUserPassAuthenticator =
      opt -> {
        if (opt.isPresent()) {
          return Optional.of(new User(opt.get().identifier()));
        } else {
          return Optional.empty();
        }
      };

    // check if user is authorized to perform admin actions:
    final Set<String> admins = new HashSet<>();
    admins.add("Peter");
    final Function<User, Boolean> hasAdminPermissions = user -> admins.contains(user.getName());

    final Route route = authenticateBasic("secure site", myUserPassAuthenticator, user ->
      path("peters-lair", () ->
        authorize(() -> hasAdminPermissions.apply(user), () ->
          complete("'" + user.getName() +"' visited Peter's lair")
        )
      )
    ).seal(system(), materializer());

    // tests:
    final HttpCredentials johnsCred =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/peters-lair").addCredentials(johnsCred))
      .assertStatusCode(StatusCodes.FORBIDDEN)
      .assertEntity("The supplied authentication is not authorized to access this resource");

    final HttpCredentials petersCred =
      BasicHttpCredentials.createBasicHttpCredentials("Peter", "pan");
    testRoute(route).run(HttpRequest.GET("/peters-lair").addCredentials(petersCred))
      .assertEntity("'Peter' visited Peter's lair");
    //#authorize
  }

  @Test
  public void testAuthorizeAsync() {
    //#authorizeAsync
    class User {
      private final String name;
      public User(String name) {
        this.name = name;
      }
      public String getName() {
        return name;
      }
    }

    // authenticate the user:
    final Function<Optional<ProvidedCredentials>, Optional<User>> myUserPassAuthenticator =
      opt -> {
        if (opt.isPresent()) {
          return Optional.of(new User(opt.get().identifier()));
        } else {
          return Optional.empty();
        }
      };

    // check if user is authorized to perform admin actions,
    // this could potentially be a long operation so it would return a Future
    final Set<String> admins = new HashSet<>();
    admins.add("Peter");
    final Set<String> synchronizedAdmins = Collections.synchronizedSet(admins);

    final Function<User, CompletionStage<Object>> hasAdminPermissions =
      user -> CompletableFuture.completedFuture(synchronizedAdmins.contains(user.getName()));

    final Route route = authenticateBasic("secure site", myUserPassAuthenticator, user ->
      path("peters-lair", () ->
        authorizeAsync(() -> hasAdminPermissions.apply(user), () ->
          complete("'" + user.getName() +"' visited Peter's lair")
        )
      )
    ).seal(system(), materializer());

    // tests:
    final HttpCredentials johnsCred =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/peters-lair").addCredentials(johnsCred))
      .assertStatusCode(StatusCodes.FORBIDDEN)
      .assertEntity("The supplied authentication is not authorized to access this resource");

    final HttpCredentials petersCred =
      BasicHttpCredentials.createBasicHttpCredentials("Peter", "pan");
    testRoute(route).run(HttpRequest.GET("/peters-lair").addCredentials(petersCred))
      .assertEntity("'Peter' visited Peter's lair");
    //#authorizeAsync
  }

  @Test
  public void testExtractCredentials() {
    //#extractCredentials
    final Route route = extractCredentials(optCreds -> {
      if (optCreds.isPresent()) {
        return complete("Credentials: " + optCreds.get());
      } else {
        return complete("No credentials");
      }
    });

    // tests:
    final HttpCredentials johnsCred =
      BasicHttpCredentials.createBasicHttpCredentials("John", "p4ssw0rd");
    testRoute(route).run(HttpRequest.GET("/").addCredentials(johnsCred))
      .assertEntity("Credentials: Basic Sm9objpwNHNzdzByZA==");

    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("No credentials");
    //#extractCredentials
  }
}
