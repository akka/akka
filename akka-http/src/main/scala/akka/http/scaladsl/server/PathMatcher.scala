/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import java.util.UUID
import scala.util.matching.Regex
import scala.annotation.tailrec
import akka.http.scaladsl.server.util.Tuple
import akka.http.scaladsl.server.util.TupleOps._
import akka.http.scaladsl.common.NameOptionReceptacle
import akka.http.scaladsl.model.Uri.Path
import akka.http.impl.util._

/**
 * A PathMatcher tries to match a prefix of a given string and returns either a PathMatcher.Matched instance
 * if matched, otherwise PathMatcher.Unmatched.
 */
abstract class PathMatcher[L](implicit val ev: Tuple[L]) extends (Path ⇒ PathMatcher.Matching[L]) { self ⇒
  import PathMatcher._

  def / : PathMatcher[L] = this ~ PathMatchers.Slash

  def /[R](other: PathMatcher[R])(implicit join: Join[L, R]): PathMatcher[join.Out] =
    this ~ PathMatchers.Slash ~ other

  def |[R >: L: Tuple](other: PathMatcher[_ <: R]): PathMatcher[R] =
    new PathMatcher[R] {
      def apply(path: Path) = self(path) orElse other(path)
    }

  def ~[R](other: PathMatcher[R])(implicit join: Join[L, R]): PathMatcher[join.Out] = {
    implicit val joinProducesTuple = Tuple.yes[join.Out]
    transform(_.andThen((restL, valuesL) ⇒ other(restL).map(join(valuesL, _))))
  }

  def unary_!(): PathMatcher0 =
    new PathMatcher[Unit] {
      def apply(path: Path) = if (self(path) eq Unmatched) Matched(path, ()) else Unmatched
    }

  def transform[R: Tuple](f: Matching[L] ⇒ Matching[R]): PathMatcher[R] =
    new PathMatcher[R] { def apply(path: Path) = f(self(path)) }

  def tmap[R: Tuple](f: L ⇒ R): PathMatcher[R] = transform(_.map(f))

  def tflatMap[R: Tuple](f: L ⇒ Option[R]): PathMatcher[R] = transform(_.flatMap(f))

  /**
   * Same as `repeat(min = count, max = count)`.
   */
  def repeat(count: Int)(implicit lift: PathMatcher.Lift[L, List]): PathMatcher[lift.Out] =
    repeat(min = count, max = count)

  /**
   * Same as `repeat(min = count, max = count, separator = separator)`.
   */
  def repeat(count: Int, separator: PathMatcher0)(implicit lift: PathMatcher.Lift[L, List]): PathMatcher[lift.Out] =
    repeat(min = count, max = count, separator = separator)

  /**
   * Turns this `PathMatcher` into one that matches a number of times (with the given separator)
   * and potentially extracts a `List` of the underlying matcher's extractions.
   * If less than `min` applications of the underlying matcher have succeeded the produced matcher fails,
   * otherwise it matches up to the given `max` number of applications.
   * Note that it won't fail even if more than `max` applications could succeed!
   * The "surplus" path elements will simply be left unmatched.
   *
   * The result type depends on the type of the underlying matcher:
   *
   * <table>
   * <th><td>If a `matcher` is of type</td><td>then `matcher.repeat(...)` is of type</td></th>
   * <tr><td>`PathMatcher0`</td><td>`PathMatcher0`</td></tr>
   * <tr><td>`PathMatcher1[T]`</td><td>`PathMatcher1[List[T]`</td></tr>
   * <tr><td>`PathMatcher[L :Tuple]`</td><td>`PathMatcher[List[L]]`</td></tr>
   * </table>
   */
  def repeat(min: Int, max: Int, separator: PathMatcher0 = PathMatchers.Neutral)(implicit lift: PathMatcher.Lift[L, List]): PathMatcher[lift.Out] =
    new PathMatcher[lift.Out]()(lift.OutIsTuple) {
      require(min >= 0, "`min` must be >= 0")
      require(max >= min, "`max` must be >= `min`")
      def apply(path: Path) = rec(path, 1)
      def rec(path: Path, count: Int): Matching[lift.Out] = {
        def done = if (count >= min) Matched(path, lift()) else Unmatched
        if (count <= max) {
          self(path) match {
            case Matched(remaining, extractions) ⇒
              def done1 = if (count >= min) Matched(remaining, lift(extractions)) else Unmatched
              separator(remaining) match {
                case Matched(remaining2, _) ⇒ rec(remaining2, count + 1) match {
                  case Matched(`remaining2`, _) ⇒ done1 // we made no progress, so "go back" to before the separator
                  case Matched(rest, result)    ⇒ Matched(rest, lift(extractions, result))
                  case Unmatched                ⇒ Unmatched
                }
                case Unmatched ⇒ done1
              }
            case Unmatched ⇒ done
          }
        } else done
      }
    }
}

