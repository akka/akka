/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

import java.{ util ⇒ ju }
import scala.concurrent.Future
import scala.reflect.ClassTag
import akka.http.javadsl.model.HttpMethod
import akka.http.scaladsl.server
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.{ RouteDirectives, BasicDirectives }
import akka.http.impl.server.{ UnmarshallerImpl, ExtractingStandaloneExtractionImpl, RequestContextImpl, StandaloneExtractionImpl }
import akka.http.scaladsl.util.FastFuture
import akka.http.impl.util.JavaMapping.Implicits._

/**
 * A collection of predefined [[RequestVals]].
 */
object RequestVals {
  /**
   * Creates an extraction that extracts the request body using the supplied Unmarshaller.
   */
  def entityAs[T](unmarshaller: Unmarshaller[T]): RequestVal[T] =
    new ExtractingStandaloneExtractionImpl[T]()(unmarshaller.classTag) {
      def extract(ctx: server.RequestContext): Future[T] = {
        val u = unmarshaller.asInstanceOf[UnmarshallerImpl[T]].scalaUnmarshaller(ctx.executionContext, ctx.materializer)
        u(ctx.request)(ctx.executionContext)
      }
    }

  /**
   * Extracts the request method.
   */
  def requestMethod: RequestVal[HttpMethod] =
    new ExtractingStandaloneExtractionImpl[HttpMethod] {
      def extract(ctx: server.RequestContext): Future[HttpMethod] = FastFuture.successful(ctx.request.method.asJava)
    }

  /**
   * Creates a new [[RequestVal]] given a [[ju.Map]] and a [[RequestVal]] that represents the key.
   * The new RequestVal represents the existing value as looked up in the map. If the key doesn't
   * exist the request is rejected.
   */
  def lookupInMap[T, U](key: RequestVal[T], clazz: Class[U], map: ju.Map[T, U]): RequestVal[U] =
    new StandaloneExtractionImpl[U]()(ClassTag(clazz)) {
      import BasicDirectives._
      import RouteDirectives._

      def directive: Directive1[U] =
        extract(ctx ⇒ key.get(RequestContextImpl(ctx))).flatMap {
          case key if map.containsKey(key) ⇒ provide(map.get(key))
          case _                           ⇒ reject()
        }
    }
}
