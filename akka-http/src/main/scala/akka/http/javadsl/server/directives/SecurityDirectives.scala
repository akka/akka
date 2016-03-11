/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.{ Function ⇒ JFunction }
import java.util.function.Supplier

import scala.collection.JavaConverters._
import scala.compat.java8.FutureConverters._
import scala.compat.java8.OptionConverters._

import akka.http.javadsl.model.headers.HttpChallenge
import akka.http.javadsl.model.headers.HttpCredentials
import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Route
import akka.http.scaladsl

import akka.http.scaladsl.server.{ Directives ⇒ D }

object SecurityDirectives {
  /**
   * Represents HTTP Basic or OAuth2 authentication credentials supplied with a request.
   */
  case class ProvidedCredentials(private val toScala: scaladsl.server.directives.Credentials.Provided) {
    /**
     * The username or token provided with the credentials
     */
    def identifier: String = toScala.identifier
    
    /**
     * Safely compares the passed in `secret` with the received secret part of the Credentials.
     * Use of this method instead of manual String equality testing is recommended in order to guard against timing attacks.
     *
     * See also [[EnhancedString#secure_==]], for more information.
     */
    def verify(secret: String): Boolean = toScala.verify(secret)    
  }
  
  private def toJava(cred: scaladsl.server.directives.Credentials): Optional[ProvidedCredentials] = cred match {
    case provided: scaladsl.server.directives.Credentials.Provided => Optional.of(ProvidedCredentials(provided))
    case _ => Optional.empty()
  }
}

abstract class SecurityDirectives extends SchemeDirectives {
  import SecurityDirectives._
  
  /**
   * Extracts the potentially present [[HttpCredentials]] provided with the request's [[Authorization]] header.
   */
  def extractCredentials(inner: JFunction[Optional[HttpCredentials], Route]): Route = ScalaRoute {
    D.extractCredentials { cred =>
      inner.apply(cred.asJava).toScala
    }
  }
  
