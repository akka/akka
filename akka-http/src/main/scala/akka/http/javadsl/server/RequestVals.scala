/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

import java.util.regex.Pattern
import java.{ util ⇒ ju }
import scala.concurrent.Future
import scala.reflect.ClassTag
import akka.http.javadsl.model.{ RemoteAddress, HttpMethod }
import akka.http.scaladsl.server
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives._
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
        val u = unmarshaller.asInstanceOf[UnmarshallerImpl[T]].scalaUnmarshaller
        u(ctx.request)(ctx.executionContext, ctx.materializer)
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
   * Extracts the scheme used for this request.
   */
  def requestContext: RequestVal[RequestContext] =
    new StandaloneExtractionImpl[RequestContext] {
      def directive: Directive1[RequestContext] = BasicDirectives.extractRequestContext.map(RequestContextImpl(_): RequestContext)
    }

  /**
   * Extracts the unmatched path of the request context.
   */
  def unmatchedPath: RequestVal[String] =
    new ExtractingStandaloneExtractionImpl[String] {
      def extract(ctx: server.RequestContext): Future[String] = FastFuture.successful(ctx.unmatchedPath.toString)
    }

  /**
   * Extracts the scheme used for this request.
   */
  def scheme: RequestVal[String] =
    new StandaloneExtractionImpl[String] {
      def directive: Directive1[String] = SchemeDirectives.extractScheme
    }

  /**
   * Extracts the host name this request targeted.
   */
  def host: RequestVal[String] =
    new StandaloneExtractionImpl[String] {
      def directive: Directive1[String] = HostDirectives.extractHost
    }

  /**
   * Extracts the host name this request targeted.
   */
  def matchAndExtractHost(regex: Pattern): RequestVal[String] =
    new StandaloneExtractionImpl[String] {
      // important to use a val here so that invalid patterns are
      // detected at construction and `IllegalArgumentException` is thrown
      override val directive: Directive1[String] = HostDirectives.host(regex.pattern().r)
    }

  /**
   * Directive extracting the IP of the client from either the X-Forwarded-For, Remote-Address or X-Real-IP header
   * (in that order of priority).
   *
   * TODO: add link to the configuration entry that would add a remote-address header
   */
  def clientIP(): RequestVal[RemoteAddress] =
    new StandaloneExtractionImpl[RemoteAddress] {
      def directive: Directive1[RemoteAddress] =
        MiscDirectives.extractClientIP.map(x ⇒ x: RemoteAddress) // missing covariance of Directive
    }

  /**
   * Creates a new [[RequestVal]] given a [[java.util.Map]] and a [[RequestVal]] that represents the key.
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