object PathMatcher extends ImplicitPathMatcherConstruction {
  sealed abstract class Matching[+L: Tuple] {
    def map[R: Tuple](f: L ⇒ R): Matching[R]
    def flatMap[R: Tuple](f: L ⇒ Option[R]): Matching[R]
    def andThen[R: Tuple](f: (Path, L) ⇒ Matching[R]): Matching[R]
    def orElse[R >: L](other: ⇒ Matching[R]): Matching[R]
  }
  case class Matched[L: Tuple](pathRest: Path, extractions: L) extends Matching[L] {
    def map[R: Tuple](f: L ⇒ R) = Matched(pathRest, f(extractions))
    def flatMap[R: Tuple](f: L ⇒ Option[R]) = f(extractions) match {
      case Some(valuesR) ⇒ Matched(pathRest, valuesR)
      case None          ⇒ Unmatched
    }
    def andThen[R: Tuple](f: (Path, L) ⇒ Matching[R]) = f(pathRest, extractions)
    def orElse[R >: L](other: ⇒ Matching[R]) = this
  }
  object Matched { val Empty = Matched(Path.Empty, ()) }
  case object Unmatched extends Matching[Nothing] {
    def map[R: Tuple](f: Nothing ⇒ R) = this
    def flatMap[R: Tuple](f: Nothing ⇒ Option[R]) = this
    def andThen[R: Tuple](f: (Path, Nothing) ⇒ Matching[R]) = this
    def orElse[R](other: ⇒ Matching[R]) = other
  }

  /**
   * Creates a PathMatcher that always matches, consumes nothing and extracts the given Tuple of values.
   */
  def provide[L: Tuple](extractions: L): PathMatcher[L] =
    new PathMatcher[L] {
      def apply(path: Path) = Matched(path, extractions)(ev)
    }

  /**
   * Creates a PathMatcher that matches and consumes the given path prefix and extracts the given list of extractions.
   * If the given prefix is empty the returned PathMatcher matches always and consumes nothing.
   */
  def apply[L: Tuple](prefix: Path, extractions: L): PathMatcher[L] =
    if (prefix.isEmpty) provide(extractions)
    else new PathMatcher[L] {
      def apply(path: Path) =
        if (path startsWith prefix) Matched(path dropChars prefix.charCount, extractions)(ev)
        else Unmatched
    }

  /** Provoke implicit conversions to PathMatcher to be applied */
  def apply[L](magnet: PathMatcher[L]): PathMatcher[L] = magnet

  implicit class PathMatcher1Ops[T](matcher: PathMatcher1[T]) {
    def map[R](f: T ⇒ R): PathMatcher1[R] = matcher.tmap { case Tuple1(e) ⇒ Tuple1(f(e)) }
    def flatMap[R](f: T ⇒ Option[R]): PathMatcher1[R] =
      matcher.tflatMap { case Tuple1(e) ⇒ f(e).map(x ⇒ Tuple1(x)) }
  }

