/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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

import scala.util.{ Try, Success }

/**
 * Provides directives for securing an inner route using the standard Http authentication headers [[`WWW-Authenticate`]]
 * and [[Authorization]]. Most prominently, HTTP Basic authentication as defined in RFC 2617.
 *
 * See: <a href="https://www.ietf.org/rfc/rfc2617.txt">RFC 2617</a>.
 *
 * @groupname security Security directives
 * @groupprio security 220
 */
trait SecurityDirectives {
  import BasicDirectives._
  import HeaderDirectives._
  import FutureDirectives._
  import RouteDirectives._

  //#authentication-result
  /**
   * The result of an HTTP authentication attempt is either the user object or
   * an HttpChallenge to present to the browser.
   *
   * @group security
   */
  type AuthenticationResult[+T] = Either[HttpChallenge, T]
  //#authentication-result

  //#authenticator
  /**
   * @group security
   */
  type Authenticator[T] = Credentials ⇒ Option[T]
  //#authenticator
  //#async-authenticator
  /**
   * @group security
   */
  type AsyncAuthenticator[T] = Credentials ⇒ Future[Option[T]]
  //#async-authenticator
  //#authenticator-pf
  /**
   * @group security
   */
  type AuthenticatorPF[T] = PartialFunction[Credentials, T]
  //#authenticator-pf
  //#async-authenticator-pf
  /**
   * @group security
   */
  type AsyncAuthenticatorPF[T] = PartialFunction[Credentials, Future[T]]
  //#async-authenticator-pf

  /**
   * Extracts the potentially present [[HttpCredentials]] provided with the request's [[Authorization]] header.
   *
   * @group security
   */
  def extractCredentials: Directive1[Option[HttpCredentials]] =
    optionalHeaderValueByType[Authorization](()).map(_.map(_.credentials))

  /**
   * Wraps the inner route with Http Basic authentication support using a given `Authenticator[T]`.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasic[T](realm: String, authenticator: Authenticator[T]): AuthenticationDirective[T] =
    authenticateBasicAsync(realm, cred ⇒ FastFuture.successful(authenticator(cred)))

  /**
   * Wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasicAsync[T](realm: String, authenticator: AsyncAuthenticator[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec ⇒
      authenticateOrRejectWithChallenge[BasicHttpCredentials, T] { cred ⇒
        authenticator(Credentials(cred)).fast.map {
          case Some(t) ⇒ AuthenticationResult.success(t)
          case None    ⇒ AuthenticationResult.failWithChallenge(challengeFor(realm))
        }
      }
    }

  /**
   * A directive that wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasicPF[T](realm: String, authenticator: AuthenticatorPF[T]): AuthenticationDirective[T] =
    authenticateBasic(realm, authenticator.lift)

  /**
   * A directive that wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateBasicPFAsync[T](realm: String, authenticator: AsyncAuthenticatorPF[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec ⇒
      authenticateBasicAsync(realm, credentials ⇒
        if (authenticator isDefinedAt credentials) authenticator(credentials).fast.map(Some(_))
        else FastFuture.successful(None))
    }

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2[T](realm: String, authenticator: Authenticator[T]): AuthenticationDirective[T] =
    authenticateOAuth2Async(realm, cred ⇒ FastFuture.successful(authenticator(cred)))

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2Async[T](realm: String, authenticator: AsyncAuthenticator[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec ⇒
      authenticateOrRejectWithChallenge[OAuth2BearerToken, T] { cred ⇒
        authenticator(Credentials(cred)).fast.map {
          case Some(t) ⇒ AuthenticationResult.success(t)
          case None    ⇒ AuthenticationResult.failWithChallenge(challengeFor(realm))
        }
      }
    }

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2PF[T](realm: String, authenticator: AuthenticatorPF[T]): AuthenticationDirective[T] =
    authenticateOAuth2(realm, authenticator.lift)

  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   *
   * @group security
   */
  def authenticateOAuth2PFAsync[T](realm: String, authenticator: AsyncAuthenticatorPF[T]): AuthenticationDirective[T] =
    extractExecutionContext.flatMap { implicit ec ⇒
      authenticateOAuth2Async(realm, credentials ⇒
        if (authenticator isDefinedAt credentials) authenticator(credentials).fast.map(Some(_))
        else FastFuture.successful(None))
    }