  /**
   * Wraps the inner route with Http Basic authentication support using a given `Authenticator[T]`.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is required in this variant, i.e. the request is rejected if [authenticator] returns Optional.empty.
   */
  def authenticateBasic[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], Optional[T]], 
                           inner: JFunction[T, Route]): Route = ScalaRoute {
    D.authenticateBasic(realm, c => authenticator.apply(toJava(c)).asScala) { t =>
      inner.apply(t).toScala
    }
  }
  
  /**
   * Wraps the inner route with Http Basic authentication support using a given `Authenticator[T]`.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is optional in this variant.
   */
  def authenticateBasicOptional[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], Optional[T]], 
                                   inner: JFunction[Optional[T], Route]): Route = ScalaRoute {
    D.authenticateBasic(realm, c => authenticator.apply(toJava(c)).asScala).optional { t =>
      inner.apply(t.asJava).toScala
    }
  }
  
  /**
   * Wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is required in this variant, i.e. the request is rejected if [authenticator] returns Optional.empty.
   */
  def authenticateBasicAsync[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], CompletionStage[Optional[T]]], 
                                inner: JFunction[T, Route]): Route = ScalaRoute {
    D.extractExecutionContext { implicit ctx =>
      D.authenticateBasicAsync(realm, c => authenticator.apply(toJava(c)).toScala.map(_.asScala)) { t =>
        inner.apply(t).toScala
      }
    }
  }
  
  /**
   * Wraps the inner route with Http Basic authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is optional in this variant.
   */
  def authenticateBasicAsyncOptional[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], CompletionStage[Optional[T]]], 
                                        inner: JFunction[Optional[T], Route]): Route = ScalaRoute {
    D.extractExecutionContext { implicit ctx =>
      D.authenticateBasicAsync(realm, c => authenticator.apply(toJava(c)).toScala.map(_.asScala)).optional { t =>
        inner.apply(t.asJava).toScala
      }
    }
  }
  
  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is required in this variant, i.e. the request is rejected if [authenticator] returns Optional.empty.
   */
  def authenticateOAuth2[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], Optional[T]], 
                            inner: JFunction[T, Route]): Route = ScalaRoute {
    D.authenticateOAuth2(realm, c => authenticator.apply(toJava(c)).asScala) { t =>
      inner.apply(t).toScala
    }
  }
  
  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is optional in this variant.
   */
  def authenticateOAuth2Optional[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], Optional[T]], 
                                    inner: JFunction[Optional[T], Route]): Route = ScalaRoute {
    D.authenticateOAuth2(realm, c => authenticator.apply(toJava(c)).asScala).optional { t =>
      inner.apply(t.asJava).toScala
    }
  }
  
  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is required in this variant, i.e. the request is rejected if [authenticator] returns Optional.empty.
   */
  def authenticateOAuth2Async[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], CompletionStage[Optional[T]]], 
                                inner: JFunction[T, Route]): Route = ScalaRoute {
    D.extractExecutionContext { implicit ctx =>
      D.authenticateBasicAsync(realm, c => authenticator.apply(toJava(c)).toScala.map(_.asScala)) { t =>
        inner.apply(t).toScala
      }
    }
  }
  
  /**
   * A directive that wraps the inner route with OAuth2 Bearer Token authentication support.
   * The given authenticator determines whether the credentials in the request are valid
   * and, if so, which user object to supply to the inner route.
   * 
   * Authentication is optional in this variant.
   */
  def authenticateOAuth2AsyncOptional[T](realm: String, authenticator: JFunction[Optional[ProvidedCredentials], CompletionStage[Optional[T]]], 
                                         inner: JFunction[Optional[T], Route]): Route = ScalaRoute {
    D.extractExecutionContext { implicit ctx =>
      D.authenticateBasicAsync(realm, c => authenticator.apply(toJava(c)).toScala.map(_.asScala)).optional { t =>
        inner.apply(t.asJava).toScala
      }
    }
  }
  
  /**
   * Lifts an authenticator function into a directive. The authenticator function gets passed in credentials from the
   * [[Authorization]] header of the request. If the function returns `Right(user)` the user object is provided
   * to the inner route. If the function returns `Left(challenge)` the request is rejected with an
   * [[AuthenticationFailedRejection]] that contains this challenge to be added to the response.
   */
  def authenticateOrRejectWithChallenge[T](authenticator: JFunction[Optional[HttpCredentials], CompletionStage[Either[HttpChallenge,T]]],
                                           inner: JFunction[T, Route]): Route = ScalaRoute {
    D.extractExecutionContext { implicit ctx =>
      val scalaAuthenticator = { cred: Option[scaladsl.model.headers.HttpCredentials] =>
        authenticator.apply(cred.asJava).toScala.map(_.left.map(ch => ch: scaladsl.model.headers.HttpChallenge))
      }
    
      D.authenticateOrRejectWithChallenge(scalaAuthenticator) { t =>
        inner.apply(t).toScala
      }
    }
  }
  
  /**
   * Lifts an authenticator function into a directive. Same as `authenticateOrRejectWithChallenge`
   * but only applies the authenticator function with a certain type of credentials.
   */
  def authenticateOrRejectWithChallenge[C <: HttpCredentials, T](c: Class[C],
                              authenticator: JFunction[Optional[C], CompletionStage[Either[HttpChallenge,T]]],
                              inner: JFunction[T, Route]): Route = ScalaRoute {
    D.extractExecutionContext { implicit ctx =>
      val scalaAuthenticator = { cred: Option[scaladsl.model.headers.HttpCredentials] =>
        authenticator.apply(cred.filter(c.isInstance).asJava).toScala.map(_.left.map(ch => ch: scaladsl.model.headers.HttpChallenge))
      }
    
      D.authenticateOrRejectWithChallenge(scalaAuthenticator) { t =>
        inner.apply(t).toScala
      }
    }
  }
  
  /**
   * Applies the given authorization check to the request.
   * If the check fails the route is rejected with an [[AuthorizationFailedRejection]].
   */
  def authorize(check: Supplier[Boolean], inner: Supplier[Route]): Route = ScalaRoute {
    D.authorize(check.get()) {
      inner.get().toScala
    }
  }

  /**
   * Creates a `Basic` [[HttpChallenge]] for the given realm.
   */
  def challengeFor(realm: String): HttpChallenge = HttpChallenge.create("Basic", realm)
  
}