  implicit class EnhancedPathMatcher[L](underlying: PathMatcher[L]) {
    def ?(implicit lift: PathMatcher.Lift[L, Option]): PathMatcher[lift.Out] =
      new PathMatcher[lift.Out]()(lift.OutIsTuple) {
        def apply(path: Path) = underlying(path) match {
          case Matched(rest, extractions) ⇒ Matched(rest, lift(extractions))
          case Unmatched                  ⇒ Matched(path, lift())
        }
      }
  }

  sealed trait Lift[L, M[+_]] {
    type Out
    def OutIsTuple: Tuple[Out]
    def apply(): Out
    def apply(value: L): Out
    def apply(value: L, more: Out): Out
  }
  object Lift extends LowLevelLiftImplicits {
    trait MOps[M[+_]] {
      def apply(): M[Nothing]
      def apply[T](value: T): M[T]
      def apply[T](value: T, more: M[T]): M[T]
    }
    object MOps {
      implicit val OptionMOps: MOps[Option] =
        new MOps[Option] {
          def apply(): Option[Nothing] = None
          def apply[T](value: T): Option[T] = Some(value)
          def apply[T](value: T, more: Option[T]): Option[T] = Some(value)
        }
      implicit val ListMOps: MOps[List] =
        new MOps[List] {
          def apply(): List[Nothing] = Nil
          def apply[T](value: T): List[T] = value :: Nil
          def apply[T](value: T, more: List[T]): List[T] = value :: more
        }
    }
    implicit def liftUnit[M[+_]]: Lift[Unit, M] { type Out = Unit } =
      new Lift[Unit, M] {
        type Out = Unit
        def OutIsTuple = implicitly[Tuple[Out]]
        def apply() = ()
        def apply(value: Unit) = value
        def apply(value: Unit, more: Out) = value
      }
    implicit def liftSingleElement[A, M[+_]](implicit mops: MOps[M]): Lift[Tuple1[A], M] { type Out = Tuple1[M[A]] } =
      new Lift[Tuple1[A], M] {
        type Out = Tuple1[M[A]]
        def OutIsTuple = implicitly[Tuple[Out]]
        def apply() = Tuple1(mops())
        def apply(value: Tuple1[A]) = Tuple1(mops(value._1))
        def apply(value: Tuple1[A], more: Out) = Tuple1(mops(value._1, more._1))
      }
  }

  trait LowLevelLiftImplicits {
    import Lift._
    implicit def default[T, M[+_]](implicit mops: MOps[M]): Lift[T, M] { type Out = Tuple1[M[T]] } =
      new Lift[T, M] {
        type Out = Tuple1[M[T]]
        def OutIsTuple = implicitly[Tuple[Out]]
        def apply() = Tuple1(mops())
        def apply(value: T) = Tuple1(mops(value))
        def apply(value: T, more: Out) = Tuple1(mops(value, more._1))
      }
  }
}

/**
 * @groupname pathmatcherimpl Path matcher implicits
 * @groupprio pathmatcherimpl 172
 */
trait ImplicitPathMatcherConstruction {
  import PathMatcher._

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * (if the path begins with a segment) and extracts a given value.
   *
   * @group pathmatcherimpl
   */
  implicit def _stringExtractionPair2PathMatcher[T](tuple: (String, T)): PathMatcher1[T] =
    PathMatcher(tuple._1 :: Path.Empty, Tuple1(tuple._2))

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * (if the path begins with a segment).
   *
   * @group pathmatcherimpl
   */
  implicit def _segmentStringToPathMatcher(segment: String): PathMatcher0 =
    PathMatcher(segment :: Path.Empty, ())

  /**
   * @group pathmatcherimpl
   */
  implicit def _stringNameOptionReceptacle2PathMatcher(nr: NameOptionReceptacle[String]): PathMatcher0 =
    PathMatcher(nr.name).?