  /**
   * Lifts an authenticator function into a directive. The authenticator function gets passed in credentials from the
   * [[Authorization]] header of the request. If the function returns `Right(user)` the user object is provided
   * to the inner route. If the function returns `Left(challenge)` the request is rejected with an
   * [[AuthenticationFailedRejection]] that contains this challenge to be added to the response.
   *
   * @group security
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
   * Lifts an authenticator function into a directive. Same as `authenticateOrRejectWithChallenge`
   * but only applies the authenticator function with a certain type of credentials.
   *
   * @group security
   */
  def authenticateOrRejectWithChallenge[C <: HttpCredentials: ClassTag, T](
    authenticator: Option[C] ⇒ Future[AuthenticationResult[T]]): AuthenticationDirective[T] =
    authenticateOrRejectWithChallenge[T](cred ⇒ authenticator(cred collect { case c: C ⇒ c }))

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorize(check: ⇒ Boolean): Directive0 = authorize(_ ⇒ check)

  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorize(check: RequestContext ⇒ Boolean): Directive0 =
    authorizeAsync(ctx ⇒ Future.successful(check(ctx)))

  /**
   * Asynchronous version of [[authorize]].
   * If the [[Future]] fails or is completed with `false`
   * authorization fails and the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorizeAsync(check: ⇒ Future[Boolean]): Directive0 =
    authorizeAsync(ctx ⇒ check)

  /**
   * Asynchronous version of [[authorize]].
   * If the [[Future]] fails or is completed with `false`
   * authorization fails and the route is rejected with an [[AuthorizationFailedRejection]].
   *
   * @group security
   */
  def authorizeAsync(check: RequestContext ⇒ Future[Boolean]): Directive0 =
    extractExecutionContext.flatMap { implicit ec ⇒
      extract(check).flatMap[Unit] { fa ⇒
        onComplete(fa).flatMap {
          case Success(true) ⇒ pass
          case _             ⇒ reject(AuthorizationFailedRejection)
        }
      }
    }

  /**
   * Creates a `Basic` [[HttpChallenge]] for the given realm.
   *
   * @group security
   */
  def challengeFor(realm: String) = HttpChallenge(scheme = "Basic", realm = realm, params = Map.empty)
}

object SecurityDirectives extends SecurityDirectives

/**
 * Represents authentication credentials supplied with a request. Credentials can either be
 * [[Credentials.Missing]] or can be [[Credentials.Provided]] in which case an identifier is
 * supplied and a function to check the known secret against the provided one in a secure fashion.
 */
sealed trait Credentials
object Credentials {
  case object Missing extends Credentials
  abstract case class Provided(identifier: String) extends Credentials {
    /**
     * Safely compares the passed in `secret` with the received secret part of the Credentials.
     * Use of this method instead of manual String equality testing is recommended in order to guard against timing attacks.
     *
     * See also [[EnhancedString#secure_==]], for more information.
     */
    def verify(secret: String): Boolean
  }

  def apply(cred: Option[HttpCredentials]): Credentials = {
    cred match {
      case Some(BasicHttpCredentials(username, receivedSecret)) ⇒
        new Credentials.Provided(username) {
          def verify(secret: String): Boolean = secret secure_== receivedSecret
        }
      case Some(OAuth2BearerToken(token)) ⇒
        new Credentials.Provided(token) {
          def verify(secret: String): Boolean = secret secure_== token
        }
      case Some(GenericHttpCredentials(scheme, token, params)) ⇒
        throw new UnsupportedOperationException("cannot verify generic HTTP credentials")
      case None ⇒ Credentials.Missing
    }
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
   * Returns a copy of this [[AuthenticationDirective]] that will provide `Some(user)` if credentials
   * were supplied and otherwise `None`.
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
