/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server

import akka.http.server.directives.RouteDirectives
import akka.http.server.util._

trait ConjunctionMagnet[L] {
  type Out
  def apply(underlying: Directive[L]): Out
}

object ConjunctionMagnet {
  implicit def fromDirective[L, R](other: Directive[R])(implicit join: Join[L, R]) =
    new ConjunctionMagnet[L] {
      type Out = Directive[join.Out]
      def apply(underlying: Directive[L]): Out =
        new Directive[join.Out]()(join.OutIsTuple) {
          def tapply(f: join.Out ⇒ Route) =
            underlying.tapply { prefix ⇒
              other.tapply { suffix ⇒
                f(join(prefix, suffix))
              }
            }
        }
    }

  implicit def fromStandardRoute[L](route: StandardRoute) =
    new ConjunctionMagnet[L] {
      type Out = StandardRoute
      def apply(underlying: Directive[L]): Out = StandardRoute(underlying.tapply(_ ⇒ route))
    }

  implicit def fromRouteGenerator[T, R <: Route](generator: T ⇒ R) = new ConjunctionMagnet[Unit] {
    type Out = RouteGenerator[T]
    def apply(underlying: Directive0): Out = { value ⇒
      underlying.tapply(_ ⇒ generator(value))
    }
  }
}

/**
 * A directive that provides a tuple of values of type `L` to create an inner route.
 */
abstract class Directive[L](implicit val ev: Tuple[L]) { self ⇒

  /**
   * Calls the inner route with a tuple of extracted values of type `L`.
   *
   * `tapply` is short for "tuple-apply". Usually, you will use the regular `apply` method instead,
   * which is added by an implicit conversion (see `Directive.addDirectiveApply`).
   */
  def tapply(f: L ⇒ Route): Route

  def |[R >: L](that: Directive[R]): Directive[R] =
    recover(rejections ⇒ directives.BasicDirectives.mapRejections(rejections ::: _) & that)(that.ev)

  /**
   * Joins two directives into one which extracts the concatenation of its base directive extractions.
   * NOTE: Extraction joining is an O(N) operation with N being the number of extractions on the right-side.
   */
  def &(magnet: ConjunctionMagnet[L]): magnet.Out = magnet(this)

  def as[T](constructor: ConstructFromTuple[L, T]): Directive1[T] =
    tmap(constructor(_))

  def tmap[R](f: L ⇒ R)(implicit tupler: Tupler[R]): Directive[tupler.Out] =
    new Directive[tupler.Out]()(tupler.OutIsTuple) {
      def tapply(g: tupler.Out ⇒ Route) = self.tapply { values ⇒ g(tupler(f(values))) }
    }

  def tflatMap[R: Tuple](f: L ⇒ Directive[R]): Directive[R] =
    new Directive[R] {
      def tapply(g: R ⇒ Route) = self.tapply { values ⇒ f(values).tapply(g) }
    }

  def trequire(predicate: L ⇒ Boolean, rejections: Rejection*): Directive0 =
    tfilter(predicate, rejections: _*).tflatMap(_ ⇒ Directive.Empty)

  def tfilter(predicate: L ⇒ Boolean, rejections: Rejection*): Directive[L] =
    new Directive[L] {
      def tapply(f: L ⇒ Route) =
        self.tapply { values ⇒ ctx ⇒ if (predicate(values)) f(values)(ctx) else ctx.reject(rejections: _*) }
    }

  def recover[R >: L: Tuple](recovery: List[Rejection] ⇒ Directive[R]): Directive[R] =
    new Directive[R] {
      def tapply(f: R ⇒ Route) = { ctx ⇒
        @volatile var rejectedFromInnerRoute = false
        self.tapply({ list ⇒ c ⇒ rejectedFromInnerRoute = true; f(list)(c) }) {
          ctx.withRejectionHandling { rejections ⇒
            if (rejectedFromInnerRoute) ctx.reject(rejections: _*)
            else recovery(rejections).tapply(f)(ctx)
          }
        }
      }
    }

  def recoverPF[R >: L: Tuple](recovery: PartialFunction[List[Rejection], Directive[R]]): Directive[R] =
    recover { rejections ⇒
      if (recovery isDefinedAt rejections) recovery(rejections)
      else RouteDirectives.reject(rejections: _*)
    }
}

object Directive {

  /**
   * A Directive that always passes the request on to its inner route (i.e. does nothing).
   */
  object Empty extends Directive0 {
    def tapply(inner: Unit ⇒ Route) = inner(())
  }

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