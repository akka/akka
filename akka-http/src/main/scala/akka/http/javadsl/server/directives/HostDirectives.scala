/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server
package directives

import java.lang.{ Iterable ⇒ JIterable, Boolean ⇒ JBoolean }

import scala.annotation.varargs
import scala.collection.JavaConverters._

import akka.http.impl.server.RouteStructure.{ GenericHostFilter, HostNameFilter }

abstract class HostDirectives extends FileAndResourceDirectives {
  /**
   * Rejects all requests with a host name different from the given one.
   */
  @varargs
  def host(hostName: String, innerRoute: Route, moreInnerRoutes: Route*): Route =
    HostNameFilter(hostName :: Nil)(innerRoute, moreInnerRoutes.toList)

  /**
   * Rejects all requests with a host name different from the given ones.
   */
  @varargs
  def host(hostNames: JIterable[String], innerRoute: Route, moreInnerRoutes: Route*): Route =
    HostNameFilter(hostNames.asScala.toList)(innerRoute, moreInnerRoutes.toList)

  /**
   * Rejects all requests for whose host name the given predicate function returns false.
   */
  @varargs
  def host(predicate: akka.japi.function.Function[String, JBoolean], innerRoute: Route, moreInnerRoutes: Route*): Route =
    new GenericHostFilter(innerRoute, moreInnerRoutes.toList) {
      def filter(hostName: String): Boolean = predicate.apply(hostName)
    }
}
