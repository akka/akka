/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server

import java.util.{ List ⇒ JList }
import java.util.UUID
import java.util.function.BiFunction
import java.util.function.{ Function ⇒ JFunction }
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import PathMatcher.fromScala0
import PathMatcher.fromScala1
import PathMatcher.fromScala2
import PathMatchersScala.Neutral
import akka.http.scaladsl.server.{ PathMatcher ⇒ SPathMatcher }
import akka.http.scaladsl.server.{ PathMatchers ⇒ SPathMatchers }
import akka.http.javadsl.server.RegexConverters.toScala

class PathMatcher0(val toScala: SPathMatcher[Unit]) {
  def slash() = fromScala0(toScala./)

  def slash(segment: String) = fromScala0(toScala / segment)
  def slash(next: PathMatcher0) = fromScala0(toScala / next.toScala)
  def slash[T](next: PathMatcher1[T]) = fromScala1(toScala / next.toScala)
  def slash[T1, T2](next: PathMatcher2[T1, T2]) = fromScala2(toScala / next.toScala)

  def concat(segment: String) = fromScala0(toScala ~ segment)
  def concat(next: PathMatcher0) = fromScala0(toScala ~ next.toScala)
  def concat[T](next: PathMatcher1[T]) = fromScala1(toScala ~ next.toScala)
  def concat[T1, T2](next: PathMatcher2[T1, T2]) = fromScala2(toScala ~ next.toScala)

  def orElse(segment: String) = fromScala0(toScala | segment)
  def orElse(alternative: PathMatcher0) = fromScala0(toScala | alternative.toScala)

  def invert = fromScala0(!toScala)

  def repeat(min: Int, max: Int): PathMatcher0 = repeat(min, max, Neutral)
  def repeat(min: Int, max: Int, separator: PathMatcher0): PathMatcher0 = fromScala0(toScala.repeat(min, max, separator.toScala))
}

class PathMatcher1[T](val toScala: SPathMatcher[Tuple1[T]]) {
  def slash[T2](next: PathMatcher1[T2]) = fromScala2(toScala./(next.toScala))
  def slash() = fromScala1(toScala./)
  def slash(segment: String) = fromScala1(toScala / segment)
  def slash(next: PathMatcher0) = fromScala1(toScala / next.toScala)

  def concat(segment: String) = fromScala1(toScala ~ segment)
  def concat(next: PathMatcher0) = fromScala1(toScala ~ next.toScala)
  def concat[T2](next: PathMatcher1[T2]) = fromScala2(toScala ~ next.toScala)

  def orElse(alternative: PathMatcher1[T]) = fromScala1(toScala | alternative.toScala)

  def invert = fromScala0(!toScala)

  def repeat(min: Int, max: Int): PathMatcher1[JList[T]] = repeat(min, max, Neutral)
  def repeat(min: Int, max: Int, separator: PathMatcher0): PathMatcher1[JList[T]] =
    fromScala1(toScala.repeat(min, max, separator.toScala).map(_.asJava))

  def map[U](f: JFunction[T, U]) = new PathMatcher1[U](toScala.map(t ⇒ f.apply(t)))
}

case class PathMatcher2[T1, T2](toScala: SPathMatcher[(T1, T2)]) {
  def slash() = fromScala2(toScala./)
  def slash(segment: String) = fromScala2(toScala / segment)
  def slash(next: PathMatcher0) = fromScala2(toScala / next.toScala)

  def concat(segment: String) = fromScala2(toScala ~ segment)
  def concat(next: PathMatcher0) = fromScala2(toScala ~ next.toScala)

  def orElse(alternative: PathMatcher2[T1, T2]) = fromScala2(toScala | alternative.toScala)

  def invert = fromScala0(!toScala)

  def repeat(min: Int, max: Int): PathMatcher1[JList[(T1, T2)]] = repeat(min, max, Neutral)
  def repeat(min: Int, max: Int, separator: PathMatcher0): PathMatcher1[JList[(T1, T2)]] =
    fromScala1(toScala.repeat(min, max, separator.toScala).map(_.asJava))

  def map[U](f: BiFunction[T1, T2, U]) = new PathMatcher1[U](toScala.tmap { case (t1, t2) ⇒ Tuple1(f.apply(t1, t2)) })
}

