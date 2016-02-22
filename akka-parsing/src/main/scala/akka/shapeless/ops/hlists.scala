/*
 * Copyright (c) 2011-13 Miles Sabin 
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.shapeless
package ops


object hlist {
  /**
   * Type class witnessing that this `HList` is composite and providing access to head and tail.
   *
   * @author Miles Sabin
   */
  trait IsHCons[L <: HList] {
    type H
    type T <: HList

    def head(l: L): H
    def tail(l: L): T
  }

  object IsHCons {
    def apply[L <: HList](implicit isHCons: IsHCons[L]): Aux[L, isHCons.H, isHCons.T] = isHCons

    type Aux[L <: HList, H0, T0 <: HList] = IsHCons[L] { type H = H0; type T = T0 }
    implicit def hlistIsHCons[H0, T0 <: HList]: Aux[H0 :: T0, H0, T0] =
      new IsHCons[H0 :: T0] {
        type H = H0
        type T = T0

        def head(l: H0 :: T0): H = l.head
        def tail(l: H0 :: T0): T = l.tail
      }
  }

  /**
   * Type class supporting reversing this `HList`.
   *
   * @author Miles Sabin
   */
  trait Reverse[L <: HList] extends DepFn1[L] { type Out <: HList }

  object Reverse {
    def apply[L <: HList](implicit reverse: Reverse[L]): Aux[L, reverse.Out] = reverse

    type Aux[L <: HList, Out0 <: HList] = Reverse[L] { type Out = Out0 }

    implicit def reverse[L <: HList, Out0 <: HList](implicit reverse: Reverse0[HNil, L, Out0]): Aux[L, Out0] =
      new Reverse[L] {
        type Out = Out0
        def apply(l: L): Out = reverse(HNil, l)
      }

    trait Reverse0[Acc <: HList, L <: HList, Out <: HList] {
      def apply(acc: Acc, l: L): Out
    }

    object Reverse0 {
      implicit def hnilReverse[Out <: HList]: Reverse0[Out, HNil, Out] =
        new Reverse0[Out, HNil, Out] {
          def apply(acc: Out, l: HNil): Out = acc
        }

      implicit def hlistReverse[Acc <: HList, InH, InT <: HList, Out <: HList](implicit rt: Reverse0[InH :: Acc, InT, Out]): Reverse0[Acc, InH :: InT, Out] =
        new Reverse0[Acc, InH :: InT, Out] {
          def apply(acc: Acc, l: InH :: InT): Out = rt(l.head :: acc, l.tail)
        }
    }
  }

  /**
   * Type class supporting prepending to this `HList`.
   *
   * @author Miles Sabin
   */
  trait Prepend[P <: HList, S <: HList] extends DepFn2[P, S] { type Out <: HList }

  trait LowPriorityPrepend {
    type Aux[P <: HList, S <: HList, Out0 <: HList] = Prepend[P, S] { type Out = Out0 }

    implicit def hnilPrepend0[P <: HList, S <: HNil]: Aux[P, S, P] =
      new Prepend[P, S] {
        type Out = P
        def apply(prefix: P, suffix: S): P = prefix
      }
  }

  object Prepend extends LowPriorityPrepend {
    def apply[P <: HList, S <: HList](implicit prepend: Prepend[P, S]): Aux[P, S, prepend.Out] = prepend

    implicit def hnilPrepend1[P <: HNil, S <: HList]: Aux[P, S, S] =
      new Prepend[P, S] {
        type Out = S
        def apply(prefix: P, suffix: S): S = suffix
      }

    implicit def hlistPrepend[PH, PT <: HList, S <: HList](implicit pt: Prepend[PT, S]): Aux[PH :: PT, S, PH :: pt.Out] =
      new Prepend[PH :: PT, S] {
        type Out = PH :: pt.Out
        def apply(prefix: PH :: PT, suffix: S): Out = prefix.head :: pt(prefix.tail, suffix)
      }
  }

  /**
   * Type class supporting reverse prepending to this `HList`.
   *
   * @author Miles Sabin
   */
  trait ReversePrepend[P <: HList, S <: HList] extends DepFn2[P, S] { type Out <: HList }

  trait LowPriorityReversePrepend {
    type Aux[P <: HList, S <: HList, Out0 <: HList] = ReversePrepend[P, S] { type Out = Out0 }

    implicit def hnilReversePrepend0[P <: HList, S <: HNil](implicit rv: Reverse[P]): Aux[P, S, rv.Out] =
      new ReversePrepend[P, S] {
        type Out = rv.Out
        def apply(prefix: P, suffix: S) = prefix.reverse
      }
  }

  object ReversePrepend extends LowPriorityReversePrepend {
    def apply[P <: HList, S <: HList](implicit prepend: ReversePrepend[P, S]): Aux[P, S, prepend.Out] = prepend

    implicit def hnilReversePrepend1[P <: HNil, S <: HList]: Aux[P, S, S] =
      new ReversePrepend[P, S] {
        type Out = S
        def apply(prefix: P, suffix: S) = suffix
      }

    implicit def hlistReversePrepend[PH, PT <: HList, S <: HList](implicit rpt: ReversePrepend[PT, PH :: S]): Aux[PH :: PT, S, rpt.Out] =
      new ReversePrepend[PH :: PT, S] {
        type Out = rpt.Out
        def apply(prefix: PH :: PT, suffix: S): Out = rpt(prefix.tail, prefix.head :: suffix)
      }
  }
}
