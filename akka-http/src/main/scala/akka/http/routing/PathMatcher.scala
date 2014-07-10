/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing

import java.util.UUID
import scala.util.matching.Regex
import scala.annotation.tailrec
import akka.shapeless._
import directives.NameReceptacle
import akka.http.model.Uri.Path
import akka.http.util._

/**
 * A PathMatcher tries to match a prefix of a given string and returns either a PathMatcher.Matched instance
 * if matched, otherwise PathMatcher.Unmatched.
 */
trait PathMatcher[L <: HList] extends (Path ⇒ PathMatcher.Matching[L]) { self ⇒
  import PathMatcher._

  def / : PathMatcher[L] = this ~ PathMatchers.Slash

  def /[R <: HList](other: PathMatcher[R])(implicit prepender: Prepender[L, R]): PathMatcher[prepender.Out] =
    this ~ PathMatchers.Slash ~ other

  def |[R >: L <: HList](other: PathMatcher[_ <: R]): PathMatcher[R] =
    new PathMatcher[R] {
      def apply(path: Path) = self(path) orElse other(path)
    }

  def ~[R <: HList](other: PathMatcher[R])(implicit prepender: Prepender[L, R]): PathMatcher[prepender.Out] =
    transform(_.andThen((restL, valuesL) ⇒ other(restL).map(prepender(valuesL, _))))

  def unary_!(): PathMatcher0 =
    new PathMatcher[HNil] {
      def apply(path: Path) = if (self(path) eq Unmatched) Matched(path, HNil) else Unmatched
    }

  def transform[R <: HList](f: Matching[L] ⇒ Matching[R]): PathMatcher[R] =
    new PathMatcher[R] { def apply(path: Path) = f(self(path)) }

  def hmap[R <: HList](f: L ⇒ R): PathMatcher[R] = transform(_.map(f))

  def hflatMap[R <: HList](f: L ⇒ Option[R]): PathMatcher[R] = transform(_.flatMap(f))

  def repeat(separator: PathMatcher0 = PathMatchers.Neutral)(implicit lift: PathMatcher.Lift[L, List]): PathMatcher[lift.Out] =
    new PathMatcher[lift.Out] {
      def apply(path: Path) = self(path) match {
        case Matched(remaining, extractions) ⇒
          def result1 = Matched(remaining, lift(extractions))
          separator(remaining) match {
            case Matched(remaining2, _) ⇒ this(remaining2) match {
              case Matched(`remaining2`, _) ⇒ result1 // we made no progress, so "go back" to before the separator
              case Matched(rest, result)    ⇒ Matched(rest, lift(extractions, result))
              case Unmatched                ⇒ throw new IllegalStateException
            }
            case Unmatched ⇒ result1
          }
        case Unmatched ⇒ Matched(path, lift())
      }
    }
}

object PathMatcher extends ImplicitPathMatcherConstruction {
  sealed trait Matching[+L <: HList] {
    def map[R <: HList](f: L ⇒ R): Matching[R]
    def flatMap[R <: HList](f: L ⇒ Option[R]): Matching[R]
    def andThen[R <: HList](f: (Path, L) ⇒ Matching[R]): Matching[R]
    def orElse[R >: L <: HList](other: ⇒ Matching[R]): Matching[R]
  }
  case class Matched[L <: HList](pathRest: Path, extractions: L) extends Matching[L] {
    def map[R <: HList](f: L ⇒ R) = Matched(pathRest, f(extractions))
    def flatMap[R <: HList](f: L ⇒ Option[R]) = f(extractions) match {
      case Some(valuesR) ⇒ Matched(pathRest, valuesR)
      case None          ⇒ Unmatched
    }
    def andThen[R <: HList](f: (Path, L) ⇒ Matching[R]) = f(pathRest, extractions)
    def orElse[R >: L <: HList](other: ⇒ Matching[R]) = this
  }
  object Matched { val Empty = Matched(Path.Empty, HNil) }
  case object Unmatched extends Matching[Nothing] {
    def map[R <: HList](f: Nothing ⇒ R) = this
    def flatMap[R <: HList](f: Nothing ⇒ Option[R]) = this
    def andThen[R <: HList](f: (Path, Nothing) ⇒ Matching[R]) = this
    def orElse[R <: HList](other: ⇒ Matching[R]) = other
  }

