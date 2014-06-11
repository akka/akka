/*
 * Copyright (c) 2012-14 Miles Sabin 
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

import hlist.{ IsHCons, ReversePrepend, Split, SplitLeft }

object zipper {
  trait Right[Z] extends DepFn1[Z]

  object Right {
    implicit def right[C, L <: HList, RH, RT <: HList, P] = new Right[Zipper[C, L, RH :: RT, P]] {
      type Out = Zipper[C, RH :: L, RT, P]
      def apply(z: Zipper[C, L, RH :: RT, P]) = Zipper(z.suffix.head :: z.prefix, z.suffix.tail, z.parent)
    }
  }

  trait Left[Z] extends DepFn1[Z]

  object Left {
    implicit def left[C, LH, LT <: HList, R <: HList, P] = new Left[Zipper[C, LH :: LT, R, P]] {
      type Out = Zipper[C, LT, LH :: R, P]
      def apply(z: Zipper[C, LH :: LT, R, P]) = Zipper(z.prefix.tail, z.prefix.head :: z.suffix, z.parent)
    }
  }

  trait First[Z] extends DepFn1[Z]

  object First {
    implicit def first[C, L <: HList, R <: HList, RP <: HList, P](implicit rp: ReversePrepend.Aux[L, R, RP]) =
      new First[Zipper[C, L, R, P]] {
        type Out = Zipper[C, HNil, RP, P]
        def apply(z: Zipper[C, L, R, P]) = Zipper(HNil, z.prefix reverse_::: z.suffix, z.parent)
      }
  }

  trait Last[Z] extends DepFn1[Z]

  object Last {
    implicit def last[C, L <: HList, R <: HList, RP <: HList, P](implicit rp: ReversePrepend.Aux[R, L, RP]) =
      new Last[Zipper[C, L, R, P]] {
        type Out = Zipper[C, RP, HNil, P]
        def apply(z: Zipper[C, L, R, P]) = Zipper(z.suffix reverse_::: z.prefix, HNil, z.parent)
      }
  }

  trait RightBy[Z, N <: Nat] extends DepFn1[Z]

  object RightBy {
    implicit def rightBy[C, L <: HList, R <: HList, P, N <: Nat, LP <: HList, RS <: HList](implicit split: Split.Aux[R, N, (LP, RS)], reverse: ReversePrepend[LP, L]) =
      new RightBy[Zipper[C, L, R, P], N] {
        type Out = Zipper[C, reverse.Out, RS, P]
        def apply(z: Zipper[C, L, R, P]) = {
          val (p, s) = z.suffix.split[N]
          Zipper(p reverse_::: z.prefix, s, z.parent)
        }
      }
  }

  trait LeftBy[Z, N <: Nat] extends DepFn1[Z]

  object LeftBy {
    implicit def leftBy[C, L <: HList, R <: HList, P, N <: Nat, RP <: HList, LS <: HList](implicit split: Split.Aux[L, N, (RP, LS)], reverse: ReversePrepend[RP, R]) =
      new LeftBy[Zipper[C, L, R, P], N] {
        type Out = Zipper[C, LS, reverse.Out, P]
        def apply(z: Zipper[C, L, R, P]) = {
          val (p, s) = z.prefix.split[N]
          Zipper(s, p reverse_::: z.suffix, z.parent)
        }
      }
  }

  trait RightTo[Z, T] extends DepFn1[Z]

  object RightTo {
    implicit def rightTo[C, L <: HList, R <: HList, P, T, LP <: HList, RS <: HList](implicit split: SplitLeft.Aux[R, T, (LP, RS)], reverse: ReversePrepend[LP, L]) =
      new RightTo[Zipper[C, L, R, P], T] {
        type Out = Zipper[C, reverse.Out, RS, P]
        def apply(z: Zipper[C, L, R, P]) = {
          val (p, s) = z.suffix.splitLeft[T]
          Zipper(p reverse_::: z.prefix, s, z.parent)
        }
      }
  }

  trait LeftTo[Z, T] extends DepFn1[Z]

  object LeftTo {
    implicit def leftTo[C, L <: HList, R <: HList, P, T, RP <: HList, R0 <: HList](implicit split: SplitLeft.Aux[L, T, (RP, R0)], reverse: ReversePrepend[RP, R], cons: IsHCons[R0]) =
      new LeftTo[Zipper[C, L, R, P], T] {
        type Out = Zipper[C, cons.T, cons.H :: reverse.Out, P]
        def apply(z: Zipper[C, L, R, P]) = {
          val (p, s) = z.prefix.splitLeft[T]
          Zipper(s.tail, s.head :: (p reverse_::: z.suffix), z.parent)
        }
      }
  }

  trait Up[Z] extends DepFn1[Z]

  object Up {
    implicit def up[C, L <: HList, R <: HList, P](implicit rz: Reify[Zipper[C, L, R, Some[P]]] { type Out = C }, pp: Put[P, C]) =
      new Up[Zipper[C, L, R, Some[P]]] {
        type Out = pp.Out
        def apply(z: Zipper[C, L, R, Some[P]]) = pp(z.parent.get, z.reify)
      }
  }

  trait Down[Z] extends DepFn1[Z]

  object Down {
    implicit def down[C, L <: HList, RH, RT <: HList, P, RHL <: HList](implicit gen: Generic.Aux[RH, RHL]) =
      new Down[Zipper[C, L, RH :: RT, P]] {
        type Out = Zipper[RH, HNil, RHL, Some[Zipper[C, L, RH :: RT, P]]]
        def apply(z: Zipper[C, L, RH :: RT, P]) = Zipper(HNil, gen.to(z.suffix.head), Some(z))
      }
  }

  trait Root[Z] extends DepFn1[Z]

  object Root extends {
    implicit def rootRoot[C, L <: HList, R <: HList] = new Root[Zipper[C, L, R, None.type]] {
      type Out = Zipper[C, L, R, None.type]
      def apply(z: Zipper[C, L, R, None.type]) = z
    }

    implicit def nonRootRoot[C, L <: HList, R <: HList, P, U](implicit up: Up[Zipper[C, L, R, Some[P]]] { type Out = U }, pr: Root[U]) =
      new Root[Zipper[C, L, R, Some[P]]] {
        type Out = pr.Out
        def apply(z: Zipper[C, L, R, Some[P]]) = pr(z.up)
      }
  }

  trait Get[Z] extends DepFn1[Z]

  object Get {
    implicit def get[C, L <: HList, RH, RT <: HList, P] = new Get[Zipper[C, L, RH :: RT, P]] {
      type Out = RH
      def apply(z: Zipper[C, L, RH :: RT, P]) = z.suffix.head
    }
  }

  trait Put[Z, E] extends DepFn2[Z, E]

  trait LowPriorityPut {
    implicit def put[C, L <: HList, RH, RT <: HList, P, E, CL <: HList](implicit gen: Generic.Aux[C, CL], rp: ReversePrepend.Aux[L, E :: RT, CL]) =
      new Put[Zipper[C, L, RH :: RT, P], E] {
        type Out = Zipper[C, L, E :: RT, P]
        def apply(z: Zipper[C, L, RH :: RT, P], e: E) = Zipper(z.prefix, e :: z.suffix.tail, z.parent)
      }
  }

  object Put extends LowPriorityPut {
    implicit def hlistPut[C <: HList, L <: HList, RH, RT <: HList, P, E, CL <: HList](implicit rp: ReversePrepend.Aux[L, E :: RT, CL]) =
      new Put[Zipper[C, L, RH :: RT, P], E] {
        type Out = Zipper[CL, L, E :: RT, P]
        def apply(z: Zipper[C, L, RH :: RT, P], e: E) = Zipper(z.prefix, e :: z.suffix.tail, z.parent)
      }
  }

  trait Insert[Z, E] extends DepFn2[Z, E]

  object Insert {
    implicit def hlistInsert[C <: HList, L <: HList, R <: HList, P, E, CL <: HList](implicit rp: ReversePrepend.Aux[E :: L, R, CL]) =
      new Insert[Zipper[C, L, R, P], E] {
        type Out = Zipper[CL, E :: L, R, P]
        def apply(z: Zipper[C, L, R, P], e: E) = Zipper(e :: z.prefix, z.suffix, z.parent)
      }
  }

  trait Delete[Z] extends DepFn1[Z]

  object Delete {
    implicit def hlistDelete[C <: HList, L <: HList, RH, RT <: HList, P, CL <: HList](implicit rp: ReversePrepend.Aux[L, RT, CL]) =
      new Delete[Zipper[C, L, RH :: RT, P]] {
        type Out = Zipper[CL, L, RT, P]
        def apply(z: Zipper[C, L, RH :: RT, P]) = Zipper(z.prefix, z.suffix.tail, z.parent)
      }
  }

  trait Reify[Z] extends DepFn1[Z]

  object Reify {
    implicit def reify[C, L <: HList, R <: HList, P, CL <: HList](implicit gen: Generic.Aux[C, CL], rp: ReversePrepend.Aux[L, R, CL]) =
      new Reify[Zipper[C, L, R, P]] {
        type Out = C
        def apply(z: Zipper[C, L, R, P]) = gen.from(z.prefix reverse_::: z.suffix)
      }
  }
}
