/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import akka.http.impl.util.JavaMapping.Inherited
import akka.http.javadsl
import akka.http.scaladsl

/** INTERNAL API */
private[http] object RoutingJavaMapping {
  implicit object Rejection extends Inherited[javadsl.server.Rejection, scaladsl.server.Rejection]
}