  /**
   * Creates a PathMatcher that always matches, consumes nothing and extracts the given HList of values.
   */
  def provide[L <: HList](extractions: L): PathMatcher[L] =
    new PathMatcher[L] {
      def apply(path: Path) = Matched(path, extractions)
    }

  /**
   * Creates a PathMatcher that matches and consumes the given path prefix and extracts the given list of extractions.
   * If the given prefix is empty the returned PathMatcher matches always and consumes nothing.
   */
  def apply[L <: HList](prefix: Path, extractions: L): PathMatcher[L] =
    if (prefix.isEmpty) provide(extractions)
    else new PathMatcher[L] {
      def apply(path: Path) =
        if (path startsWith prefix) Matched(path dropChars prefix.charCount, extractions)
        else Unmatched
    }

  def apply[L <: HList](magnet: PathMatcher[L]): PathMatcher[L] = magnet

  implicit class PathMatcher1Ops[T](matcher: PathMatcher1[T]) {
    def map[R](f: T ⇒ R): PathMatcher1[R] = matcher.hmap { case e :: HNil ⇒ f(e) :: HNil }
    def flatMap[R](f: T ⇒ Option[R]): PathMatcher1[R] =
      matcher.hflatMap { case e :: HNil ⇒ f(e).map(_ :: HNil) }
  }

  implicit class PimpedPathMatcher[L <: HList](underlying: PathMatcher[L]) {
    def ?(implicit lift: PathMatcher.Lift[L, Option]): PathMatcher[lift.Out] =
      new PathMatcher[lift.Out] {
        def apply(path: Path) = underlying(path) match {
          case Matched(rest, extractions) ⇒ Matched(rest, lift(extractions))
          case Unmatched                  ⇒ Matched(path, lift())
        }
      }
  }

  sealed trait Lift[L <: HList, M[+_]] {
    type Out <: HList
    def apply(): Out
    def apply(value: L): Out
    def apply(value: L, more: Out): Out
  }
  object Lift {
    trait MOps[M[+_]] {
      def apply(): M[Nothing]
      def apply[T](value: T): M[T]
      def apply[T](value: T, more: M[T]): M[T]
    }
    object MOps {
      implicit object OptionMOps extends MOps[Option] {
        def apply(): Option[Nothing] = None
        def apply[T](value: T): Option[T] = Some(value)
        def apply[T](value: T, more: Option[T]): Option[T] = Some(value)
      }
      implicit object ListMOps extends MOps[List] {
        def apply(): List[Nothing] = Nil
        def apply[T](value: T): List[T] = value :: Nil
        def apply[T](value: T, more: List[T]): List[T] = value :: more
      }
    }
    implicit def liftHNil[M[+_]] = new Lift[HNil, M] {
      type Out = HNil
      def apply() = HNil
      def apply(value: HNil) = value
      def apply(value: HNil, more: Out) = value
    }
    implicit def liftSingleElement[A, M[+_]](implicit mops: MOps[M]) = new Lift[A :: HNil, M] {
      type Out = M[A] :: HNil
      def apply() = mops() :: HNil
      def apply(value: A :: HNil) = mops(value.head) :: HNil
      def apply(value: A :: HNil, more: Out) = mops(value.head, more.head) :: HNil
    }
    implicit def default[A, B, L <: HList, M[+_]](implicit mops: MOps[M]) = new Lift[A :: B :: L, M] {
      type Out = M[A :: B :: L] :: HNil
      def apply() = mops() :: HNil
      def apply(value: A :: B :: L) = mops(value) :: HNil
      def apply(value: A :: B :: L, more: Out) = mops(value, more.head) :: HNil
    }
  }
}

