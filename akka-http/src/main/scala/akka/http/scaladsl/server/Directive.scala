/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import scala.collection.immutable
import akka.http.scaladsl.server.directives.RouteDirectives
import akka.http.scaladsl.server.util._
import akka.http.scaladsl.util.FastFuture
import akka.http.scaladsl.util.FastFuture._
import akka.http.impl.util._

/**
 * A directive that provides a tuple of values of type `L` to create an inner route.
 */
//#basic
abstract class Directive[L](implicit val ev: Tuple[L]) {

  /**
   * Calls the inner route with a tuple of extracted values of type `L`.
   *
   * `tapply` is short for "tuple-apply". Usually, you will use the regular `apply` method instead,
   * which is added by an implicit conversion (see `Directive.addDirectiveApply`).
   */
  def tapply(f: L ⇒ Route): Route
  //#
  /**
   * Joins two directives into one which runs the second directive if the first one rejects.
   */
  def |[R >: L](that: Directive[R]): Directive[R] =
    recover(rejections ⇒ directives.BasicDirectives.mapRejections(rejections ++ _) & that)(that.ev)

  /**
   * Joins two directives into one which extracts the concatenation of its base directive extractions.
   * NOTE: Extraction joining is an O(N) operation with N being the number of extractions on the right-side.
   */
  def &(magnet: ConjunctionMagnet[L]): magnet.Out = magnet(this)

  /**
   * Converts this directive into one which, instead of a tuple of type `L`, creates an
   * instance of type `A` (which is usually a case class).
   */
  def as[A](constructor: ConstructFromTuple[L, A]): Directive1[A] = {
    def validatedMap[R](f: L ⇒ R)(implicit tupler: Tupler[R]): Directive[tupler.Out] =
      Directive[tupler.Out] { inner ⇒
        tapply { values ⇒
          ctx ⇒
            try inner(tupler(f(values)))(ctx)
            catch {
              case e: IllegalArgumentException ⇒ ctx.reject(ValidationRejection(e.getMessage.nullAsEmpty, Some(e)))
            }
        }
      }(tupler.OutIsTuple)

    validatedMap(constructor)
  }

  /**
   * Maps over this directive using the given function, which can produce either a tuple or any other value
   * (which will then we wrapped into a [[scala.Tuple1]]).
   */
  def tmap[R](f: L ⇒ R)(implicit tupler: Tupler[R]): Directive[tupler.Out] =
    Directive[tupler.Out] { inner ⇒ tapply { values ⇒ inner(tupler(f(values))) } }(tupler.OutIsTuple)

  /**
   * Flatmaps this directive using the given function.
   */
  def tflatMap[R: Tuple](f: L ⇒ Directive[R]): Directive[R] =
    Directive[R] { inner ⇒ tapply { values ⇒ f(values) tapply inner } }

  /**
   * Creates a new [[akka.http.scaladsl.server.Directive0]], which passes if the given predicate matches the current
   * extractions or rejects with the given rejections.
   */
  def trequire(predicate: L ⇒ Boolean, rejections: Rejection*): Directive0 =
    tfilter(predicate, rejections: _*).tflatMap(_ ⇒ Directive.Empty)

  /**
   * Creates a new directive of the same type, which passes if the given predicate matches the current
   * extractions or rejects with the given rejections.
   */
  def tfilter(predicate: L ⇒ Boolean, rejections: Rejection*): Directive[L] =
    Directive[L] { inner ⇒ tapply { values ⇒ ctx ⇒ if (predicate(values)) inner(values)(ctx) else ctx.reject(rejections: _*) } }

  /**
   * Creates a new directive that is able to recover from rejections that were produced by `this` Directive
   * **before the inner route was applied**.
   */
  def recover[R >: L: Tuple](recovery: immutable.Seq[Rejection] ⇒ Directive[R]): Directive[R] =
    Directive[R] { inner ⇒
      ctx ⇒
        import ctx.executionContext
        @volatile var rejectedFromInnerRoute = false
        tapply({ list ⇒ c ⇒ rejectedFromInnerRoute = true; inner(list)(c) })(ctx).fast.flatMap {
          case RouteResult.Rejected(rejections) if !rejectedFromInnerRoute ⇒ recovery(rejections).tapply(inner)(ctx)
          case x ⇒ FastFuture.successful(x)
        }
    }

