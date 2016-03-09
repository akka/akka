/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import akka.http.scaladsl.server.Rejection
import scala.annotation.varargs
import akka.http.javadsl.model.HttpMethods
import akka.http.javadsl.server.directives.WebSocketDirectives

abstract class AllDirectives extends WebSocketDirectives

object Directives extends AllDirectives {

  // These are repeated here since sometimes (?) the Scala compiler won't actually generate java-compatible
  // signatures for varargs methods, making them show up as Seq<Object> instead of T... in Java.
  @varargs override def reject(rejections: Rejection*): Route = super.reject(rejections: _*)
  @varargs override def route(alternatives: Route*): Route = super.route(alternatives: _*)
  @varargs override def getFromBrowseableDirectories(directories: String*): Route = super.getFromBrowseableDirectories(directories: _*)
}
