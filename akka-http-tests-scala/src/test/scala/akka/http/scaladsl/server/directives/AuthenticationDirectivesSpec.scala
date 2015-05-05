/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.{ CredentialsRejected, CredentialsMissing }
import akka.http.scaladsl.server.directives.AuthenticationDirectives._

import scala.concurrent.Future

class AuthenticationDirectivesSpec extends RoutingSpec {

  "the 'HttpBasicAuthentication' directive" should {
    lazy val dontAuth = HttpBasicAuthentication("MyRealm")(HttpBasicAuthenticator[String](_ ⇒ Future.successful(None)))
    lazy val doAuth = HttpBasicAuthentication("MyRealm")(HttpBasicAuthenticator.provideUserName(_ ⇒ true))
    lazy val authWithAnonymous = doAuth.withAnonymousUser("We are Legion")

    lazy val challenge = HttpChallenge("Basic", "MyRealm")

    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, challenge) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(BasicHttpCredentials("Bob", "")) ~> {
        dontAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, challenge) }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontAuth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(challenge))
      }
    }
    "extract the object representing the user identity created by successful authentication" in {
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
        doAuth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Alice" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        authWithAnonymous { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException
      Get() ~> Authorization(BasicHttpCredentials("Alice", "")) ~> {
        Route.seal {
          doAuth { _ ⇒ throw TestException }
        }
      } ~> check { status shouldEqual StatusCodes.InternalServerError }
    }
  }
  "the BearerTokenAuthentication directive" should {
    lazy val dontAuth = BearerTokenAuthentication("MyRealm")(BearerTokenAuthenticator[String](_ ⇒ Future.successful(None)))
    lazy val doAuth = BearerTokenAuthentication("MyRealm")(BearerTokenAuthenticator.provideToken(_ ⇒ true))
    lazy val authWithAnonymous = doAuth.withAnonymousUser("We are Legion")

    lazy val challenge = HttpChallenge("Bearer", "MyRealm")

    "reject requests without Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> {
        dontAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsMissing, challenge) }
    }
    "reject unauthenticated requests with Authorization header with an AuthenticationFailedRejection" in {
      Get() ~> Authorization(OAuth2BearerToken("MyToken")) ~> {
        dontAuth { echoComplete }
      } ~> check { rejection shouldEqual AuthenticationFailedRejection(CredentialsRejected, challenge) }
    }
    "reject requests with illegal Authorization header with 401" in {
      Get() ~> RawHeader("Authorization", "bob alice") ~> Route.seal {
        dontAuth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        responseAs[String] shouldEqual "The resource requires authentication, which was not supplied with the request"
        header[`WWW-Authenticate`] shouldEqual Some(`WWW-Authenticate`(challenge))
      }
    }
    "extract the token object created by successful authentication" in {
      Get() ~> Authorization(OAuth2BearerToken("MyToken")) ~> {
        doAuth { echoComplete }
      } ~> check { responseAs[String] shouldEqual "MyToken" }
    }
    "extract the object representing the user identity created for the anonymous user" in {
      Get() ~> {
        authWithAnonymous { echoComplete }
      } ~> check { responseAs[String] shouldEqual "We are Legion" }
    }
    "properly handle exceptions thrown in its inner route" in {
      object TestException extends RuntimeException
      Get() ~> Authorization(OAuth2BearerToken("MyToken")) ~> {
        Route.seal {
          doAuth { _ ⇒ throw TestException }
        }
      } ~> check { status shouldEqual StatusCodes.InternalServerError }
    }
  }
  "AuthenticationDirectives facilities" should {
    lazy val dontAuth = HttpBasicAuthentication("MyRealm")(HttpBasicAuthenticator[String](_ ⇒ Future.successful(None)))

    lazy val challenge = HttpChallenge("Basic", "MyRealm")

    "properly stack several authentication directives" in {
      val otherChallenge = HttpChallenge("MyAuth", "MyRealm2")
      val otherAuth: Directive1[String] = AuthenticationDirectives.authenticateOrRejectWithChallenge { (cred: Option[HttpCredentials]) ⇒
        Future.successful(Left(otherChallenge))
      }
      val bothAuth = dontAuth | otherAuth

      Get() ~> Route.seal {
        bothAuth { echoComplete }
      } ~> check {
        status shouldEqual StatusCodes.Unauthorized
        headers.collect {
          case `WWW-Authenticate`(challenge +: Nil) ⇒ challenge
        } shouldEqual Seq(challenge, otherChallenge)
      }
    }
  }
}
