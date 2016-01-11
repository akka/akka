/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.values

import akka.http.impl.server.{ ExtractionImplBase, RouteStructure }
import akka.http.javadsl.server.{ AbstractDirective, RequestVal, Route }
import akka.http.scaladsl.util.FastFuture

import scala.concurrent.Future
import scala.reflect.ClassTag

/**
 * Represents existing or missing OAuth 2 authentication credentials.
 */
trait OAuth2Credentials {
  /**
   * Were credentials provided in the request?
   */
  def available: Boolean

  /**
   * The identifier as sent in the request.
   */
  def identifier: String

  /**
   * Verifies the given secret against the one sent in the request.
   */
  def verify(secret: String): Boolean
}

/**
 * Implement this class to provide an OAuth 2 Bearer Token authentication check. The [[authenticate]] method needs to be implemented
 * to check if the supplied or missing credentials are authenticated and provide a domain level object representing
 * the user as a [[RequestVal]].
 */
abstract class OAuth2Authenticator[T](val realm: String) extends AbstractDirective with ExtractionImplBase[T] with RequestVal[T] {
  protected[http] implicit def classTag: ClassTag[T] = reflect.classTag[AnyRef].asInstanceOf[ClassTag[T]]
  def authenticate(credentials: OAuth2Credentials): Future[Option[T]]

  /**
   * Creates a return value for use in [[authenticate]] that successfully authenticates the requests and provides
   * the given user.
   */
  def authenticateAs(user: T): Future[Option[T]] = FastFuture.successful(Some(user))

  /**
   * Refuses access for this user.
   */
  def refuseAccess(): Future[Option[T]] = FastFuture.successful(None)

  /**
   * INTERNAL API
   */
  protected[http] final def createRoute(first: Route, others: Array[Route]): Route =
    RouteStructure.OAuth2Authentication(this)(first, others.toList)
}
