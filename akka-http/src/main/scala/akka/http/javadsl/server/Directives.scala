/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.impl.util.JavaMapping
import akka.http.javadsl.server.directives.TimeoutDirectives

import scala.annotation.varargs

abstract class AllDirectives extends TimeoutDirectives

/**
 * INTERNAL API
 */
object Directives extends AllDirectives {
  import JavaMapping.Implicits._
  import RoutingJavaMapping._

  // These are repeated here since sometimes (?) the Scala compiler won't actually generate java-compatible
  // signatures for varargs methods, making them show up as Seq<Object> instead of T... in Java.

  @varargs override def route(alternatives: Route*): Route =
    super.route(alternatives: _*)

  @varargs override def getFromBrowseableDirectories(directories: String*): Route =
    super.getFromBrowseableDirectories(directories: _*)
}