  /**
   * Creates a PathMatcher that consumes (a prefix of) the first path segment
   * if the path begins with a segment (a prefix of) which matches the given regex.
   * Extracts either the complete match (if the regex doesn't contain a capture group) or
   * the capture group (if the regex contains exactly one).
   * If the regex contains more than one capture group the method throws an IllegalArgumentException.
   *
   * @group pathmatcherimpl
   */
  implicit def _regex2PathMatcher(regex: Regex): PathMatcher1[String] = regex.groupCount match {
    case 0 ⇒ new PathMatcher1[String] {
      def apply(path: Path) = path match {
        case Path.Segment(segment, tail) ⇒ regex findPrefixOf segment match {
          case Some(m) ⇒ Matched(segment.substring(m.length) :: tail, Tuple1(m))
          case None    ⇒ Unmatched
        }
        case _ ⇒ Unmatched
      }
    }
    case 1 ⇒ new PathMatcher1[String] {
      def apply(path: Path) = path match {
        case Path.Segment(segment, tail) ⇒ regex findPrefixMatchOf segment match {
          case Some(m) ⇒ Matched(segment.substring(m.end) :: tail, Tuple1(m.group(1)))
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
   * For keys sharing a common prefix the longest matching prefix is selected.
   *
   * @group pathmatcherimpl
   */
  implicit def _valueMap2PathMatcher[T](valueMap: Map[String, T]): PathMatcher1[T] =
    if (valueMap.isEmpty) PathMatchers.nothingMatcher
    else valueMap.toSeq.sortWith(_._1 > _._1).map(_stringExtractionPair2PathMatcher).reduceLeft(_ | _)
}

/**
 * @groupname pathmatcher Path matchers
 * @groupprio pathmatcher 171
 */
trait PathMatchers {
  import PathMatcher._

  /**
   * Converts a path string containing slashes into a PathMatcher that interprets slashes as
   * path segment separators.
   *
   * @group pathmatcher
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
   *
   * @group pathmatcher
   */
  object Slash extends PathMatcher0 {
    def apply(path: Path) = path match {
      case Path.Slash(tail) ⇒ Matched(tail, ())
      case _                ⇒ Unmatched
    }
  }

  /**
   * A PathMatcher that matches the very end of the requests URI path.
   *
   * @group pathmatcher
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
   * use the `RemainingPath` matcher!
   *
   * @group pathmatcher
   */
  object Remaining extends PathMatcher1[String] {
    def apply(path: Path) = Matched(Path.Empty, Tuple1(path.toString))
  }

  /**
   * A PathMatcher that matches and extracts the complete remaining,
   * unmatched part of the request's URI path.
   *
   * @group pathmatcher
   */
  object RemainingPath extends PathMatcher1[Path] {
    def apply(path: Path) = Matched(Path.Empty, Tuple1(path))
  }

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than Int.MaxValue.
   *
   * @group pathmatcher
   */
  object IntNumber extends NumberMatcher[Int](Int.MaxValue, 10) {
    def fromChar(c: Char) = fromDecimalChar(c)
  }

  /**
   * A PathMatcher that efficiently matches a number of digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than Long.MaxValue.
   *
   * @group pathmatcher
   */
  object LongNumber extends NumberMatcher[Long](Long.MaxValue, 10) {
    def fromChar(c: Char) = fromDecimalChar(c)
  }

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Int value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Int value larger
   * than Int.MaxValue.
   *
   * @group pathmatcher
   */
  object HexIntNumber extends NumberMatcher[Int](Int.MaxValue, 16) {
    def fromChar(c: Char) = fromHexChar(c)
  }

  /**
   * A PathMatcher that efficiently matches a number of hex-digits and extracts their (non-negative) Long value.
   * The matcher will not match 0 digits or a sequence of digits that would represent an Long value larger
   * than Long.MaxValue.
   *
   * @group pathmatcher
   */
  object HexLongNumber extends NumberMatcher[Long](Long.MaxValue, 16) {
    def fromChar(c: Char) = fromHexChar(c)
  }

  // common implementation of Number matchers
  /**
   * @group pathmatcher
   */
  abstract class NumberMatcher[@specialized(Int, Long) T](max: T, base: T)(implicit x: Integral[T])
    extends PathMatcher1[T] {

    import x._ // import implicit conversions for numeric operators
    val minusOne = x.zero - x.one
    val maxDivBase = max / base

    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) ⇒
        @tailrec def digits(ix: Int = 0, value: T = minusOne): Matching[Tuple1[T]] = {
          val a = if (ix < segment.length) fromChar(segment charAt ix) else minusOne
          if (a == minusOne) {
            if (value == minusOne) Unmatched
            else Matched(if (ix < segment.length) segment.substring(ix) :: tail else tail, Tuple1(value))
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

    def fromDecimalChar(c: Char): T = if ('0' <= c && c <= '9') x.fromInt(c - '0') else minusOne

    def fromHexChar(c: Char): T =
      if ('0' <= c && c <= '9') x.fromInt(c - '0') else {
        val cn = c | 0x20 // normalize to lowercase
        if ('a' <= cn && cn <= 'f') x.fromInt(cn - 'a' + 10) else minusOne
      }
  }

  /**
   * A PathMatcher that matches and extracts a Double value. The matched string representation is the pure decimal,
   * optionally signed form of a double value, i.e. without exponent.
   *
   * @group pathmatcher
   */
  val DoubleNumber: PathMatcher1[Double] =
    PathMatcher("""[+-]?\d*\.?\d*""".r) flatMap { string ⇒
      try Some(java.lang.Double.parseDouble(string))
      catch { case _: NumberFormatException ⇒ None }
    }

  /**
   * A PathMatcher that matches and extracts a java.util.UUID instance.
   *
   * @group pathmatcher
   */
  val JavaUUID: PathMatcher1[UUID] =
    PathMatcher("""[\da-fA-F]{8}-[\da-fA-F]{4}-[\da-fA-F]{4}-[\da-fA-F]{4}-[\da-fA-F]{12}""".r) flatMap { string ⇒
      try Some(UUID.fromString(string))
      catch { case _: IllegalArgumentException ⇒ None }
    }

  /**
   * A PathMatcher that always matches, doesn't consume anything and extracts nothing.
   * Serves mainly as a neutral element in PathMatcher composition.
   *
   * @group pathmatcher
   */
  val Neutral: PathMatcher0 = PathMatcher.provide(())

  /**
   * A PathMatcher that matches if the unmatched path starts with a path segment.
   * If so the path segment is extracted as a String.
   *
   * @group pathmatcher
   */
  object Segment extends PathMatcher1[String] {
    def apply(path: Path) = path match {
      case Path.Segment(segment, tail) ⇒ Matched(tail, Tuple1(segment))
      case _                           ⇒ Unmatched
    }
  }

  /**
   * A PathMatcher that matches up to 128 remaining segments as a List[String].
   * This can also be no segments resulting in the empty list.
   * If the path has a trailing slash this slash will *not* be matched.
   *
   * @group pathmatcher
   */
  val Segments: PathMatcher1[List[String]] = Segments(min = 0, max = 128)

  /**
   * A PathMatcher that matches the given number of path segments (separated by slashes) as a List[String].
   * If there are more than `count` segments present the remaining ones will be left unmatched.
   * If the path has a trailing slash this slash will *not* be matched.
   *
   * @group pathmatcher
   */
  def Segments(count: Int): PathMatcher1[List[String]] = Segment.repeat(count, separator = Slash)

  /**
   * A PathMatcher that matches between `min` and `max` (both inclusively) path segments (separated by slashes)
   * as a List[String]. If there are more than `count` segments present the remaining ones will be left unmatched.
   * If the path has a trailing slash this slash will *not* be matched.
   *
   * @group pathmatcher
   */
  def Segments(min: Int, max: Int): PathMatcher1[List[String]] = Segment.repeat(min, max, separator = Slash)

  /**
   * A PathMatcher that never matches anything.
   *
   * @group pathmatcher
   */
  def nothingMatcher[L: Tuple]: PathMatcher[L] =
    new PathMatcher[L] {
      def apply(p: Path) = Unmatched
    }
}

object PathMatchers extends PathMatchers
