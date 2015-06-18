/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server.values

import java.{ lang ⇒ jl, util ⇒ ju }

import akka.http.impl.server.PathMatcherImpl
import akka.http.javadsl.server.RequestVal
import akka.http.scaladsl.server.{ PathMatcher0, PathMatcher1, PathMatchers ⇒ ScalaPathMatchers }
import akka.japi.Option

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * A PathMatcher is used to match the (yet unmatched) URI path of incoming requests.
 * It is also a RequestVal that allows to access dynamic parts of the part in a
 * handler.
 *
 * Using a PathMatcher with the [[Directives.path]] or [[Directives.pathPrefix]] directives
 * "consumes" a part of the path which is recorded in [[RequestContext.unmatchedPath]].
 */
trait PathMatcher[T] extends RequestVal[T] {
  def optional: PathMatcher[Option[T]]
}

/**
 * A collection of predefined path matchers.
 */
object PathMatchers {
  val NEUTRAL: PathMatcher[Void] = matcher0(_.Neutral)
  val SLASH: PathMatcher[Void] = matcher0(_.Slash)
  val END: PathMatcher[Void] = matcher0(_.PathEnd)

  def segment(name: String): PathMatcher[String] = matcher(_ ⇒ name -> name)

  def integerNumber: PathMatcher[jl.Integer] = matcher(_.IntNumber.asInstanceOf[PathMatcher1[jl.Integer]])
  def hexIntegerNumber: PathMatcher[jl.Integer] = matcher(_.HexIntNumber.asInstanceOf[PathMatcher1[jl.Integer]])

  def longNumber: PathMatcher[jl.Long] = matcher(_.LongNumber.asInstanceOf[PathMatcher1[jl.Long]])
  def hexLongNumber: PathMatcher[jl.Long] = matcher(_.HexLongNumber.asInstanceOf[PathMatcher1[jl.Long]])

  def uuid: PathMatcher[ju.UUID] = matcher(_.JavaUUID)

  def segment: PathMatcher[String] = matcher(_.Segment)
  def segments: PathMatcher[ju.List[String]] = matcher(_.Segments.map(_.asJava))
  def segments(maxNumber: Int): PathMatcher[ju.List[String]] = matcher(_.Segments(maxNumber).map(_.asJava))

  def rest: PathMatcher[String] = matcher(_.Rest)

  private def matcher[T: ClassTag](scalaMatcher: ScalaPathMatchers.type ⇒ PathMatcher1[T]): PathMatcher[T] =
    new PathMatcherImpl[T](scalaMatcher(ScalaPathMatchers))
  private def matcher0(scalaMatcher: ScalaPathMatchers.type ⇒ PathMatcher0): PathMatcher[Void] =
    new PathMatcherImpl[Void](scalaMatcher(ScalaPathMatchers).tmap(_ ⇒ Tuple1(null)))
}
