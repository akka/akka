/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.values

import java.util.Optional
import java.{ lang ⇒ jl, util ⇒ ju }

import akka.http.impl.server.PathMatcherImpl
import akka.http.javadsl.server.RequestVal
import akka.http.scaladsl.server.{ PathMatcher0, PathMatcher1, PathMatchers ⇒ ScalaPathMatchers }
import akka.japi.function.Function

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
 * A PathMatcher is used to match the (yet unmatched) URI path of incoming requests.
 * It is also a RequestVal that allows to access dynamic parts of the part in a
 * handler.
 *
 * Using a PathMatcher with the [[akka.http.javadsl.server.Directives#path]] or [[akka.http.javadsl.server.Directives#pathPrefix]] directives
 * "consumes" a part of the path which is recorded in [[akka.http.javadsl.server.RequestContext#unmatchedPath]].
 */
trait PathMatcher[T] extends RequestVal[T] {
  def optional: PathMatcher[Optional[T]]
}

/**
 * A collection of predefined path matchers.
 */
object PathMatchers {
  /**
   * A PathMatcher that always matches, doesn't consume anything and extracts nothing.
   * Serves mainly as a neutral element in PathMatcher composition.
   */
  val NEUTRAL: PathMatcher[Void] = matcher0(_.Neutral)

  /**
   * A PathMatcher that matches a single slash character ('/').
   */
  val SLASH: PathMatcher[Void] = matcher0(_.Slash)

  /**
   * A PathMatcher that matches the very end of the requests URI path.
   */
  val END: PathMatcher[Void] = matcher0(_.PathEnd)

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * (if the path begins with a segment) and extracts a given value.
   */
  def segment(name: String): PathMatcher[String] = matcher(_ ⇒ name -> name)

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than [[java.lang.Integer.MAX_VALUE]].
   */
  def intValue: PathMatcher[jl.Integer] = matcher(_.IntNumber.asInstanceOf[PathMatcher1[jl.Integer]])

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than [[java.lang.Integer.MAX_VALUE]].
   */
  def hexIntValue: PathMatcher[jl.Integer] = matcher(_.HexIntNumber.asInstanceOf[PathMatcher1[jl.Integer]])

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than [[java.lang.Long.MAX_VALUE]].
   */
  def longValue: PathMatcher[jl.Long] = matcher(_.LongNumber.asInstanceOf[PathMatcher1[jl.Long]])

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than [[java.lang.Long.MAX_VALUE]].
   */
  def hexLongValue: PathMatcher[jl.Long] = matcher(_.HexLongNumber.asInstanceOf[PathMatcher1[jl.Long]])

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * if the path begins with a segment (a prefix of) which matches the given regex.
   * Extracts either the complete match (if the regex doesn't contain a capture group) or
   * the capture group (if the regex contains exactly one).
   * If the regex contains more than one capture group the method throws an IllegalArgumentException.
   */
  def regex(regex: String): PathMatcher[String] = matcher(_ ⇒ regex.r)

  /**
   * A PathMatcher that matches and extracts a java.util.UUID instance.
   */
  def uuid: PathMatcher[ju.UUID] = matcher(_.JavaUUID)

  /**
   * A PathMatcher that matches if the unmatched path starts with a path segment.
   * If so the path segment is extracted as a String.
   */
  def segment: PathMatcher[String] = matcher(_.Segment)

  /**
   * A PathMatcher that matches up to 128 remaining segments as a List[String].
   * This can also be no segments resulting in the empty list.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  def segments: PathMatcher[ju.List[String]] = matcher(_.Segments.map(_.asJava))

  /**
   * A PathMatcher that matches the given number of path segments (separated by slashes) as a List[String].
   * If there are more than `count` segments present the remaining ones will be left unmatched.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  def segments(maxNumber: Int): PathMatcher[ju.List[String]] = matcher(_.Segments(maxNumber).map(_.asJava))

  /**
   * A PathMatcher that matches and extracts the complete remaining,
   * unmatched part of the request's URI path as an (encoded!) String.
   * If you need access to the remaining unencoded elements of the path
   * use the `RestPath` matcher!
   */
  def rest: PathMatcher[String] = matcher(_.Rest)

  def segmentFromString[T](convert: Function[String, T], clazz: Class[T]): PathMatcher[T] =
    matcher(_ ⇒ ScalaPathMatchers.Segment.map(convert(_)))(ClassTag(clazz))

  private def matcher[T: ClassTag](scalaMatcher: ScalaPathMatchers.type ⇒ PathMatcher1[T]): PathMatcher[T] =
    new PathMatcherImpl[T](scalaMatcher(ScalaPathMatchers))
  private def matcher0(scalaMatcher: ScalaPathMatchers.type ⇒ PathMatcher0): PathMatcher[Void] =
    new PathMatcherImpl[Void](scalaMatcher(ScalaPathMatchers).tmap(_ ⇒ Tuple1(null)))
}