  /**
   * Variant of `recover` that only recovers from rejections handled by the given PartialFunction.
   */
  def recoverPF[R >: L: Tuple](recovery: PartialFunction[immutable.Seq[Rejection], Directive[R]]): Directive[R] =
    recover { rejections ⇒ recovery.applyOrElse(rejections, (rejs: Seq[Rejection]) ⇒ RouteDirectives.reject(rejs: _*)) }

  //#basic
}
//#

object Directive {

  /**
   * Constructs a directive from a function literal.
   */
  def apply[T: Tuple](f: (T ⇒ Route) ⇒ Route): Directive[T] =
    new Directive[T] { def tapply(inner: T ⇒ Route) = f(inner) }

  /**
   * A Directive that always passes the request on to its inner route (i.e. does nothing).
   */
  val Empty: Directive0 = Directive(_(()))

  /**
   * Adds `apply` to all Directives with 1 or more extractions,
   * which allows specifying an n-ary function to receive the extractions instead of a Function1[TupleX, Route].
   */
  implicit def addDirectiveApply[L](directive: Directive[L])(implicit hac: ApplyConverter[L]): hac.In ⇒ Route =
    f ⇒ directive.tapply(hac(f))

  /**
   * Adds `apply` to Directive0. Note: The `apply` parameter is call-by-name to ensure consistent execution behavior
   * with the directives producing extractions.
   */
  implicit def addByNameNullaryApply(directive: Directive0): (⇒ Route) ⇒ Route =
    r ⇒ directive.tapply(_ ⇒ r)

  implicit class SingleValueModifiers[T](underlying: Directive1[T]) extends AnyRef {
    def map[R](f: T ⇒ R)(implicit tupler: Tupler[R]): Directive[tupler.Out] =
      underlying.tmap { case Tuple1(value) ⇒ f(value) }

    def flatMap[R: Tuple](f: T ⇒ Directive[R]): Directive[R] =
      underlying.tflatMap { case Tuple1(value) ⇒ f(value) }

    def require(predicate: T ⇒ Boolean, rejections: Rejection*): Directive0 =
      underlying.filter(predicate, rejections: _*).tflatMap(_ ⇒ Empty)

    def filter(predicate: T ⇒ Boolean, rejections: Rejection*): Directive1[T] =
      underlying.tfilter({ case Tuple1(value) ⇒ predicate(value) }, rejections: _*)
  }
}

trait ConjunctionMagnet[L] {
  type Out
  def apply(underlying: Directive[L]): Out
}

object ConjunctionMagnet {
  implicit def fromDirective[L, R](other: Directive[R])(implicit join: TupleOps.Join[L, R]): ConjunctionMagnet[L] { type Out = Directive[join.Out] } =
    new ConjunctionMagnet[L] {
      type Out = Directive[join.Out]
      def apply(underlying: Directive[L]) =
        Directive[join.Out] { inner ⇒
          underlying.tapply { prefix ⇒ other.tapply { suffix ⇒ inner(join(prefix, suffix)) } }
        }(Tuple.yes) // we know that join will only ever produce tuples
    }

  implicit def fromStandardRoute[L](route: StandardRoute): ConjunctionMagnet[L] { type Out = StandardRoute } =
    new ConjunctionMagnet[L] {
      type Out = StandardRoute
      def apply(underlying: Directive[L]) = StandardRoute(underlying.tapply(_ ⇒ route))
    }

  implicit def fromRouteGenerator[T, R <: Route](generator: T ⇒ R): ConjunctionMagnet[Unit] { type Out = RouteGenerator[T] } =
    new ConjunctionMagnet[Unit] {
      type Out = RouteGenerator[T]
      def apply(underlying: Directive0) = value ⇒ underlying.tapply(_ ⇒ generator(value))
    }
}