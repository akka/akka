/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.scaladsl.server.directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.Credentials

import scala.concurrent.Future
import docs.http.scaladsl.server.RoutingSpec

class SecurityDirectivesExamplesSpec extends RoutingSpec {

  "authenticateBasic-0" in {
    //#authenticateBasic-0
    def myUserPassAuthenticator(credentials: Credentials): Option[String] =
      credentials match {
        case p @ Credentials.Provided(id) if p.verify("p4ssw0rd") => Some(id)
        case _ => None
      }

    val route =
      Route.seal {
        path("secured") {
          authenticateBasic(realm = "secure site", myUserPassAuthenticator) { userName =>
            complete(s"The user is '$userName'")
          }
        }
      }

    // tests:
    Get("/secured") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
      header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
    }

    val validCredentials = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/secured") ~> addCredentials(validCredentials) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "The user is 'John'"
      }

    val invalidCredentials = BasicHttpCredentials("Peter", "pan")
    Get("/secured") ~>
      addCredentials(invalidCredentials) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
      }
    //#authenticateBasic-0
  }
  "authenticateBasicPF-0" in {
    //#authenticateBasicPF-0
    val myUserPassAuthenticator: AuthenticatorPF[String] = {
      case p @ Credentials.Provided(id) if p.verify("p4ssw0rd")         => id
      case p @ Credentials.Provided(id) if p.verify("p4ssw0rd-special") => s"$id-admin"
    }

    val route =
      Route.seal {
        path("secured") {
          authenticateBasicPF(realm = "secure site", myUserPassAuthenticator) { userName =>
            complete(s"The user is '$userName'")
          }
        }
      }

    // tests:
    Get("/secured") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
      header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
    }

    val validCredentials = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/secured") ~> addCredentials(validCredentials) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "The user is 'John'"
      }

    val validAdminCredentials = BasicHttpCredentials("John", "p4ssw0rd-special")
    Get("/secured") ~> addCredentials(validAdminCredentials) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "The user is 'John-admin'"
      }

    val invalidCredentials = BasicHttpCredentials("Peter", "pan")
    Get("/secured") ~>
      addCredentials(invalidCredentials) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
      }
    //#authenticateBasicPF-0
  }
  "authenticateBasicPFAsync-0" in {
    //#authenticateBasicPFAsync-0
    case class User(id: String)
    def fetchUser(id: String): Future[User] = {
      // some fancy logic to obtain a User
      Future.successful(User(id))
    }

    val myUserPassAuthenticator: AsyncAuthenticatorPF[User] = {
      case p @ Credentials.Provided(id) if p.verify("p4ssw0rd") =>
        fetchUser(id)
    }

    val route =
      Route.seal {
        path("secured") {
          authenticateBasicPFAsync(realm = "secure site", myUserPassAuthenticator) { user =>
            complete(s"The user is '${user.id}'")
          }
        }
      }

    // tests:
    Get("/secured") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
      header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
    }

    val validCredentials = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/secured") ~> addCredentials(validCredentials) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "The user is 'John'"
      }

    val invalidCredentials = BasicHttpCredentials("Peter", "pan")
    Get("/secured") ~>
      addCredentials(invalidCredentials) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
      }
    //#authenticateBasicPFAsync-0
  }
  "authenticateBasicAsync-0" in {
    //#authenticateBasicAsync-0
    def myUserPassAuthenticator(credentials: Credentials): Future[Option[String]] =
      credentials match {
        case p @ Credentials.Provided(id) =>
          Future {
            // potentially
            if (p.verify("p4ssw0rd")) Some(id)
            else None
          }
        case _ => Future.successful(None)
      }

    val route =
      Route.seal {
        path("secured") {
          authenticateBasicAsync(realm = "secure site", myUserPassAuthenticator) { userName =>
            complete(s"The user is '$userName'")
          }
        }
      }

    // tests:
    Get("/secured") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
      header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
    }

    val validCredentials = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/secured") ~> addCredentials(validCredentials) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "The user is 'John'"
      }

    val invalidCredentials = BasicHttpCredentials("Peter", "pan")
    Get("/secured") ~>
      addCredentials(invalidCredentials) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", Some("secure site"), Map("charset" → "UTF-8"))
      }
    //#authenticateBasicAsync-0
  }
  "authenticateOrRejectWithChallenge-0" in {
    //#authenticateOrRejectWithChallenge-0
    val challenge = HttpChallenge("MyAuth", Some("MyRealm"))

    // your custom authentication logic:
    def auth(creds: HttpCredentials): Boolean = true

    def myUserPassAuthenticator(credentials: Option[HttpCredentials]): Future[AuthenticationResult[String]] =
      Future {
        credentials match {
          case Some(creds) if auth(creds) => Right("some-user-name-from-creds")
          case _                          => Left(challenge)
        }
      }

    val route =
      Route.seal {
        path("secured") {
          authenticateOrRejectWithChallenge(myUserPassAuthenticator _) { userName =>
            complete("Authenticated!")
          }
        }
      }

    // tests:
    Get("/secured") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
      header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("MyAuth", Some("MyRealm"))
    }

    val validCredentials = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/secured") ~> addCredentials(validCredentials) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.OK
        responseAs[String] shouldEqual "Authenticated!"
      }
    //#authenticateOrRejectWithChallenge-0
  }

  "0authorize-0" in {
    //#authorize0-0
    case class User(name: String)

    // authenticate the user:
    def myUserPassAuthenticator(credentials: Credentials): Option[User] =
      credentials match {
        case Credentials.Provided(id) => Some(User(id))
        case _                        => None
      }

    // check if user is authorized to perform admin actions:
    val admins = Set("Peter")
    def hasAdminPermissions(user: User): Boolean =
      admins.contains(user.name)

    val route =
      Route.seal {
        authenticateBasic(realm = "secure site", myUserPassAuthenticator) { user =>
          path("peters-lair") {
            authorize(hasAdminPermissions(user)) {
              complete(s"'${user.name}' visited Peter's lair")
            }
          }
        }
      }

    // tests:
    val johnsCred = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/peters-lair") ~> addCredentials(johnsCred) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[String] shouldEqual "The supplied authentication is not authorized to access this resource"
      }

    val petersCred = BasicHttpCredentials("Peter", "pan")
    Get("/peters-lair") ~> addCredentials(petersCred) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "'Peter' visited Peter's lair"
      }
    //#authorize0-0
  }

  "0authorizeAsync" in {
    //#authorizeAsync0
    case class User(name: String)

    // authenticate the user:
    def myUserPassAuthenticator(credentials: Credentials): Option[User] =
      credentials match {
        case Credentials.Provided(id) => Some(User(id))
        case _                        => None
      }

    // check if user is authorized to perform admin actions,
    // this could potentially be a long operation so it would return a Future
    val admins = Set("Peter")
    def hasAdminPermissions(user: User): Future[Boolean] =
      Future.successful(admins.contains(user.name))

    val route =
      Route.seal {
        authenticateBasic(realm = "secure site", myUserPassAuthenticator) { user =>
          path("peters-lair") {
            authorizeAsync(_ => hasAdminPermissions(user)) {
              complete(s"'${user.name}' visited Peter's lair")
            }
          }
        }
      }

    // tests:
    val johnsCred = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/peters-lair") ~> addCredentials(johnsCred) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[String] shouldEqual "The supplied authentication is not authorized to access this resource"
      }

    val petersCred = BasicHttpCredentials("Peter", "pan")
    Get("/peters-lair") ~> addCredentials(petersCred) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "'Peter' visited Peter's lair"
      }
    //#authorizeAsync0
  }

  "0extractCredentials" in {
    //#extractCredentials0
    val route =
      extractCredentials { creds =>
        complete {
          creds match {
            case Some(c) => "Credentials: " + c
            case _       => "No credentials"
          }
        }
      }

    // tests:
    val johnsCred = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/") ~> addCredentials(johnsCred) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "Credentials: Basic Sm9objpwNHNzdzByZA=="
      }

    Get("/") ~> route ~> check {
      responseAs[String] shouldEqual "No credentials"
    }
    //#extractCredentials0
  }
}
