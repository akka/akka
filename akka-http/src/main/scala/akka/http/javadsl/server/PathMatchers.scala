/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server

import java.util.UUID
import java.util.regex.Pattern

import akka.http.scaladsl.model.Uri.Path

import scala.collection.JavaConverters._

import akka.http.scaladsl.server.{ PathMatcher ⇒ SPathMatcher }
import akka.http.scaladsl.server.{ PathMatchers ⇒ SPathMatchers }
import akka.http.javadsl.server.RegexConverters.toScala

final class PathMatchers

object PathMatchers {
  import JavaPathMatchers._

  private[this] val IntegerSegment: PathMatcher1[java.lang.Integer] = fromScala1(SPathMatchers.IntNumber.map { i ⇒ i: java.lang.Integer })
  private[this] val LongSegment: PathMatcher1[java.lang.Long] = fromScala1(SPathMatchers.LongNumber.map { i ⇒ i: java.lang.Long })
  private[this] val HexIntegerSegment: PathMatcher1[java.lang.Integer] = fromScala1(SPathMatchers.HexIntNumber.map { i ⇒ i: java.lang.Integer })
  private[this] val HexLongSegment: PathMatcher1[java.lang.Long] = fromScala1(SPathMatchers.HexLongNumber.map { i ⇒ i: java.lang.Long })
  private[this] val DoubleSegment: PathMatcher1[java.lang.Double] = fromScala1(SPathMatchers.DoubleNumber.map { i ⇒ i: java.lang.Double })
  private[this] val UUIDSegment: PathMatcher1[UUID] = fromScala1(SPathMatchers.JavaUUID)

  private[this] val Neutral = fromScala0(SPathMatchers.Neutral)
  private[this] val Slash = new PathMatcher0(SPathMatchers.Slash)
  private[this] val PathEnd = new PathMatcher0(SPathMatchers.PathEnd)
  private[this] val Remaining = new PathMatcher1[String](SPathMatchers.Remaining)
  private[this] val RemainingPath = new PathMatcher1[Path](SPathMatchers.RemainingPath)
  private[this] val Segment = new PathMatcher1[String](SPathMatchers.Segment)
  private[this] val Segments = new PathMatcher1[java.util.List[String]](SPathMatchers.Segments.map(_.asJava))

  /**
   * Converts a path string containing slashes into a PathMatcher that interprets slashes as
   * path segment separators.
   */
  def separateOnSlashes(segments: String): PathMatcher0 = fromScala0(SPathMatchers.separateOnSlashes(segments))

  /**
   * A PathMatcher that matches a single slash character ('/').
   */
  def slash(): PathMatcher0 = Slash

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * (if the path begins with a segment).
   */
  def segment(segment: String): PathMatcher0 = new PathMatcher0(SPathMatcher(segment))

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * if the path begins with a segment (a prefix of) which matches the given regex.
   * Extracts either the complete match (if the regex doesn't contain a capture group) or
   * the capture group (if the regex contains exactly one).
   * If the regex contains more than one capture group the method throws an IllegalArgumentException.
   */
  def segment(regex: Pattern): PathMatcher1[String] = new PathMatcher1[String](SPathMatcher[Tuple1[String]](toScala(regex)))

  /**
   * A PathMatcher that matches between `min` and `max` (both inclusively) path segments (separated by slashes)
   * as a List[String]. If there are more than `count` segments present the remaining ones will be left unmatched.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  def segments(min: Int, max: Int): PathMatcher1[java.util.List[String]] = new PathMatcher1[java.util.List[String]](SPathMatchers.Segments(min, max).map(_.asJava))

  /**
   * A PathMatcher that matches the given number of path segments (separated by slashes) as a List[String].
   * If there are more than `count` segments present the remaining ones will be left unmatched.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  def segments(count: Int) = new PathMatcher1[java.util.List[String]](SPathMatchers.Segments(count).map(_.asJava))

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than Int.MaxValue.
   */
  def integerSegment: PathMatcher1[java.lang.Integer] = IntegerSegment

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than Long.MaxValue.
   */
  def longSegment: PathMatcher1[java.lang.Long] = LongSegment

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than Int.MaxValue.
   */
  def hexIntegerSegment: PathMatcher1[java.lang.Integer] = HexIntegerSegment

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than Long.MaxValue.
   */
  def hexLongSegment: PathMatcher1[java.lang.Long] = HexLongSegment

  /**
   * A PathMatcher that matches and extracts a Double value. The matched string representation is the pure decimal,
   * optionally signed form of a double value, i.e. without exponent.
   */
  def doubleSegment: PathMatcher1[java.lang.Double] = DoubleSegment

  /**
   * A PathMatcher that matches and extracts a java.util.UUID instance.
   */
  def uuidSegment: PathMatcher1[UUID] = UUIDSegment

  /**
   * A PathMatcher that always matches, doesn't consume anything and extracts nothing.
   * Serves mainly as a neutral element in PathMatcher composition.
   */
  def neutral: PathMatcher0 = Neutral

  /**
   * A PathMatcher that matches the very end of the requests URI path.
   */
  def pathEnd: PathMatcher0 = PathEnd

  /**
   * A PathMatcher that matches and extracts the complete remaining,
   * unmatched part of the request's URI path as an (encoded!) String.
   */
  def remaining: PathMatcher1[String] = Remaining

  /**
   * A PathMatcher that matches and extracts the complete remaining,
   * unmatched part of the request's URI path.
   */
  def remainingPath: PathMatcher1[Path] = RemainingPath

  /**
   * A PathMatcher that matches if the unmatched path starts with a path segment.
   * If so the path segment is extracted as a String.
   */
  def segment: PathMatcher1[String] = Segment

  /**
   * A PathMatcher that matches up to 128 remaining segments as a List[String].
   * This can also be no segments resulting in the empty list.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  def segments: PathMatcher1[java.util.List[String]] = Segments

}

