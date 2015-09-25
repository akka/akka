/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.server
package directives

import scala.reflect.ClassTag
import scala.concurrent.Future
import akka.http.impl.util._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.server.AuthenticationFailedRejection.{ CredentialsRejected, CredentialsMissing }

/**
 * Provides directives for securing an inner route using the standard Http authentication headers [[`WWW-Authenticate`]]
 * and [[Authorization]]. Most prominently, HTTP Basic authentication as defined in RFC 2617.
 */
trait SecurityDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import FutureDirectives._
  import RouteDirectives._

  /**
   * The result of an HTTP authentication attempt is either the user object or
   * an HttpChallenge to present to the browser.
   */
  type AuthenticationResult[+T] = Either[HttpChallenge, T]

  type Authenticator[T] = UserCredentials ⇒ Option[T]
  type AsyncAuthenticator[T] = UserCredentials ⇒ Future[Option[T]]
  type AuthenticatorPF[T] = PartialFunction[UserCredentials, T]
  type AsyncAuthenticatorPF[T] = PartialFunction[UserCredentials, Future[T]]

  /**
   * Extracts the potentially present [[HttpCredentials]] provided with the request's [[Authorization]] header.
   */
  def extractCredentials: Directive1[Option[HttpCredentials]] =
    optionalHeaderValueByType[Authorization](()).map(_.map(_.credentials))

  /**
   * Wraps the inner route with Http Basic authentication support using a given ``Authenticator[T]``.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   */
  def authenticateBasic[T](realm: String, authenticator: Authenticator[T]): AuthenticationDirective[T] =
    authenticateBasicAsync(realm, cred ⇒ FastFuture.successful(authenticator(cred)))

  /**
   * Wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   */
  def authenticateBasicAsync[T](realm: String, authenticator: AsyncAuthenticator[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec ⇒
      authenticateOrRejectWithChallenge[BasicHttpCredentials, T] { basic ⇒
        authenticator(UserCredentials(basic)).fast.map {
          case Some(t) ⇒ AuthenticationResult.success(t)
          case None    ⇒ AuthenticationResult.failWithChallenge(challengeFor(realm))
        }
      }
    }

  /**
   * A directive that wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   */
  def authenticateBasicPF[T](realm: String, authenticator: AuthenticatorPF[T]): AuthenticationDirective[T] =
    authenticateBasic(realm, authenticator.lift)

  /**
   * A directive that wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   */
  def authenticateBasicPFAsync[T](realm: String, authenticator: AsyncAuthenticatorPF[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec ⇒
      authenticateBasicAsync(realm, credentials ⇒
        if (authenticator isDefinedAt credentials) authenticator(credentials).fast.map(Some(_))
        else FastFuture.successful(None))
    }

  /**
   * Lifts an authenticator function into a directive. The authenticator function gets passed in credentials from the
   * [[Authorization]] header of the request. If the function returns ``Right(user)`` the user object is provided
   * to the inner route. If the function returns ``Left(challenge)`` the request is rejected with an
   * [[AuthenticationFailedRejection]] that contains this challenge to be added to the response.
   *
   */
  def authenticateOrRejectWithChallenge[T](authenticator: Option[HttpCredentials] ⇒ Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec ⇒
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
   * Lifts an authenticator function into a directive. Same as ``authenticateOrRejectWithChallenge``
   * but only applies the authenticator function with a certain type of credentials.
   */
  def authenticateOrRejectWithChallenge[C <: HttpCredentials: ClassTag, T](
    authenticator: Option[C] ⇒ Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    authenticateOrRejectWithChallenge[T](cred ⇒ authenticator(cred collect { case c: C ⇒ c }))

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   */
  def authorize(check: ⇒ Boolean): Directive0 = authorize(_ ⇒ check)

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   */
  def authorize(check: RequestContext ⇒ Boolean): Directive0 =
    extract(check).flatMap[Unit](if (_) pass else reject(AuthorizationFailedRejection)) &
      cancelRejection(AuthorizationFailedRejection)

  /**
   * Creates a ``Basic`` [[HttpChallenge]] for the given realm.
   */
  def challengeFor(realm: String) = HttpChallenge(scheme = "Basic", realm = realm, params = Map.empty)
}

object SecurityDirectives extends SecurityDirectives

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

  def apply(cred: Option[BasicHttpCredentials]): UserCredentials =
    cred match {
      case Some(BasicHttpCredentials(username, receivedSecret)) ⇒
        new UserCredentials.Provided(username) {
          def verifySecret(secret: String): Boolean = secret secure_== receivedSecret
        }
      case None ⇒ UserCredentials.Missing
    }
}

import SecurityDirectives._

object AuthenticationResult {
  def success[T](user: T): AuthenticationResult[T] = Right(user)
  def failWithChallenge(challenge: HttpChallenge): AuthenticationResult[Nothing] = Left(challenge)
}

trait AuthenticationDirective[T] extends Directive1[T] {
  import BasicDirectives._
  import RouteDirectives._

  /**
   * Returns a copy of this [[AuthenticationDirective]] that will provide ``Some(user)`` if credentials
   * were supplied and otherwise ``None``.
   */
  def optional: Directive1[Option[T]] =
    this.map(Some(_): Option[T]) recover {
      case AuthenticationFailedRejection(CredentialsMissing, _) +: _ ⇒ provide(None)
      case rejs ⇒ reject(rejs: _*)
    }

  /**
   * Returns a copy of this [[AuthenticationDirective]] that uses the given object as the
   * anonymous user which will be used if no credentials were supplied in the request.
   */
  def withAnonymousUser(anonymous: T): Directive1[T] = optional map (_ getOrElse anonymous)
}
object AuthenticationDirective {
  implicit def apply[T](other: Directive1[T]): AuthenticationDirective[T] =
    new AuthenticationDirective[T] { def tapply(inner: Tuple1[T] ⇒ Route) = other.tapply(inner) }
}