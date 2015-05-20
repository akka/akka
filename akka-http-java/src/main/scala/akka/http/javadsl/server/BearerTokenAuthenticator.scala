/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import akka.http.impl.server.{ ExtractionImplBase, ExtractionImpl, RouteStructure }
import akka.http.scaladsl.util.FastFuture

import scala.annotation.varargs
import scala.concurrent.Future
import scala.reflect
import reflect.ClassTag

/**
 * Represents existing or missing HTTP Basic authentication credentials.
 */
trait BearerTokenCredentials {
  /**
   * Were credentials provided in the request?
   */
  def available: Boolean

  /**
   * The username as sent in the request.
   */
  def token: String
  /**
   * Verifies the given secret against the one sent in the request.
   */
  def verify(secret: String): Boolean
}

/**
 * Implement this class to provide an HTTP Basic authentication check. The [[authenticate]] method needs to be implemented
 * to check if the supplied or missing credentials are authenticated and provide a domain level object representing
 * the user as a [[RequestVal]].
 */
abstract class BearerTokenAuthenticator[T](val realm: String) extends AbstractDirective with ExtractionImplBase[T] with RequestVal[T] {
  protected[http] implicit def classTag: ClassTag[T] = reflect.classTag[AnyRef].asInstanceOf[ClassTag[T]]
  def authenticate(credentials: BearerTokenCredentials): Future[Option[T]]

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
    RouteStructure.BearerAuthentication(this, (first +: others).toVector)
}