trait ImplicitPathMatcherConstruction {
  import PathMatcher._

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * (if the path begins with a segment) and extracts a given value.
   */
  implicit def stringExtractionPair2PathMatcher[T](tuple: (String, T)): PathMatcher1[T] =
    PathMatcher(tuple._1 :: Path.Empty, tuple._2 :: HNil)

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * (if the path begins with a segment).
   */
  implicit def segmentStringToPathMatcher(segment: String): PathMatcher0 =
    PathMatcher(segment :: Path.Empty, HNil)

  implicit def stringOptionNameReceptacle2PathMatcher(nr: NameReceptacle[Option[String]]): PathMatcher0 =
    PathMatcher(nr.name).?

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * if the path begins with a segment (a prefix of) which matches the given regex.
   * Extracts either the complete match (if the regex doesn't contain a capture group) or
   * the capture group (if the regex contains exactly one).
   * If the regex contains more than one capture group the method throws an IllegalArgumentException.
   */
  implicit def regex2PathMatcher(regex: Regex): PathMatcher1[String] = regex.groupCount match {
    case 0 ⇒ new PathMatcher1[String] {
      def apply(path: Path) = path match {
        case Path.Segment(segment, tail) ⇒ regex findPrefixOf segment match {
          case Some(m) ⇒ Matched(segment.substring(m.length) :: tail, m :: HNil)
          case None    ⇒ Unmatched
        }
        case _ ⇒ Unmatched
      }
    }
    case 1 ⇒ new PathMatcher1[String] {
      def apply(path: Path) = path match {
        case Path.Segment(segment, tail) ⇒ regex findPrefixMatchOf segment match {
          case Some(m) ⇒ Matched(segment.substring(m.end) :: tail, m.group(1) :: HNil)
          case None    ⇒ Unmatched
        }
        case _ ⇒ Unmatched
      }
    }
    case _ ⇒ throw new IllegalArgumentException("Path regex '" + regex.pattern.pattern +
      "' must not contain more than one capturing group")
  }
  /**
   * Creates a PathMatcher from the given Map of path segments (prefixes) to extracted values.
   * If the unmatched path starts with a segment having one of the maps keys as a prefix
   * the matcher consumes this path segment (prefix) and extracts the corresponding map value.
   */
  implicit def valueMap2PathMatcher[T](valueMap: Map[String, T]): PathMatcher1[T] =
    valueMap.map { case (prefix, value) ⇒ stringExtractionPair2PathMatcher(prefix, value) }.reduceLeft(_ | _)
}

trait PathMatchers {
  import PathMatcher._

  /**
   * Converts a path string containing slashes into a PathMatcher that interprets slashes as
   * path segment separators.
   */
  def separateOnSlashes(string: String): PathMatcher0 = {
    @tailrec def split(ix: Int = 0, matcher: PathMatcher0 = null): PathMatcher0 = {
      val nextIx = string.indexOf('/', ix)
      def append(m: PathMatcher0) = if (matcher eq null) m else matcher / m
      if (nextIx < 0) append(string.substring(ix))
      else split(nextIx + 1, append(string.substring(ix, nextIx)))
    }
    split()
  }

  /**
   * A PathMatcher that matches a single slash character ('/').
   */
  object Slash extends PathMatcher0 {
    def apply(path: Path) = path match {
      case Path.Slash(tail) ⇒ Matched(tail, HNil)
      case _                ⇒ Unmatched
    }
  }

  /**
   * A PathMatcher that matches the very end of the requests URI path.
   */
  object PathEnd extends PathMatcher0 {
    def apply(path: Path) = path match {
      case Path.Empty ⇒ Matched.Empty
      case _          ⇒ Unmatched
    }
  }

  /**
   * A PathMatcher that matches and extracts the complete remaining,
   * unmatched part of the request's URI path as an (encoded!) String.
   * If you need access to the remaining unencoded elements of the path
   * use the `RestPath` matcher!
   */
  object Rest extends PathMatcher1[String] {
    def apply(path: Path) = Matched(Path.Empty, path.toString :: HNil)
  }

