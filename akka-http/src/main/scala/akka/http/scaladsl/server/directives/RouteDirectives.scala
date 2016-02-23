/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import StatusCodes._

trait RouteDirectives {

  /**
   * Rejects the request with an empty set of rejections.
   */
  def reject: StandardRoute = RouteDirectives._reject

  /**
   * Rejects the request with the given rejections.
   */
  def reject(rejections: Rejection*): StandardRoute =
    StandardRoute(_.reject(rejections: _*))

  /**
   * Completes the request with redirection response of the given type to the given URI.
   */
  def redirect(uri: Uri, redirectionType: Redirection): StandardRoute =
    StandardRoute {
      _. //# red-impl
        complete {
          HttpResponse(
            status = redirectionType,
            headers = headers.Location(uri) :: Nil,
            entity = redirectionType.htmlTemplate match {
              case ""       ⇒ HttpEntity.Empty
              case template ⇒ HttpEntity(ContentTypes.`text/html(UTF-8)`, template format uri)
            })
        }
      //#
    }

  /**
   * Completes the request using the given arguments.
   */
  def complete(m: ⇒ ToResponseMarshallable): StandardRoute =
    StandardRoute(_.complete(m))

  /**
   * Bubbles the given error up the response chain, where it is dealt with by the closest `handleExceptions`
   * directive and its ExceptionHandler.
   */
  def failWith(error: Throwable): StandardRoute =
    StandardRoute(_.fail(error))
}

object RouteDirectives extends RouteDirectives {
  private val _reject = StandardRoute(_.reject())
}
