/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server
package directives

import com.typesafe.config.ConfigFactory
import scala.concurrent.Future
import akka.http.scaladsl.model._
import headers._

class SecurityDirectivesExamplesSpec extends RoutingSpec {
  "authenticate-custom-user-pass-authenticator" in {
    def myUserPassAuthenticator(userPass: Option[UserPass]): Future[Option[String]] =
      Future {
        if (userPass.exists(up => up.user == "John" && up.pass == "p4ssw0rd")) Some("John")
        else None
      }

    val route =
      Route.seal {
        path("secured") {
          authenticateBasic(BasicAuth(myUserPassAuthenticator _, realm = "secure site")) { userName =>
            complete(s"The user is '$userName'")
          }
        }
      }

    Get("/secured") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
      header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", "secure site")
    }

    val validCredentials = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/secured") ~>
      addCredentials(validCredentials) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "The user is 'John'"
      }

    val invalidCredentials = BasicHttpCredentials("Peter", "pan")
    Get("/secured") ~>
      addCredentials(invalidCredentials) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", "secure site")
      }
  }

  "authenticate-from-config" in {
    def extractUser(userPass: UserPass): String = userPass.user
    val config = ConfigFactory.parseString("John = p4ssw0rd")

    val route =
      Route.seal {
        path("secured") {
          authenticateBasic(BasicAuth(realm = "secure site", config = config, createUser = extractUser _)) { userName =>
            complete(s"The user is '$userName'")
          }
        }
      }

    Get("/secured") ~> route ~> check {
      status shouldEqual StatusCodes.Unauthorized
      responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
      header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", "secure site")
    }

    val validCredentials = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/secured") ~>
      addCredentials(validCredentials) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "The user is 'John'"
      }

    val invalidCredentials = BasicHttpCredentials("Peter", "pan")
    Get("/secured") ~>
      addCredentials(invalidCredentials) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The supplied authentication is invalid"
        header[`WWW-Authenticate`].get.challenges.head shouldEqual HttpChallenge("Basic", "secure site")
      }
  }

  "authorize-1" in {
    def extractUser(userPass: UserPass): String = userPass.user
    val config = ConfigFactory.parseString("John = p4ssw0rd\nPeter = pan")
    def hasPermissionToPetersLair(userName: String) = userName == "Peter"

    val route =
      Route.seal {
        authenticateBasic(BasicAuth(realm = "secure site", config = config, createUser = extractUser _)) { userName =>
          path("peters-lair") {
            authorize(hasPermissionToPetersLair(userName)) {
              complete(s"'$userName' visited Peter's lair")
            }
          }
        }
      }

    val johnsCred = BasicHttpCredentials("John", "p4ssw0rd")
    Get("/peters-lair") ~>
      addCredentials(johnsCred) ~> // adds Authorization header
      route ~> check {
        status shouldEqual StatusCodes.Forbidden
        responseAs[String] shouldEqual "The supplied authentication is not authorized to access this resource"
      }

    val petersCred = BasicHttpCredentials("Peter", "pan")
    Get("/peters-lair") ~>
      addCredentials(petersCred) ~> // adds Authorization header
      route ~> check {
        responseAs[String] shouldEqual "'Peter' visited Peter's lair"
      }
  }
}