  /**
   * A PathMatcher that matches and extracts the complete remaining,
   * unmatched part of the request's URI path.
   */
  object RestPath extends PathMatcher1[Path] {
    def apply(path: Path) = Matched(Path.Empty, path :: HNil)
  }

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than Int.MaxValue.
   */
  object IntNumber extends NumberMatcher[Int](Int.MaxValue, 10) {
    def fromChar(c: Char) = fromDecimalChar(c)
  }

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than Long.MaxValue.
   */
  object LongNumber extends NumberMatcher[Long](Long.MaxValue, 10) {
    def fromChar(c: Char) = fromDecimalChar(c)
  }

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than Int.MaxValue.
   */
  object HexIntNumber extends NumberMatcher[Int](Int.MaxValue, 16) {
    def fromChar(c: Char) = fromHexChar(c)
  }

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than Long.MaxValue.
   */
  object HexLongNumber extends NumberMatcher[Long](Long.MaxValue, 16) {
    def fromChar(c: Char) = fromHexChar(c)
  }

  // common implementation of Number matchers
  abstract class NumberMatcher[@specialized(Int, Long) T](max: T, base: T)(implicit x: Integral[T])
    extends PathMatcher1[T] {

    import x._ // import implicit conversions for numeric operators
    val minusOne = x.zero - x.one
    val maxDivBase = max / base

    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) ⇒
        @tailrec def digits(ix: Int = 0, value: T = minusOne): Matching[T :: HNil] = {
          val a = if (ix < segment.length) fromChar(segment charAt ix) else minusOne
          if (a == minusOne) {
            if (value == minusOne) Unmatched
            else Matched(if (ix < segment.length) segment.substring(ix) :: tail else tail, value :: HNil)
          } else {
            if (value == minusOne) digits(ix + 1, a)
            else if (value <= maxDivBase && value * base <= max - a) // protect from overflow
              digits(ix + 1, value * base + a)
            else Unmatched
          }
        }
        digits()

      case _ ⇒ Unmatched
    }

    def fromChar(c: Char): T

    def fromDecimalChar(c: Char): T = if ('0' <= c && c <= '9') (c - '0').asInstanceOf[T] else minusOne

    def fromHexChar(c: Char): T =
      if ('0' <= c && c <= '9') (c - '0').asInstanceOf[T] else {
        val cn = c | 0x20 // normalize to lowercase
        if ('a' <= cn && cn <= 'f') (cn - 'a' + 10).asInstanceOf[T] else
          minusOne
      }
  }

  /**
   * A PathMatcher that matches and extracts a Double value. The matched string representation is the pure decimal,
   * optionally signed form of a double value, i.e. without exponent.
   */
  val DoubleNumber: PathMatcher1[Double] =
    PathMatcher("""[+-]?\d*\.?\d*""".r) flatMap { string ⇒
      try Some(java.lang.Double.parseDouble(string))
      catch { case _: NumberFormatException ⇒ None }
    }

  /**
   * A PathMatcher that matches and extracts a java.util.UUID instance.
   */
  val JavaUUID: PathMatcher1[UUID] =
    PathMatcher("""[\da-fA-F]{8}-[\da-fA-F]{4}-[\da-fA-F]{4}-[\da-fA-F]{4}-[\da-fA-F]{12}""".r) flatMap { string ⇒
      try Some(UUID.fromString(string))
      catch { case _: IllegalArgumentException ⇒ None }
    }

  /**
   * A PathMatcher that always matches, doesn't consume anything and extracts nothing.
   * Serves mainly as a neutral element in PathMatcher composition.
   */
  val Neutral: PathMatcher0 = PathMatcher.provide(HNil)

  /**
   * A PathMatcher that matches if the unmatched path starts with a path segment.
   * If so the path segment is extracted as a String.
   */
  object Segment extends PathMatcher1[String] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) ⇒ Matched(tail, segment :: HNil)
      case _                           ⇒ Unmatched
    }
  }

  /**
   * A PathMatcher that matches all remaining segments as a List[String].
   * This can also be no segments resulting in the empty list.
   * If the path has a trailing slash this slash will *not* be matched.
   */
  val Segments: PathMatcher1[List[String]] = Segment.repeat(separator = Slash)

  @deprecated("Use `Segment` instead", "1.0-M8/1.1-M8")
  def PathElement = Segment
}

object PathMatchers extends PathMatchers
