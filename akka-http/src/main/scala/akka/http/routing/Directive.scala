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

import scala.util.{ Failure, Success }
import scala.concurrent.{ ExecutionContext, Future }
import akka.shapeless._
//import spray.httpx.unmarshalling.MalformedContent

trait ConjunctionMagnet[L <: HList] {
  type Out
  def apply(underlying: Directive[L]): Out
}

object ConjunctionMagnet {
  implicit def fromDirective[L <: HList, R <: HList](other: Directive[R])(implicit p: Prepender[L, R]) =
    new ConjunctionMagnet[L] {
      type Out = Directive[p.Out]
      def apply(underlying: Directive[L]): Out =
        new Directive[p.Out] {
          def happly(f: p.Out ⇒ Route) =
            underlying.happly { prefix ⇒
              other.happly { suffix ⇒
                f(p(prefix, suffix))
              }
            }
        }
    }

  implicit def fromStandardRoute[L <: HList](route: StandardRoute) =
    new ConjunctionMagnet[L] {
      type Out = StandardRoute
      def apply(underlying: Directive[L]): Out = StandardRoute(underlying.happly(_ ⇒ route))
    }

  implicit def fromRouteGenerator[T, R <: Route](generator: T ⇒ R) = new ConjunctionMagnet[HNil] {
    type Out = RouteGenerator[T]
    def apply(underlying: Directive0): Out = { value ⇒
      underlying.happly(_ ⇒ generator(value))
    }
  }
}

abstract class Directive[L <: HList] { self ⇒
  def happly(f: L ⇒ Route): Route

  def |[R >: L <: HList](that: Directive[R]): Directive[R] = new Directive[R] {
    def happly(f: (R) ⇒ Route): Route = FIXME
    //recover(rejections ⇒ directives.BasicDirectives.mapRejections(rejections ::: _) & that)
  }

  def &(magnet: ConjunctionMagnet[L]): magnet.Out = magnet(this)

  /*def as[T](deserializer: HListDeserializer[L, T]): Directive1[T] =
    new Directive1[T] {
      def happly(f: T :: HNil ⇒ Route) =
        self.happly { values ⇒
          ctx ⇒
            deserializer(values) match {
              case Right(t)                           ⇒ f(t :: HNil)(ctx)
              case Left(MalformedContent(msg, cause)) ⇒ ctx.reject(ValidationRejection(msg, cause))
              case Left(error)                        ⇒ ctx.reject(ValidationRejection(error.toString))
            }
        }
    }*/

  def hmap[R](f: L ⇒ R)(implicit hl: HListable[R]): Directive[hl.Out] =
    new Directive[hl.Out] {
      def happly(g: hl.Out ⇒ Route) = self.happly { values ⇒ g(hl(f(values))) }
    }

  def hflatMap[R <: HList](f: L ⇒ Directive[R]): Directive[R] =
    new Directive[R] {
      def happly(g: R ⇒ Route) = self.happly { values ⇒ f(values).happly(g) }
    }

  def hrequire(predicate: L ⇒ Boolean, rejections: Rejection*): Directive0 =
    hfilter(predicate, rejections: _*).hflatMap(_ ⇒ Directive.Empty)

  def hfilter(predicate: L ⇒ Boolean, rejections: Rejection*): Directive[L] =
    new Directive[L] {
      def happly(f: L ⇒ Route) =
        self.happly { values ⇒ ctx ⇒ if (predicate(values)) f(values)(ctx) else ctx.reject(rejections: _*) }
    }

  def recover[R >: L <: HList](recovery: List[Rejection] ⇒ Directive[R]): Directive[R] =
    new Directive[R] {
      def happly(f: R ⇒ Route) = { ctx ⇒
        @volatile var rejectedFromInnerRoute = false
        self.happly({ list ⇒ c ⇒ rejectedFromInnerRoute = true; f(list)(c) }) {
          ctx.withRejectionHandling { rejections ⇒
            if (rejectedFromInnerRoute) ctx.reject(rejections: _*)
            else recovery(rejections).happly(f)(ctx)
          }
        }
      }
    }

  def recoverPF[R >: L <: HList](recovery: PartialFunction[List[Rejection], Directive[R]]): Directive[R] =
    recover { rejections ⇒
      if (recovery.isDefinedAt(rejections)) recovery(rejections)
      else Route.toDirective(_.reject(rejections: _*))
    }
}

object Directive {

  /**
   * A Directive that always passes the request on to its inner route (i.e. does nothing).
   */
  object Empty extends Directive0 {
    def happly(inner: HNil ⇒ Route) = inner(HNil)
  }

  implicit def addDirectiveApply[L <: HList](directive: Directive[L])(implicit hac: ApplyConverter[L]): hac.In ⇒ Route = f ⇒ directive.happly(hac(f))

  implicit class SingleValueModifiers[T](underlying: Directive1[T]) {
    def map[R](f: T ⇒ R)(implicit hl: HListable[R]): Directive[hl.Out] =
      underlying.hmap { case value :: HNil ⇒ f(value) }

    def flatMap[R <: HList](f: T ⇒ Directive[R]): Directive[R] =
      underlying.hflatMap { case value :: HNil ⇒ f(value) }

    def require(predicate: T ⇒ Boolean, rejections: Rejection*): Directive0 =
      underlying.filter(predicate, rejections: _*).hflatMap(_ ⇒ Empty)

    def filter(predicate: T ⇒ Boolean, rejections: Rejection*): Directive1[T] =
      underlying.hfilter({ case value :: HNil ⇒ predicate(value) }, rejections: _*)
  }
}