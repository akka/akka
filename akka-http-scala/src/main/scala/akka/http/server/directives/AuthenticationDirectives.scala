/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server
package directives

import scala.reflect.ClassTag

import scala.concurrent.{ ExecutionContext, Future }

import akka.http.util._
import akka.http.util.FastFuture._

import akka.http.model.headers._
import akka.http.server.AuthenticationFailedRejection.{ CredentialsRejected, CredentialsMissing }

/**
 * Provides directives for securing an inner route using the standard Http authentication headers [[`WWW-Authenticate`]]
 * and [[Authorization]]. Most prominently, HTTP Basic authentication as defined in RFC 2617.
 */
trait AuthenticationDirectives {
  import BasicDirectives._
  import AuthenticationDirectives._

  /**
   * The result of an HTTP authentication attempt is either the user object or
   * an HttpChallenge to present to the browser.
   */
  type AuthenticationResult[+T] = Either[HttpChallenge, T]

  /**
   * Given [[UserCredentials]] the HttpBasicAuthenticator
   * returns a Future of either the authenticated user object or None of the user
   * couldn't be authenticated.
   */
  type HttpBasicAuthenticator[T] = UserCredentials ⇒ Future[Option[T]]

  object HttpBasicAuthentication {
    def challengeFor(realm: String) = HttpChallenge(scheme = "Basic", realm = realm, params = Map.empty)

    /**
     * A directive that wraps the inner route with Http Basic authentication support. The given authenticator
     * is used to determine if the credentials in the request are valid and which user object to supply
     * to the inner route.
     */
    def apply[T](realm: String)(authenticator: HttpBasicAuthenticator[T]): AuthenticationDirective[T] =
      extractExecutionContext.flatMap { implicit ctx ⇒
        authenticateOrRejectWithChallenge[BasicHttpCredentials, T] { basic ⇒
          authenticator(authDataFor(basic)).fast.map {
            case Some(t) ⇒ AuthenticationResult.success(t)
            case None    ⇒ AuthenticationResult.failWithChallenge(challengeFor(realm))
          }
        }
      }

    private def authDataFor(cred: Option[BasicHttpCredentials]): UserCredentials =
      cred match {
        case Some(BasicHttpCredentials(username, receivedSecret)) ⇒
          new UserCredentials.Provided(username) {
            def verifySecret(secret: String): Boolean = secret secure_== receivedSecret
          }
        case None ⇒ UserCredentials.Missing
      }
  }
}

object AuthenticationDirectives extends AuthenticationDirectives {
  import BasicDirectives._
  import RouteDirectives._
  import FutureDirectives._
  import HeaderDirectives._

  /**
   * Represents authentication credentials supplied with a request. Credentials can either be
   * [[UserCredentials.Missing]] or can be [[UserCredentials.Provided]] in which case a username is
   * supplied and a function to check the known secret against the provided one in a secure fashion.
   */
  sealed trait UserCredentials
  object UserCredentials {
    case object Missing extends UserCredentials
    abstract case class Provided(username: String) extends UserCredentials {
      def verifySecret(secret: String): Boolean
    }
  }

  object AuthenticationResult {
    def success[T](user: T): AuthenticationResult[T] = Right(user)
    def failWithChallenge(challenge: HttpChallenge): AuthenticationResult[Nothing] = Left(challenge)
  }

  object HttpBasicAuthenticator {
    implicit def apply[T](f: UserCredentials ⇒ Future[Option[T]]): HttpBasicAuthenticator[T] =
      new HttpBasicAuthenticator[T] {
        def apply(credentials: UserCredentials): Future[Option[T]] = f(credentials)
      }
    def fromPF[T](pf: PartialFunction[UserCredentials, Future[T]])(implicit ec: ExecutionContext): HttpBasicAuthenticator[T] =
      new HttpBasicAuthenticator[T] {
        def apply(credentials: UserCredentials): Future[Option[T]] =
          if (pf.isDefinedAt(credentials)) pf(credentials).fast.map(Some(_))
          else FastFuture.successful(None)
      }

    def checkAndProvide[T](check: UserCredentials.Provided ⇒ Boolean)(provide: String ⇒ T)(implicit ec: ExecutionContext): HttpBasicAuthenticator[T] =
      HttpBasicAuthenticator.fromPF {
        case p @ UserCredentials.Provided(name) if check(p) ⇒ FastFuture.successful(provide(name))
      }
    def provideUserName(check: UserCredentials.Provided ⇒ Boolean)(implicit ec: ExecutionContext): HttpBasicAuthenticator[String] =
      checkAndProvide(check)(identity)
  }

  /**
   * Lifts an authenticator function into a directive. The authenticator function gets passed in credentials from the
   * [[Authorization]] header of the request. If the function returns ``Right(user)`` the user object is provided
   * to the inner route. If the function returns ``Left(challenge)`` the request is rejected with an
   * [[AuthenticationFailedRejection]] that contains this challenge to be added to the response.
   *
   */
  def authenticateOrRejectWithChallenge[T](authenticator: Option[HttpCredentials] ⇒ Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ctx ⇒
      extractCredentials.flatMap { cred ⇒
        onSuccess(authenticator(cred)).flatMap {
          case Right(user) ⇒ provide(user)
          case Left(challenge) ⇒
            val cause = if (cred.isEmpty) CredentialsMissing else CredentialsRejected
            reject(AuthenticationFailedRejection(cause, challenge)): Directive1[T]
        }
      }
    }

  /**
   * Lifts an authenticator function into a directive. Same as ``authenticateOrRejectWithChallenge`` above but only applies
   * the authenticator function with a certain type of credentials.
   */
  def authenticateOrRejectWithChallenge[C <: HttpCredentials: ClassTag, T](authenticator: Option[C] ⇒ Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    authenticateOrRejectWithChallenge[T] { cred ⇒
      authenticator {
        cred.collect {
          case c: C ⇒ c
        }
      }
    }

  trait AuthenticationDirective[T] extends Directive1[T] {
    /**
     * Returns a copy of this authenticationDirective that will provide ``Some(user)`` if credentials
     * were supplied and otherwise ``None``.
     */
    def optional: Directive1[Option[T]] =
      this.map(Some(_): Option[T]).recover {
        case AuthenticationFailedRejection(CredentialsMissing, _) +: _ ⇒ provide(None)
        case rejs ⇒ reject(rejs: _*)
      }

    /**
     * Returns a copy of this authenticationDirective that uses the given object as the
     * anonymous user which will be used if no credentials were supplied in the request.
     */
    def withAnonymousUser(anonymous: T): Directive1[T] =
      optional.map(_.getOrElse(anonymous))
  }
  object AuthenticationDirective {
    implicit def apply[T](other: Directive1[T]): AuthenticationDirective[T] =
      new AuthenticationDirective[T] { def tapply(inner: Tuple1[T] ⇒ Route) = other.tapply(inner) }
  }

  def extractCredentials: Directive1[Option[HttpCredentials]] =
    optionalHeaderValueByType[`Authorization`]().map(_.map(_.credentials))
}