/**
 * INTERNAL API
 *
 * Contains Scala path matchers as object fields so they can be referred to from Java code.
 * @see PathMatchers for the actual Java constants to use.
 */
private[server] object PathMatchersScala {
  val IntegerSegment: PathMatcher1[java.lang.Integer] = fromScala1(SPathMatchers.IntNumber.map { i ⇒ i: java.lang.Integer })
  val LongSegment: PathMatcher1[java.lang.Long] = fromScala1(SPathMatchers.LongNumber.map { i ⇒ i: java.lang.Long })
  val HexIntegerSegment: PathMatcher1[java.lang.Integer] = fromScala1(SPathMatchers.HexIntNumber.map { i ⇒ i: java.lang.Integer })
  val HexLongSegment: PathMatcher1[java.lang.Long] = fromScala1(SPathMatchers.HexLongNumber.map { i ⇒ i: java.lang.Long })
  val DoubleSegment: PathMatcher1[java.lang.Double] = fromScala1(SPathMatchers.DoubleNumber.map { i ⇒ i: java.lang.Double })
  val UUIDSegment: PathMatcher1[UUID] = fromScala1(SPathMatchers.JavaUUID)

  val Neutral = fromScala0(SPathMatchers.Neutral)
  val Slash = new PathMatcher0(SPathMatchers.Slash)
  val PathEnd = new PathMatcher0(SPathMatchers.PathEnd)
  val Remaining = new PathMatcher1[String](SPathMatchers.Remaining)
  val Segment = new PathMatcher1[String](SPathMatchers.Segment)
  val Segments = new PathMatcher1[JList[String]](SPathMatchers.Segments.map(_.asJava))
}

object PathMatcher {
  /**
   * Converts a path string containing slashes into a PathMatcher that interprets slashes as
   * path segment separators.
   */
  def separateOnSlashes(segments: String): PathMatcher0 = fromScala0(SPathMatchers.separateOnSlashes(segments))

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * (if the path begins with a segment).
   */
  def segment(segment: String) = new PathMatcher0(SPathMatcher(segment))

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * if the path begins with a segment (a prefix of) which matches the given regex.
   * Extracts either the complete match (if the regex doesn't contain a capture group) or
   * the capture group (if the regex contains exactly one).
   * If the regex contains more than one capture group the method throws an IllegalArgumentException.
   */
  def segment(regex: Pattern) = new PathMatcher1[String](SPathMatcher[Tuple1[String]](toScala(regex)))

  /**
   * A PathMatcher that matches between `min` and `max` (both inclusively) path segments (separated by slashes)
   * as a List[String]. If there are more than `count` segments present the remaining ones will be left unmatched.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  def segments(min: Int, max: Int) = new PathMatcher1[JList[String]](SPathMatchers.Segments(min, max).map(_.asJava))

  /**
   * A PathMatcher that matches the given number of path segments (separated by slashes) as a List[String].
   * If there are more than `count` segments present the remaining ones will be left unmatched.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  def segments(count: Int) = new PathMatcher1[JList[String]](SPathMatchers.Segments(count).map(_.asJava))

  /** INTERNAL API */
  private[server] def fromScala0(scalaMatcher: SPathMatcher[Unit]) = new PathMatcher0(scalaMatcher)
  /** INTERNAL API */
  private[server] def fromScala1[T](scalaMatcher: SPathMatcher[Tuple1[T]]) = new PathMatcher1(scalaMatcher)
  /** INTERNAL API */
  private[server] def fromScala2[T1, T2](scalaMatcher: SPathMatcher[(T1, T2)]) = new PathMatcher2(scalaMatcher)
}

