/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server

import akka.http.scaladsl.server
import java.util.function
import akka.http.scaladsl.server.Rejection
import scala.reflect.ClassTag
import scala.collection.JavaConverters._

object RejectionHandler {
  /**
   * Creates a new [[RejectionHandler]] builder.
   */
  def newBuilder = new RejectionHandlerBuilder(server.RejectionHandler.newBuilder)
  
  def defaultHandler = new RejectionHandler(server.RejectionHandler.default)
}

final class RejectionHandler(val asScala: server.RejectionHandler) {
  /**
   * Creates a new [[RejectionHandler]] which uses the given one as fallback for this one.
   */
  def withFallback(fallback: RejectionHandler) = new RejectionHandler(asScala.withFallback(fallback.asScala))
  
  /**
   * "Seals" this handler by attaching a default handler as fallback if necessary.
   */
  def seal = new RejectionHandler(asScala.seal)
}

class RejectionHandlerBuilder(asScala: server.RejectionHandler.Builder) {
  def build = new RejectionHandler(asScala.result())
  
  /**
   * Handles a single [[Rejection]] with the given partial function.
   */
  def handle[T <: Rejection](t: Class[T], handler: function.Function[T, Route]): RejectionHandlerBuilder = {
    asScala.handle { case r if t.isInstance(r) => handler.apply(t.cast(r)).toScala }
    this
  }
  
  /**
   * Handles several Rejections of the same type at the same time.
   * The list passed to the given function is guaranteed to be non-empty.
   */
  def handleAll[T <: Rejection](t: Class[T], handler: function.Function[java.util.List[T], Route]): RejectionHandlerBuilder = {
    asScala.handleAll { rejections:collection.immutable.Seq[T] => handler.apply(rejections.asJava).toScala } (ClassTag(t))
    this
  }
  
  /**
   * Handles the special "not found" case using the given [[Route]].
   */
  def handleNotFound(route: Route): RejectionHandlerBuilder = {
    asScala.handleNotFound(route.toScala)
    this
  }
}
