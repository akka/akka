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

import scala.annotation.tailrec

import poly._

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
   * Type class witnessing that the result of wrapping each element of `HList` `L` in type constructor `F` is `Out`.
   */
  trait Mapped[L <: HList, F[_]] {
    type Out <: HList
  }

  object Mapped {
    def apply[L <: HList, F[_]](implicit mapped: Mapped[L, F]): Aux[L, F, mapped.Out] = mapped

    type Aux[L <: HList, F[_], Out0 <: HList] = Mapped[L, F] { type Out = Out0 }

    implicit def hnilMapped[F[_]]: Aux[HNil, F, HNil] = new Mapped[HNil, F] { type Out = HNil }

    implicit def hlistIdMapped[L <: HList]: Aux[L, Id, L] = new Mapped[L, Id] { type Out = L }

    implicit def hlistMapped1[H, T <: HList, F[_], OutM <: HList](implicit mt: Mapped.Aux[T, F, OutM]): Aux[H :: T, F, F[H] :: OutM] =
      new Mapped[H :: T, F] { type Out = F[H] :: OutM }

    implicit def hlistMapped2[H, T <: HList, F, OutM <: HList](implicit mt: Mapped.Aux[T, Const[F]#λ, OutM]): Aux[H :: T, Const[F]#λ, F :: OutM] =
      new Mapped[H :: T, Const[F]#λ] { type Out = F :: OutM }
  }

  /**
   * Type class witnessing that the result of stripping type constructor `F` off each element of `HList` `L` is `Out`.
   */
  trait Comapped[L <: HList, F[_]] {
    type Out <: HList
  }

  trait LowPriorityComapped {
    type Aux[L <: HList, F[_], Out0 <: HList] = Comapped[L, F] { type Out = Out0 }
    implicit def hlistIdComapped[L <: HList]: Aux[L, Id, L] = new Comapped[L, Id] { type Out = L }
  }

  object Comapped extends LowPriorityComapped {
    def apply[L <: HList, F[_]](implicit comapped: Comapped[L, F]): Aux[L, F, comapped.Out] = comapped

    implicit def hnilComapped[F[_]]: Aux[HNil, F, HNil] = new Comapped[HNil, F] { type Out = HNil }

    implicit def hlistComapped[H, T <: HList, F[_]](implicit mt: Comapped[T, F]): Aux[F[H] :: T, F, H :: mt.Out] =
      new Comapped[F[H]:: T, F] { type Out = H :: mt.Out }
  }

  /**
   * Type class witnessing that `HList`s `L1` and `L2` have elements of the form `F1[Ln]` and `F2[Ln]` respectively for all
   * indices `n`. This implies that a natural transform `F1 ~> F2` will take a list of type `L1` onto a list of type `L2`.
   *
   * @author Miles Sabin
   */
  trait NatTRel[L1 <: HList, F1[_], L2 <: HList, F2[_]] {
    def map(nt: F1 ~> F2, fa: L1): L2
  }

  object NatTRel {
    def apply[L1 <: HList, F1[_], L2 <: HList, F2[_]](implicit natTRel: NatTRel[L1, F1, L2, F2]) = natTRel

    implicit def hnilNatTRel1[F1[_], F2[_]] = new NatTRel[HNil, F1, HNil, F2] {
      def map(f: F1 ~> F2, fa: HNil): HNil = HNil
    }

    implicit def hnilNatTRel2[F1[_], H2] = new NatTRel[HNil, F1, HNil, Const[H2]#λ] {
      def map(f: F1 ~> Const[H2]#λ, fa: HNil): HNil = HNil
    }

    implicit def hlistNatTRel1[H, F1[_], F2[_], T1 <: HList, T2 <: HList](implicit nt: NatTRel[T1, F1, T2, F2]) =
      new NatTRel[F1[H]:: T1, F1, F2[H]:: T2, F2] {
        def map(f: F1 ~> F2, fa: F1[H] :: T1): F2[H] :: T2 = f(fa.head) :: nt.map(f, fa.tail)
      }

    implicit def hlistNatTRel2[H, F2[_], T1 <: HList, T2 <: HList](implicit nt: NatTRel[T1, Id, T2, F2]) =
      new NatTRel[H :: T1, Id, F2[H]:: T2, F2] {
        def map(f: Id ~> F2, fa: H :: T1): F2[H] :: T2 = f(fa.head) :: nt.map(f, fa.tail)
      }

    implicit def hlistNatTRel3[H, F1[_], T1 <: HList, T2 <: HList](implicit nt: NatTRel[T1, F1, T2, Id]) =
      new NatTRel[F1[H]:: T1, F1, H :: T2, Id] {
        def map(f: F1 ~> Id, fa: F1[H] :: T1): H :: T2 = f(fa.head) :: nt.map(f, fa.tail)
      }

    implicit def hlistNatTRel4[H1, F1[_], T1 <: HList, H2, T2 <: HList](implicit nt: NatTRel[T1, F1, T2, Const[H2]#λ]) =
      new NatTRel[F1[H1]:: T1, F1, H2 :: T2, Const[H2]#λ] {
        def map(f: F1 ~> Const[H2]#λ, fa: F1[H1] :: T1): H2 :: T2 = f(fa.head) :: nt.map(f, fa.tail)
      }

    implicit def hlistNatTRel5[H1, T1 <: HList, H2, T2 <: HList](implicit nt: NatTRel[T1, Id, T2, Const[H2]#λ]) =
      new NatTRel[H1 :: T1, Id, H2 :: T2, Const[H2]#λ] {
        def map(f: Id ~> Const[H2]#λ, fa: H1 :: T1): H2 :: T2 = f(fa.head) :: nt.map(f, fa.tail)
      }
  }

  /**
   * Type class providing minimally witnessed operations on `HList`s which can be derived from `L` by wrapping
   * each of its elements in a type constructor.
   */
  trait HKernel {
    type L <: HList
    type Mapped[G[_]] <: HList
    type Length <: Nat

    def map[F[_], G[_]](f: F ~> G, l: Mapped[F]): Mapped[G]

    def tabulate[C](from: Int)(f: Int ⇒ C): Mapped[Const[C]#λ]

    def toList[C](l: Mapped[Const[C]#λ]): List[C]

    def length: Int
  }

  trait HNilHKernel extends HKernel {
    type L = HNil
    type Mapped[G[_]] = HNil
    type Length = _0

    def map[F[_], G[_]](f: F ~> G, l: HNil): HNil = HNil

    def tabulate[C](from: Int)(f: Int ⇒ C): HNil = HNil

    def toList[C](l: HNil): List[C] = Nil

    def length: Int = 0
  }

  case object HNilHKernel extends HNilHKernel

  final case class HConsHKernel[H, T <: HKernel](tail: T) extends HKernel {
    type L = H :: tail.L
    type Mapped[G[_]] = G[H] :: tail.Mapped[G]
    type Length = Succ[tail.Length]

    def map[F[_], G[_]](f: F ~> G, l: F[H] :: tail.Mapped[F]): G[H] :: tail.Mapped[G] = f(l.head) :: tail.map(f, l.tail)

    def tabulate[C](from: Int)(f: Int ⇒ C): C :: tail.Mapped[Const[C]#λ] = f(from) :: tail.tabulate(from + 1)(f)

    def toList[C](l: C :: tail.Mapped[Const[C]#λ]): List[C] = l.head :: tail.toList(l.tail)

    def length: Int = 1 + tail.length
  }

  object HKernel {
    def apply[L <: HList](implicit mk: HKernelAux[L]): mk.Out = mk()
    def apply[L <: HList](l: L)(implicit mk: HKernelAux[L]): mk.Out = mk()
  }

  trait HKernelAux[L <: HList] {
    type Out <: HKernel
    def apply(): Out
  }

  object HKernelAux {
    implicit def mkHNilHKernel = new HKernelAux[HNil] {
      type Out = HNilHKernel
      def apply() = HNilHKernel
    }

    implicit def mkHListHKernel[H, T <: HList](implicit ct: HKernelAux[T]) = new HKernelAux[H :: T] {
      type Out = HConsHKernel[H, ct.Out]
      def apply() = HConsHKernel[H, ct.Out](ct())
    }
  }

  /**
   * Type class computing the coproduct type corresponding to this `HList`.
   *
   * @author Miles Sabin
   */
  trait Union[L <: HList] { type Out <: Coproduct }

  object Union {
    def apply[L <: HList](implicit union: Union[L]): Aux[L, union.Out] = union

    type Aux[L <: HList, Out0 <: Coproduct] = Union[L] { type Out = Out0 }

    implicit def hnilUnion[H]: Aux[HNil, CNil] =
      new Union[HNil] {
        type Out = CNil
      }

    implicit def hlistUnion[H, T <: HList](implicit ut: Union[T]): Aux[H :: T, H :+: ut.Out] =
      new Union[H :: T] {
        type Out = H :+: ut.Out
      }
  }

  /**
   * Type class supporting computing the type-level Nat corresponding to the length of this `HList`.
   *
   * @author Miles Sabin
   */
  trait Length[L <: HList] extends DepFn0 { type Out <: Nat }

  object Length {
    def apply[L <: HList](implicit length: Length[L]): Aux[L, length.Out] = length

    import akka.shapeless.nat._
    type Aux[L <: HList, N <: Nat] = Length[L] { type Out = N }
    implicit def hnilLength: Aux[HNil, _0] = new Length[HNil] {
      type Out = _0
      def apply(): Out = _0
    }

    implicit def hlistLength[H, T <: HList, N <: Nat](implicit lt: Aux[T, N], sn: Witness.Aux[Succ[N]]): Aux[H :: T, Succ[N]] = new Length[H :: T] {
      type Out = Succ[N]
      def apply(): Out = sn.value
    }
  }

  /**
   * Type class supporting mapping a higher ranked function over this `HList`.
   *
   * @author Miles Sabin
   */
  trait Mapper[HF, In <: HList] extends DepFn1[In] { type Out <: HList }

  object Mapper {
    def apply[F, L <: HList](implicit mapper: Mapper[F, L]): Aux[F, L, mapper.Out] = mapper

    type Aux[HF, In <: HList, Out0 <: HList] = Mapper[HF, In] { type Out = Out0 }

    implicit def hnilMapper1[HF]: Aux[HF, HNil, HNil] =
      new Mapper[HF, HNil] {
        type Out = HNil
        def apply(l: HNil): Out = HNil
      }

    implicit def hlistMapper1[HF <: Poly, InH, InT <: HList](implicit hc: Case1[HF, InH], mt: Mapper[HF, InT]): Aux[HF, InH :: InT, hc.Result :: mt.Out] =
      new Mapper[HF, InH :: InT] {
        type Out = hc.Result :: mt.Out
        def apply(l: InH :: InT): Out = hc(l.head) :: mt(l.tail)
      }
  }

  /**
   * Type class supporting flatmapping a higher ranked function over this `HList`.
   *
   * @author Miles Sabin
   */
  trait FlatMapper[HF, In <: HList] extends DepFn1[In] { type Out <: HList }

  object FlatMapper {
    def apply[F, L <: HList](implicit mapper: FlatMapper[F, L]): Aux[F, L, mapper.Out] = mapper

    type Aux[HF, In <: HList, Out0 <: HList] = FlatMapper[HF, In] { type Out = Out0 }

    implicit def hnilFlatMapper1[HF]: Aux[HF, HNil, HNil] =
      new FlatMapper[HF, HNil] {
        type Out = HNil
        def apply(l: HNil): Out = HNil
      }

    implicit def hlistFlatMapper1[HF <: Poly, InH, OutH <: HList, InT <: HList, OutT <: HList, Out0 <: HList](implicit hc: Case1.Aux[HF, InH, OutH],
                                                                                                              mt: FlatMapper.Aux[HF, InT, OutT],
                                                                                                              prepend: Prepend.Aux[OutH, OutT, Out0]): Aux[HF, InH :: InT, Out0] =
      new FlatMapper[HF, InH :: InT] {
        type Out = Out0
        def apply(l: InH :: InT): Out = prepend(hc(l.head), mt(l.tail))
      }
  }

  /**
   * Type class supporting mapping a constant valued function over this `HList`.
   *
   * @author Miles Sabin
   */
  trait ConstMapper[C, L <: HList] extends DepFn2[C, L] { type Out <: HList }

  object ConstMapper {
    def apply[C, L <: HList](implicit mapper: ConstMapper[C, L]): Aux[C, L, mapper.Out] = mapper

    type Aux[C, L <: HList, Out0 <: HList] = ConstMapper[C, L] { type Out = Out0 }

    implicit def hnilConstMapper[C]: Aux[C, HNil, HNil] =
      new ConstMapper[C, HNil] {
        type Out = HNil
        def apply(c: C, l: HNil): Out = l
      }

    implicit def hlistConstMapper[H, T <: HList, C](implicit mct: ConstMapper[C, T]): Aux[C, H :: T, C :: mct.Out] =
      new ConstMapper[C, H :: T] {
        type Out = C :: mct.Out
        def apply(c: C, l: H :: T): Out = c :: mct(c, l.tail)
      }
  }

  /**
   * Type class supporting mapping a polymorphic function over this `HList` and then folding the result using a
   * monomorphic function value.
   *
   * @author Miles Sabin
   */
  trait MapFolder[L <: HList, R, HF] {
    def apply(l: L, in: R, op: (R, R) ⇒ R): R
  }

  object MapFolder {
    def apply[L <: HList, R, F](implicit folder: MapFolder[L, R, F]) = folder

    implicit def hnilMapFolder[R, HF]: MapFolder[HNil, R, HF] = new MapFolder[HNil, R, HF] {
      def apply(l: HNil, in: R, op: (R, R) ⇒ R): R = in
    }

    implicit def hlistMapFolder[H, T <: HList, R, HF <: Poly](implicit hc: Case1.Aux[HF, H, R], tf: MapFolder[T, R, HF]): MapFolder[H :: T, R, HF] =
      new MapFolder[H :: T, R, HF] {
        def apply(l: H :: T, in: R, op: (R, R) ⇒ R): R = op(hc(l.head), tf(l.tail, in, op))
      }
  }

  /**
   * Type class supporting left-folding a polymorphic binary function over this `HList`.
   *
   * @author Miles Sabin
   */
  trait LeftFolder[L <: HList, In, HF] extends DepFn2[L, In]

  object LeftFolder {
    def apply[L <: HList, In, F](implicit folder: LeftFolder[L, In, F]): Aux[L, In, F, folder.Out] = folder

    type Aux[L <: HList, In, HF, Out0] = LeftFolder[L, In, HF] { type Out = Out0 }

    implicit def hnilLeftFolder[In, HF]: Aux[HNil, In, HF, In] =
      new LeftFolder[HNil, In, HF] {
        type Out = In
        def apply(l: HNil, in: In): Out = in
      }

    implicit def hlistLeftFolder[H, T <: HList, In, HF, OutH](implicit f: Case2.Aux[HF, In, H, OutH], ft: LeftFolder[T, OutH, HF]): Aux[H :: T, In, HF, ft.Out] =
      new LeftFolder[H :: T, In, HF] {
        type Out = ft.Out
        def apply(l: H :: T, in: In): Out = ft(l.tail, f(in, l.head))
      }
  }

  /**
   * Type class supporting right-folding a polymorphic binary function over this `HList`.
   *
   * @author Miles Sabin
   */
  trait RightFolder[L <: HList, In, HF] extends DepFn2[L, In]

  object RightFolder {
    def apply[L <: HList, In, F](implicit folder: RightFolder[L, In, F]): Aux[L, In, F, folder.Out] = folder

    type Aux[L <: HList, In, HF, Out0] = RightFolder[L, In, HF] { type Out = Out0 }

    implicit def hnilRightFolder[In, HF]: Aux[HNil, In, HF, In] =
      new RightFolder[HNil, In, HF] {
        type Out = In
        def apply(l: HNil, in: In): Out = in
      }

    implicit def hlistRightFolder[H, T <: HList, In, HF, OutT](implicit ft: RightFolder.Aux[T, In, HF, OutT], f: Case2[HF, H, OutT]): Aux[H :: T, In, HF, f.Result] =
      new RightFolder[H :: T, In, HF] {
        type Out = f.Result
        def apply(l: H :: T, in: In): Out = f(l.head, ft(l.tail, in))
      }
  }

  /**
   * Type class supporting left-reducing a polymorphic binary function over this `HList`.
   *
   * @author Miles Sabin
   */
  trait LeftReducer[L <: HList, HF] extends DepFn1[L]

  object LeftReducer {
    def apply[L <: HList, F](implicit reducer: LeftReducer[L, F]): Aux[L, F, reducer.Out] = reducer

    type Aux[L <: HList, HF, Out0] = LeftReducer[L, HF] { type Out = Out0 }
    implicit def leftReducer[H, T <: HList, HF](implicit folder: LeftFolder[T, H, HF]): Aux[H :: T, HF, folder.Out] =
      new LeftReducer[H :: T, HF] {
        type Out = folder.Out
        def apply(l: H :: T): Out = folder.apply(l.tail, l.head)
      }
  }

  /**
   * Type class supporting right-reducing a polymorphic binary function over this `HList`.
   *
   * @author Miles Sabin
   */
  trait RightReducer[L <: HList, HF] extends DepFn1[L]

  object RightReducer {
    def apply[L <: HList, F](implicit reducer: RightReducer[L, F]): Aux[L, F, reducer.Out] = reducer

    type Aux[L <: HList, HF, Out0] = RightReducer[L, HF] { type Out = Out0 }

    implicit def hsingleRightReducer[H, HF]: Aux[H :: HNil, HF, H] =
      new RightReducer[H :: HNil, HF] {
        type Out = H
        def apply(l: H :: HNil): Out = l.head
      }

    implicit def hlistRightReducer[H, T <: HList, HF, OutT](implicit rt: RightReducer.Aux[T, HF, OutT], f: Case2[HF, H, OutT]): Aux[H :: T, HF, f.Result] =
      new RightReducer[H :: T, HF] {
        type Out = f.Result
        def apply(l: H :: T): Out = f(l.head, rt(l.tail))
      }
  }

  /**
   * Type class supporting unification of this `HList`.
   *
   * @author Miles Sabin
   */
  trait Unifier[L <: HList] extends DepFn1[L] { type Out <: HList }

  object Unifier {
    def apply[L <: HList](implicit unifier: Unifier[L]): Aux[L, unifier.Out] = unifier

    type Aux[L <: HList, Out0 <: HList] = Unifier[L] { type Out = Out0 }

    implicit val hnilUnifier: Aux[HNil, HNil] = new Unifier[HNil] {
      type Out = HNil
      def apply(l: HNil): Out = l
    }

    implicit def hsingleUnifier[T]: Aux[T :: HNil, T :: HNil] =
      new Unifier[T :: HNil] {
        type Out = T :: HNil
        def apply(l: T :: HNil): Out = l
      }

    implicit def hlistUnifier[H1, H2, L, T <: HList](implicit u: Lub[H1, H2, L], lt: Unifier[L :: T]): Aux[H1 :: H2 :: T, L :: lt.Out] =
      new Unifier[H1 :: H2 :: T] {
        type Out = L :: lt.Out
        def apply(l: H1 :: H2 :: T): Out = u.left(l.head) :: lt(u.right(l.tail.head) :: l.tail.tail)
      }
  }

  /**
   * Type class supporting unification of all elements that are subtypes of `B` in this `HList` to `B`, with all other
   * elements left unchanged.
   *
   * @author Travis Brown
   */
  trait SubtypeUnifier[L <: HList, B] extends DepFn1[L] { type Out <: HList }

  object SubtypeUnifier {
    def apply[L <: HList, B](implicit unifier: SubtypeUnifier[L, B]): Aux[L, B, unifier.Out] = unifier

    type Aux[L <: HList, B, Out0 <: HList] = SubtypeUnifier[L, B] { type Out = Out0 }

    implicit def hnilSubtypeUnifier[B]: Aux[HNil, B, HNil] =
      new SubtypeUnifier[HNil, B] {
        type Out = HNil
        def apply(l: HNil): Out = l
      }

    implicit def hlistSubtypeUnifier1[H, T <: HList, B](implicit st: H <:< B, sut: SubtypeUnifier[T, B]): Aux[H :: T, B, B :: sut.Out] =
      new SubtypeUnifier[H :: T, B] {
        type Out = B :: sut.Out
        def apply(l: H :: T): Out = st(l.head) :: sut(l.tail)
      }

    implicit def hlistSubtypeUnifier2[H, T <: HList, B](implicit nst: H <:!< B, sut: SubtypeUnifier[T, B]): Aux[H :: T, B, H :: sut.Out] =
      new SubtypeUnifier[H :: T, B] {
        type Out = H :: sut.Out
        def apply(l: H :: T): Out = l.head :: sut(l.tail)
      }
  }

  /**
   * Type class supporting conversion of this `HList` to an ordinary `List` with elements typed as the least upper bound
   * of the types of the elements of this `HList`.
   *
   * @author Miles Sabin
   */
  trait ToList[-L <: HList, Lub] {
    def apply(l: L): List[Lub]
  }

  trait LowPriorityToList {
    implicit def hlistToListAny[L <: HList]: ToList[L, Any] =
      new ToList[L, Any] {
        type Out = List[Any]
        val b = scala.collection.mutable.ListBuffer.empty[Any]

        def apply(l: L): Out = {
          @tailrec
          def loop(l: HList): Unit = l match {
            case hd :: tl ⇒
              b += hd; loop(tl)
            case _ ⇒
          }
          loop(l)
          b.toList
        }
      }
  }

  object ToList extends LowPriorityToList {
    def apply[L <: HList, Lub](implicit toList: ToList[L, Lub]) = toList

    implicit def hnilToList[T]: ToList[HNil, T] =
      new ToList[HNil, T] {
        type Out = List[T]
        def apply(l: HNil): Out = Nil
      }

    implicit def hsingleToList[T]: ToList[T :: HNil, T] =
      new ToList[T :: HNil, T] {
        type Out = List[T]
        def apply(l: T :: HNil): Out = List(l.head)
      }

    implicit def hlistToList[H1, H2, T <: HList, L](implicit u: Lub[H1, H2, L], ttl: ToList[H2 :: T, L]): ToList[H1 :: H2 :: T, L] =
      new ToList[H1 :: H2 :: T, L] {
        type Out = List[L]
        def apply(l: H1 :: H2 :: T): Out = u.left(l.head) :: ttl(l.tail)
      }
  }

  /**
   * Type class supporting conversion of this `HList` to an `Array` with elements typed as the least upper bound
   * of the types of the elements of this `HList`.
   *
   * @author Miles Sabin
   */
  trait ToArray[-L <: HList, Lub] {
    def apply(len: Int, l: L, i: Int): Array[Lub]
  }

  trait LowPriorityToArray {
    implicit def hlistToArrayAnyRef[L <: HList]: ToArray[L, Any] =
      new ToArray[L, Any] {
        def apply(len: Int, l: L, i: Int): Array[Any] = {
          val arr = Array[Any](len)

          @tailrec
          def loop(l: HList, i: Int): Unit = l match {
            case hd :: tl ⇒
              arr(i) = hd; loop(tl, i + 1)
            case _ ⇒
          }
          loop(l, 0)
          arr
        }
      }
  }

  object ToArray extends LowPriorityToArray {
    def apply[L <: HList, Lub](implicit toArray: ToArray[L, Lub]) = toArray

    import scala.reflect.ClassTag

    implicit def hnilToArray[T: ClassTag]: ToArray[HNil, T] =
      new ToArray[HNil, T] {
        def apply(len: Int, l: HNil, i: Int) = Array.ofDim[T](len)
      }

    implicit def hsingleToArray[T: ClassTag]: ToArray[T :: HNil, T] =
      new ToArray[T :: HNil, T] {
        def apply(len: Int, l: T :: HNil, i: Int) = {
          val arr = Array.ofDim[T](len)
          arr(i) = l.head
          arr
        }
      }

    implicit def hlistToArray[H1, H2, T <: HList, L](implicit u: Lub[H1, H2, L], tta: ToArray[H2 :: T, L]): ToArray[H1 :: H2 :: T, L] =
      new ToArray[H1 :: H2 :: T, L] {
        def apply(len: Int, l: H1 :: H2 :: T, i: Int) = {
          val arr = tta(len, l.tail, i + 1)
          arr(i) = u.left(l.head)
          arr
        }
      }
  }

  /**
   * Type class supporting conversion of this `HList` to a tuple.
   *
   * @author Miles Sabin
   */
  trait Tupler[L <: HList] extends DepFn1[L]

  object Tupler extends TuplerInstances {
    def apply[L <: HList](implicit tupler: Tupler[L]): Aux[L, tupler.Out] = tupler

    implicit val hnilTupler: Aux[HNil, Unit] =
      new Tupler[HNil] {
        type Out = Unit
        def apply(l: HNil): Out = ()
      }
  }

  /**
   * Type class supporting access to the last element of this `HList`. Available only if this `HList` has at least one
   * element.
   *
   * @author Miles Sabin
   */
  trait Last[L <: HList] extends DepFn1[L]

  object Last {
    def apply[L <: HList](implicit last: Last[L]): Aux[L, last.Out] = last

    type Aux[L <: HList, Out0] = Last[L] { type Out = Out0 }

    implicit def hsingleLast[H]: Aux[H :: HNil, H] =
      new Last[H :: HNil] {
        type Out = H
        def apply(l: H :: HNil): Out = l.head
      }

    implicit def hlistLast[H, T <: HList](implicit lt: Last[T]): Aux[H :: T, lt.Out] =
      new Last[H :: T] {
        type Out = lt.Out
        def apply(l: H :: T): Out = lt(l.tail)
      }
  }

  /**
   * Type class supporting access to all but the last element of this `HList`. Available only if this `HList` has at
   * least one element.
   *
   * @author Miles Sabin
   */
  trait Init[L <: HList] extends DepFn1[L] { type Out <: HList }

  object Init {
    def apply[L <: HList](implicit init: Init[L]): Aux[L, init.Out] = init

    type Aux[L <: HList, Out0 <: HList] = Init[L] { type Out = Out0 }

    implicit def hsingleInit[H]: Aux[H :: HNil, HNil] =
      new Init[H :: HNil] {
        type Out = HNil
        def apply(l: H :: HNil): Out = HNil
      }

    implicit def hlistInit[H, T <: HList, OutH, OutT <: HList](implicit it: Init[T]): Aux[H :: T, H :: it.Out] =
      new Init[H :: T] {
        type Out = H :: it.Out
        def apply(l: H :: T): Out = l.head :: it(l.tail)
      }
  }

  /**
   * Type class supporting access to the first element of this `HList` of type `U`. Available only if this `HList`
   * contains an element of type `U`.
   *
   * @author Miles Sabin
   */
  trait Selector[L <: HList, U] extends DepFn1[L] { type Out = U }

  object Selector {
    def apply[L <: HList, U](implicit selector: Selector[L, U]): Aux[L, U] = selector

    type Aux[L <: HList, U] = Selector[L, U]

    implicit def hlistSelect1[H, T <: HList]: Aux[H :: T, H] =
      new Selector[H :: T, H] {
        def apply(l: H :: T) = l.head
      }

    implicit def hlistSelect[H, T <: HList, U](implicit st: Selector[T, U]): Aux[H :: T, U] =
      new Selector[H :: T, U] {
        def apply(l: H :: T) = st(l.tail)
      }
  }

  /**
   * Type class supporting access to the all elements of this `HList` of type `U`.
   *
   * @author Alois Cochard
   */
  trait Filter[L <: HList, U] extends DepFn1[L] { type Out <: HList }

  object Filter {
    def apply[L <: HList, U](implicit filter: Filter[L, U]): Aux[L, U, filter.Out] = filter

    type Aux[L <: HList, U, Out0 <: HList] = Filter[L, U] { type Out = Out0 }

    implicit def hlistFilterHNil[L <: HList, U]: Aux[HNil, U, HNil] =
      new Filter[HNil, U] {
        type Out = HNil
        def apply(l: HNil): Out = HNil
      }

    implicit def hlistFilter1[L <: HList, H](implicit f: Filter[L, H]): Aux[H :: L, H, H :: f.Out] =
      new Filter[H :: L, H] {
        type Out = H :: f.Out
        def apply(l: H :: L): Out = l.head :: f(l.tail)
      }

    implicit def hlistFilter2[H, L <: HList, U](implicit f: Filter[L, U], e: U =:!= H): Aux[H :: L, U, f.Out] =
      new Filter[H :: L, U] {
        type Out = f.Out
        def apply(l: H :: L): Out = f(l.tail)
      }
  }

  /**
   * Type class supporting access to the all elements of this `HList` of type different than `U`.
   *
   * @author Alois Cochard
   */
  trait FilterNot[L <: HList, U] extends DepFn1[L] { type Out <: HList }

  object FilterNot {
    def apply[L <: HList, U](implicit filter: FilterNot[L, U]): Aux[L, U, filter.Out] = filter

    type Aux[L <: HList, U, Out0 <: HList] = FilterNot[L, U] { type Out = Out0 }

    implicit def hlistFilterNotHNil[L <: HList, U]: Aux[HNil, U, HNil] =
      new FilterNot[HNil, U] {
        type Out = HNil
        def apply(l: HNil): Out = HNil
      }

    implicit def hlistFilterNot1[L <: HList, H](implicit f: FilterNot[L, H]): Aux[H :: L, H, f.Out] =
      new FilterNot[H :: L, H] {
        type Out = f.Out
        def apply(l: H :: L): Out = f(l.tail)
      }

    implicit def hlistFilterNot2[H, L <: HList, U, Out <: HList](implicit f: FilterNot[L, U], e: U =:!= H): Aux[H :: L, U, H :: f.Out] =
      new FilterNot[H :: L, U] {
        type Out = H :: f.Out
        def apply(l: H :: L): Out = l.head :: f(l.tail)
      }
  }

  /**
   * Type class supporting removal of an element from this `HList`. Available only if this `HList` contains an
   * element of type `U`.
   *
   * @author Stacy Curl
   */
  trait Remove[L <: HList, E] extends DepFn1[L]

  object Remove {
    def apply[L <: HList, E](implicit remove: Remove[L, E]): Aux[L, E, remove.Out] = remove

    type Aux[L <: HList, E, Out0] = Remove[L, E] { type Out = Out0 }

    implicit def hlistRemove1[H, T <: HList]: Aux[H :: T, H, (H, T)] =
      new Remove[H :: T, H] {
        type Out = (H, T)
        def apply(l: H :: T): Out = (l.head, l.tail)
      }

    implicit def hlistRemove[H, T <: HList, E, OutT <: HList](implicit r: Aux[T, E, (E, OutT)]): Aux[H :: T, E, (E, H :: OutT)] =
      new Remove[H :: T, E] {
        type Out = (E, H :: OutT)
        def apply(l: H :: T): Out = {
          val (e, tail) = r(l.tail)
          (e, l.head :: tail)
        }
      }
  }

  /**
   * Type class supporting removal of a sublist from this `HList`. Available only if this `HList` contains a
   * sublist of type `SL`.
   *
   * The elements of `SL` do not have to be contiguous in this `HList`.
   *
   * @author Stacy Curl
   */
  trait RemoveAll[L <: HList, SL <: HList] extends DepFn1[L]

  object RemoveAll {
    def apply[L <: HList, SL <: HList](implicit remove: RemoveAll[L, SL]): Aux[L, SL, remove.Out] = remove

    type Aux[L <: HList, SL <: HList, Out0] = RemoveAll[L, SL] { type Out = Out0 }

    implicit def hlistRemoveAllNil[L <: HList]: Aux[L, HNil, (HNil, L)] =
      new RemoveAll[L, HNil] {
        type Out = (HNil, L)
        def apply(l: L): Out = (HNil, l)
      }

    implicit def hlistRemoveAll[L <: HList, E, RemE <: HList, Rem <: HList, SLT <: HList](implicit rt: Remove.Aux[L, E, (E, RemE)], st: Aux[RemE, SLT, (SLT, Rem)]): Aux[L, E :: SLT, (E :: SLT, Rem)] =
      new RemoveAll[L, E :: SLT] {
        type Out = (E :: SLT, Rem)
        def apply(l: L): Out = {
          val (e, rem) = rt(l)
          val (sl, left) = st(rem)
          (e :: sl, left)
        }
      }
  }

  /**
   * Type class supporting replacement of the first element of type U from this `HList` with an element of type V.
   * Available only if this `HList` contains an element of type `U`.
   *
   * @author Miles Sabin
   */
  trait Replacer[L <: HList, U, V] extends DepFn2[L, V]

  object Replacer {
    def apply[L <: HList, U, V](implicit replacer: Replacer[L, U, V]): Aux[L, U, V, replacer.Out] = replacer

    type Aux[L <: HList, U, V, Out0] = Replacer[L, U, V] { type Out = Out0 }

    implicit def hlistReplacer1[T <: HList, U, V]: Aux[U :: T, U, V, (U, V :: T)] =
      new Replacer[U :: T, U, V] {
        type Out = (U, V :: T)
        def apply(l: U :: T, v: V): Out = (l.head, v :: l.tail)
      }

    implicit def hlistReplacer2[H, T <: HList, U, V, OutT <: HList](implicit ut: Aux[T, U, V, (U, OutT)]): Aux[H :: T, U, V, (U, H :: OutT)] =
      new Replacer[H :: T, U, V] {
        type Out = (U, H :: OutT)
        def apply(l: H :: T, v: V): Out = {
          val (u, outT) = ut(l.tail, v)
          (u, l.head :: outT)
        }
      }
  }

  /**
   * Type class supporting replacement of the Nth element of this `HList` with an element of type V. Available only if
   * this `HList` contains at least N elements.
   *
   * @author Miles Sabin
   */
  trait ReplaceAt[L <: HList, N <: Nat, V] extends DepFn2[L, V]

  object ReplaceAt {
    def apply[L <: HList, N <: Nat, V](implicit replacer: ReplaceAt[L, N, V]): Aux[L, N, V, replacer.Out] = replacer

    type Aux[L <: HList, N <: Nat, V, Out0] = ReplaceAt[L, N, V] { type Out = Out0 }

    implicit def hlistReplaceAt1[H, T <: HList, V]: Aux[H :: T, _0, V, (H, V :: T)] =
      new ReplaceAt[H :: T, _0, V] {
        type Out = (H, V :: T)
        def apply(l: H :: T, v: V): Out = (l.head, v :: l.tail)
      }

    implicit def hlistReplaceAt2[H, T <: HList, N <: Nat, U, V, Out0 <: HList](implicit ut: Aux[T, N, V, (U, Out0)]): Aux[H :: T, Succ[N], V, (U, H :: Out0)] =
      new ReplaceAt[H :: T, Succ[N], V] {
        type Out = (U, H :: Out0)
        def apply(l: H :: T, v: V): Out = {
          val (u, outT) = ut(l.tail, v)
          (u, l.head :: outT)
        }
      }
  }

  /**
   * Type class supporting access to the ''nth'' element of this `HList`. Available only if this `HList` has at least
   * ''n'' elements.
   *
   * @author Miles Sabin
   */
  trait At[L <: HList, N <: Nat] extends DepFn1[L]

  object At {
    def apply[L <: HList, N <: Nat](implicit at: At[L, N]): Aux[L, N, at.Out] = at

    type Aux[L <: HList, N <: Nat, Out0] = At[L, N] { type Out = Out0 }

    implicit def hlistAtZero[H, T <: HList]: Aux[H :: T, _0, H] =
      new At[H :: T, _0] {
        type Out = H
        def apply(l: H :: T): Out = l.head
      }

    implicit def hlistAtN[H, T <: HList, N <: Nat](implicit att: At[T, N]): Aux[H :: T, Succ[N], att.Out] =
      new At[H :: T, Succ[N]] {
        type Out = att.Out
        def apply(l: H :: T): Out = att(l.tail)
      }
  }

  /**
   * Type class supporting removal of the first ''n'' elements of this `HList`. Available only if this `HList` has at
   * least ''n'' elements.
   *
   * @author Miles Sabin
   */
  trait Drop[L <: HList, N <: Nat] extends DepFn1[L] { type Out <: HList }

  object Drop {
    def apply[L <: HList, N <: Nat](implicit drop: Drop[L, N]): Aux[L, N, drop.Out] = drop

    type Aux[L <: HList, N <: Nat, Out0 <: HList] = Drop[L, N] { type Out = Out0 }

    implicit def hlistDrop1[L <: HList]: Aux[L, _0, L] =
      new Drop[L, _0] {
        type Out = L
        def apply(l: L): Out = l
      }

    implicit def hlistDrop2[H, T <: HList, N <: Nat](implicit dt: Drop[T, N]): Aux[H :: T, Succ[N], dt.Out] =
      new Drop[H :: T, Succ[N]] {
        type Out = dt.Out
        def apply(l: H :: T): Out = dt(l.tail)
      }
  }

  /**
   * Type class supporting retrieval of the first ''n'' elements of this `HList`. Available only if this `HList` has at
   * least ''n'' elements.
   *
   * @author Miles Sabin
   */
  trait Take[L <: HList, N <: Nat] extends DepFn1[L] { type Out <: HList }

  object Take {
    def apply[L <: HList, N <: Nat](implicit take: Take[L, N]): Aux[L, N, take.Out] = take

    type Aux[L <: HList, N <: Nat, Out0 <: HList] = Take[L, N] { type Out = Out0 }

    implicit def hlistTake1[L <: HList]: Aux[L, _0, HNil] =
      new Take[L, _0] {
        type Out = HNil
        def apply(l: L): Out = HNil
      }

    implicit def hlistTake2[H, T <: HList, N <: Nat, Out <: HList](implicit tt: Take[T, N]): Aux[H :: T, Succ[N], H :: tt.Out] =
      new Take[H :: T, Succ[N]] {
        type Out = H :: tt.Out
        def apply(l: H :: T): Out = l.head :: tt(l.tail)
      }
  }

  /**
   * Type class supporting splitting this `HList` at the ''nth'' element returning the prefix and suffix as a pair.
   * Available only if this `HList` has at least ''n'' elements.
   *
   * @author Miles Sabin
   */
  trait Split[L <: HList, N <: Nat] extends DepFn1[L]

  object Split {
    def apply[L <: HList, N <: Nat](implicit split: Split[L, N]): Aux[L, N, split.Out] = split

    type Aux[L <: HList, N <: Nat, Out0] = Split[L, N] { type Out = Out0 }

    implicit def split[L <: HList, N <: Nat, P <: HList, S <: HList](implicit split: Split0[HNil, L, N, P, S]): Aux[L, N, (P, S)] =
      new Split[L, N] {
        type Out = (P, S)
        def apply(l: L): Out = split(HNil, l)
      }

    trait Split0[AccP <: HList, AccS <: HList, N <: Nat, P <: HList, S <: HList] {
      def apply(accP: AccP, accS: AccS): (P, S)
    }

    object Split0 {
      implicit def hlistSplit1[P <: HList, S <: HList]: Split0[P, S, _0, P, S] =
        new Split0[P, S, _0, P, S] {
          def apply(accP: P, accS: S): (P, S) = (accP, accS)
        }

      implicit def hlistSplit2[AccP <: HList, AccSH, AccST <: HList, N <: Nat, P <: HList, S <: HList](implicit st: Split0[AccP, AccST, N, P, S]): Split0[AccP, AccSH :: AccST, Succ[N], AccSH :: P, S] =
        new Split0[AccP, AccSH :: AccST, Succ[N], AccSH :: P, S] {
          def apply(accP: AccP, accS: AccSH :: AccST): (AccSH :: P, S) =
            st(accP, accS.tail) match { case (prefix, suffix) ⇒ (accS.head :: prefix, suffix) }
        }
    }
  }

  /**
   * Type class supporting splitting this `HList` at the ''nth'' element returning the reverse prefix and suffix as a
   * pair. Available only if this `HList` has at least ''n'' elements.
   *
   * @author Miles Sabin
   */
  trait ReverseSplit[L <: HList, N <: Nat] extends DepFn1[L]

  object ReverseSplit {
    def apply[L <: HList, N <: Nat](implicit split: ReverseSplit[L, N]): Aux[L, N, split.Out] = split

    type Aux[L <: HList, N <: Nat, Out0] = ReverseSplit[L, N] { type Out = Out0 }

    implicit def reverseSplit[L <: HList, N <: Nat, P <: HList, S <: HList](implicit split: ReverseSplit0[HNil, L, N, P, S]): Aux[L, N, (P, S)] =
      new ReverseSplit[L, N] {
        type Out = (P, S)
        def apply(l: L): Out = split(HNil, l)
      }

    trait ReverseSplit0[AccP <: HList, AccS <: HList, N <: Nat, P, S] {
      def apply(accP: AccP, accS: AccS): (P, S)
    }

    object ReverseSplit0 {
      implicit def hlistReverseSplit1[P <: HList, S <: HList]: ReverseSplit0[P, S, _0, P, S] =
        new ReverseSplit0[P, S, _0, P, S] {
          def apply(accP: P, accS: S): (P, S) = (accP, accS)
        }

      implicit def hlistReverseSplit2[AccP <: HList, AccSH, AccST <: HList, N <: Nat, P, S](implicit st: ReverseSplit0[AccSH :: AccP, AccST, N, P, S]): ReverseSplit0[AccP, AccSH :: AccST, Succ[N], P, S] =
        new ReverseSplit0[AccP, AccSH :: AccST, Succ[N], P, S] {
          def apply(accP: AccP, accS: AccSH :: AccST): (P, S) = st(accS.head :: accP, accS.tail)
        }
    }
  }

  /**
   * Type class supporting splitting this `HList` at the first occurence of an element of type `U` returning the prefix
   * and suffix as a pair. Available only if this `HList` contains an element of type `U`.
   *
   * @author Miles Sabin
   */
  trait SplitLeft[L <: HList, U] extends DepFn1[L]

  object SplitLeft {
    def apply[L <: HList, U](implicit split: SplitLeft[L, U]): Aux[L, U, split.Out] = split

    type Aux[L <: HList, U, Out0] = SplitLeft[L, U] { type Out = Out0 }

    implicit def splitLeft[L <: HList, U, P <: HList, S <: HList](implicit splitLeft: SplitLeft0[HNil, L, U, P, S]): Aux[L, U, (P, S)] =
      new SplitLeft[L, U] {
        type Out = (P, S)
        def apply(l: L): Out = splitLeft(HNil, l)
      }

    trait SplitLeft0[AccP <: HList, AccS <: HList, U, P <: HList, S <: HList] {
      def apply(accP: AccP, accS: AccS): (P, S)
    }

    trait LowPrioritySplitLeft0 {
      implicit def hlistSplitLeft1[AccP <: HList, AccSH, AccST <: HList, U, P <: HList, S <: HList](implicit slt: SplitLeft0[AccP, AccST, U, P, S]): SplitLeft0[AccP, AccSH :: AccST, U, AccSH :: P, S] =
        new SplitLeft0[AccP, AccSH :: AccST, U, AccSH :: P, S] {
          def apply(accP: AccP, accS: AccSH :: AccST): (AccSH :: P, S) =
            slt(accP, accS.tail) match { case (prefix, suffix) ⇒ (accS.head :: prefix, suffix) }
        }
    }

    object SplitLeft0 extends LowPrioritySplitLeft0 {
      implicit def hlistSplitLeft2[P <: HList, SH, ST <: HList]: SplitLeft0[P, SH :: ST, SH, P, SH :: ST] =
        new SplitLeft0[P, SH :: ST, SH, P, SH :: ST] {
          def apply(accP: P, accS: SH :: ST): (P, SH :: ST) = (accP, accS)
        }
    }
  }

  /**
   * Type class supporting splitting this `HList` at the first occurence of an element of type `U` returning the reverse
   * prefix and suffix as a pair. Available only if this `HList` contains an element of type `U`.
   *
   * @author Miles Sabin
   */
  trait ReverseSplitLeft[L <: HList, U] extends DepFn1[L]

  object ReverseSplitLeft {
    def apply[L <: HList, U](implicit split: ReverseSplitLeft[L, U]): Aux[L, U, split.Out] = split

    type Aux[L <: HList, U, Out0] = ReverseSplitLeft[L, U] { type Out = Out0 }

    implicit def reverseSplitLeft[L <: HList, U, P <: HList, S <: HList](implicit splitLeft: ReverseSplitLeft0[HNil, L, U, P, S]): Aux[L, U, (P, S)] =
      new ReverseSplitLeft[L, U] {
        type Out = (P, S)
        def apply(l: L): Out = splitLeft(HNil, l)
      }

    trait ReverseSplitLeft0[AccP <: HList, AccS <: HList, U, P, S] {
      def apply(accP: AccP, accS: AccS): (P, S)
    }

    trait LowPriorityReverseSplitLeft0 {
      implicit def hlistReverseSplitLeft1[AccP <: HList, AccSH, AccST <: HList, U, P, S](implicit slt: ReverseSplitLeft0[AccSH :: AccP, AccST, U, P, S]): ReverseSplitLeft0[AccP, AccSH :: AccST, U, P, S] =
        new ReverseSplitLeft0[AccP, AccSH :: AccST, U, P, S] {
          def apply(accP: AccP, accS: AccSH :: AccST): (P, S) = slt(accS.head :: accP, accS.tail)
        }
    }

    object ReverseSplitLeft0 extends LowPriorityReverseSplitLeft0 {
      implicit def hlistReverseSplitLeft2[P <: HList, SH, ST <: HList]: ReverseSplitLeft0[P, SH :: ST, SH, P, SH :: ST] =
        new ReverseSplitLeft0[P, SH :: ST, SH, P, SH :: ST] {
          def apply(accP: P, accS: SH :: ST): (P, SH :: ST) = (accP, accS)
        }
    }
  }

  /**
   * Type class supporting splitting this `HList` at the last occurence of an element of type `U` returning the prefix
   * and suffix as a pair. Available only if this `HList` contains an element of type `U`.
   *
   * @author Miles Sabin
   */
  trait SplitRight[L <: HList, U] extends DepFn1[L]

  object SplitRight {
    def apply[L <: HList, U](implicit split: SplitRight[L, U]): Aux[L, U, split.Out] = split

    type Aux[L <: HList, U, Out0] = SplitRight[L, U] { type Out = Out0 }

    implicit def splitRight[L <: HList, U, P <: HList, S <: HList](implicit splitRight: SplitRight0[L, HNil, HNil, U, P, S]): Aux[L, U, (P, S)] =
      new SplitRight[L, U] {
        type Out = (P, S)
        def apply(l: L): Out = splitRight(l, HNil, HNil)
      }

    trait SplitRight0[Rev <: HList, AccP <: HList, AccS <: HList, U, P <: HList, S <: HList] {
      def apply(rev: Rev, accP: AccP, accS: AccS): (P, S)
    }

    trait LowPrioritySplitRight0 {
      implicit def hlistSplitRight1[RevH, RevT <: HList, AccP <: HList, U, P <: HList, S <: HList](implicit srt: SplitRight0[RevT, RevH :: AccP, HNil, U, P, S]): SplitRight0[RevH :: RevT, AccP, HNil, U, P, S] =
        new SplitRight0[RevH :: RevT, AccP, HNil, U, P, S] {
          def apply(rev: RevH :: RevT, accP: AccP, accS: HNil): (P, S) = srt(rev.tail, rev.head :: accP, accS)
        }

      implicit def hlistSplitRight2[AccPH, AccPT <: HList, AccS <: HList, U, P <: HList, S <: HList](implicit srt: SplitRight0[HNil, AccPT, AccPH :: AccS, U, P, S]): SplitRight0[HNil, AccPH :: AccPT, AccS, U, P, S] =
        new SplitRight0[HNil, AccPH :: AccPT, AccS, U, P, S] {
          def apply(rev: HNil, accP: AccPH :: AccPT, accS: AccS): (P, S) = srt(rev, accP.tail, accP.head :: accS)
        }
    }

    object SplitRight0 extends LowPrioritySplitRight0 {
      implicit def hlistSplitRight3[PH, PT <: HList, S <: HList](implicit reverse: Reverse[PH :: PT]): SplitRight0[HNil, PH :: PT, S, PH, reverse.Out, S] =
        new SplitRight0[HNil, PH :: PT, S, PH, reverse.Out, S] {
          def apply(rev: HNil, accP: PH :: PT, accS: S): (reverse.Out, S) = (accP.reverse, accS)
        }
    }
  }

  /**
   * Type class supporting splitting this `HList` at the last occurence of an element of type `U` returning the reverse
   * prefix and suffix as a pair. Available only if this `HList` contains an element of type `U`.
   *
   * @author Miles Sabin
   */
  trait ReverseSplitRight[L <: HList, U] extends DepFn1[L]

  object ReverseSplitRight {
    def apply[L <: HList, U](implicit split: ReverseSplitRight[L, U]): Aux[L, U, split.Out] = split

    type Aux[L <: HList, U, Out0] = ReverseSplitRight[L, U] { type Out = Out0 }

    implicit def reverseSplitRight[L <: HList, U, P <: HList, S <: HList](implicit splitRight: ReverseSplitRight0[L, HNil, HNil, U, P, S]): Aux[L, U, (P, S)] =
      new ReverseSplitRight[L, U] {
        type Out = (P, S)
        def apply(l: L): Out = splitRight(l, HNil, HNil)
      }

    trait ReverseSplitRight0[Rev <: HList, AccP <: HList, AccS <: HList, U, P, S] {
      def apply(rev: Rev, accP: AccP, accS: AccS): (P, S)
    }

    trait LowPriorityReverseSplitRight0 {
      implicit def hlistReverseSplitRight1[RevH, RevT <: HList, AccP <: HList, U, P <: HList, S <: HList](implicit srt: ReverseSplitRight0[RevT, RevH :: AccP, HNil, U, P, S]): ReverseSplitRight0[RevH :: RevT, AccP, HNil, U, P, S] =
        new ReverseSplitRight0[RevH :: RevT, AccP, HNil, U, P, S] {
          def apply(rev: RevH :: RevT, accP: AccP, accS: HNil): (P, S) = srt(rev.tail, rev.head :: accP, accS)
        }

      implicit def hlistReverseSplitRight2[AccPH, AccPT <: HList, AccS <: HList, U, P <: HList, S <: HList](implicit srt: ReverseSplitRight0[HNil, AccPT, AccPH :: AccS, U, P, S]): ReverseSplitRight0[HNil, AccPH :: AccPT, AccS, U, P, S] =
        new ReverseSplitRight0[HNil, AccPH :: AccPT, AccS, U, P, S] {
          def apply(rev: HNil, accP: AccPH :: AccPT, accS: AccS): (P, S) = srt(rev, accP.tail, accP.head :: accS)
        }
    }

    object ReverseSplitRight0 extends LowPriorityReverseSplitRight0 {
      implicit def hlistReverseSplitRight3[PH, PT <: HList, S <: HList]: ReverseSplitRight0[HNil, PH :: PT, S, PH, PH :: PT, S] =
        new ReverseSplitRight0[HNil, PH :: PT, S, PH, PH :: PT, S] {
          def apply(rev: HNil, accP: PH :: PT, accS: S): (PH :: PT, S) = (accP, accS)
        }
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

  /**
   * Type class supporting zipping this `HList` with an `HList` of `HList`s returning an `HList` of `HList`s with each
   * element of this `HList` prepended to the corresponding `HList` element of the argument `HList`.
   *
   * @author Miles Sabin
   */
  trait ZipOne[H <: HList, T <: HList] extends DepFn2[H, T] { type Out <: HList }

  object ZipOne {
    def apply[H <: HList, T <: HList](implicit zip: ZipOne[H, T]): Aux[H, T, zip.Out] = zip

    type Aux[H <: HList, T <: HList, Out0 <: HList] = ZipOne[H, T] { type Out = Out0 }

    implicit def zipOne1[H <: HList]: Aux[H, HNil, HNil] =
      new ZipOne[H, HNil] {
        type Out = HNil
        def apply(h: H, t: HNil): Out = HNil
      }

    implicit def zipOne2[T <: HList]: Aux[HNil, T, HNil] =
      new ZipOne[HNil, T] {
        type Out = HNil
        def apply(h: HNil, t: T): Out = HNil
      }

    implicit def zipOne3[H, T <: HList]: Aux[H :: HNil, T :: HNil, (H :: T) :: HNil] =
      new ZipOne[H :: HNil, T :: HNil] {
        type Out = (H :: T) :: HNil
        def apply(h: H :: HNil, t: T :: HNil): Out = (h.head :: t.head) :: HNil
      }

    implicit def zipOne4[HH, HT <: HList, TH <: HList, TT <: HList](implicit zot: ZipOne[HT, TT]): Aux[HH :: HT, TH :: TT, (HH :: TH) :: zot.Out] =
      new ZipOne[HH :: HT, TH :: TT] {
        type Out = (HH :: TH) :: zot.Out
        def apply(h: HH :: HT, t: TH :: TT): Out = (h.head :: t.head) :: zot(h.tail, t.tail)
      }
  }

  /**
   * Type class supporting transposing this `HList`.
   *
   * @author Miles Sabin
   */
  trait Transposer[L <: HList] extends DepFn1[L] { type Out <: HList }

  object Transposer {
    def apply[L <: HList](implicit transposer: Transposer[L]): Aux[L, transposer.Out] = transposer

    type Aux[L <: HList, Out0 <: HList] = Transposer[L] { type Out = Out0 }

    implicit def hnilTransposer: Aux[HNil, HNil] =
      new Transposer[HNil] {
        type Out = HNil
        def apply(l: HNil): Out = l
      }

    implicit def hlistTransposer1[H <: HList, MC <: HList, Out0 <: HList](implicit mc: ConstMapper.Aux[HNil, H, MC], zo: ZipOne.Aux[H, MC, Out0]): Aux[H :: HNil, Out0] =
      new Transposer[H :: HNil] {
        type Out = Out0
        def apply(l: H :: HNil): Out = zo(l.head, mc(HNil, l.head))
      }

    implicit def hlistTransposer2[H <: HList, TH <: HList, TT <: HList, OutT <: HList, Out0 <: HList](implicit tt: Aux[TH :: TT, OutT], zo: ZipOne.Aux[H, OutT, Out0]): Aux[H :: TH :: TT, Out0] =
      new Transposer[H :: TH :: TT] {
        type Out = Out0
        def apply(l: H :: TH :: TT): Out = zo(l.head, tt(l.tail))
      }
  }

  /**
   * Type class supporting zipping this `HList` of `HList`s returning an `HList` of tuples.
   *
   * @author Miles Sabin
   */
  trait Zip[L <: HList] extends DepFn1[L] { type Out <: HList }

  object Zip {
    def apply[L <: HList](implicit zip: Zip[L]): Aux[L, zip.Out] = zip

    type Aux[L <: HList, Out0 <: HList] = Zip[L] { type Out = Out0 }

    implicit def zipper[L <: HList, OutT <: HList](implicit transposer: Transposer.Aux[L, OutT],
                                                   mapper: Mapper[tupled.type, OutT]): Aux[L, mapper.Out] =
      new Zip[L] {
        type Out = mapper.Out
        def apply(l: L): Out = l.transpose map tupled
      }
  }

  /**
   * Type class supporting unzipping this `HList` of tuples returning a tuple of `HList`s.
   *
   * @author Miles Sabin
   */
  trait Unzip[L <: HList] extends DepFn1[L]

  object Unzip {
    def apply[L <: HList](implicit unzip: Unzip[L]): Aux[L, unzip.Out] = unzip

    type Aux[L <: HList, Out0] = Unzip[L] { type Out = Out0 }

    implicit def unzipper[L <: HList, OutM <: HList, OutT <: HList](implicit mapper: Mapper.Aux[productElements.type, L, OutM],
                                                                    transposer: Transposer.Aux[OutM, OutT],
                                                                    tupler: Tupler[OutT]): Aux[L, tupler.Out] =
      new Unzip[L] {
        type Out = tupler.Out
        def apply(l: L): Out = (l map productElements).transpose.tupled
      }
  }

  /**
   * Type class supporting zipping this `HList` of monomorphic function values with its argument `HList` of
   * correspondingly typed function arguments returning the result of each application as an `HList`. Available only if
   * there is evidence that the corresponding function and argument elements have compatible types.
   *
   * @author Miles Sabin
   */
  trait ZipApply[FL <: HList, AL <: HList] extends DepFn2[FL, AL] { type Out <: HList }

  object ZipApply {
    def apply[FL <: HList, AL <: HList](implicit zip: ZipApply[FL, AL]): Aux[FL, AL, zip.Out] = zip

    type Aux[FL <: HList, AL <: HList, Out0 <: HList] = ZipApply[FL, AL] { type Out = Out0 }

    implicit def hnilZipApply: Aux[HNil, HNil, HNil] =
      new ZipApply[HNil, HNil] {
        type Out = HNil
        def apply(fl: HNil, al: HNil): Out = HNil
      }

    implicit def hconsZipApply[T, R, FLT <: HList, ALT <: HList](implicit ztt: ZipApply[FLT, ALT]): Aux[(T ⇒ R) :: FLT, T :: ALT, R :: ztt.Out] =
      new ZipApply[(T ⇒ R) :: FLT, T :: ALT] {
        type Out = R :: ztt.Out
        def apply(fl: (T ⇒ R) :: FLT, al: T :: ALT): Out = fl.head(al.head) :: ztt(fl.tail, al.tail)
      }
  }

  /**
   * Type class supporting zipping an `HList` with a constant, resulting in an `HList` of tuples of the form
   * ({element from input `HList`}, {supplied constant})
   *
   * @author Cody Allen
   */
  trait ZipConst[C, L <: HList] extends DepFn2[C, L] { type Out <: HList }

  object ZipConst {
    def apply[C, L <: HList](implicit zip: ZipConst[C, L]): Aux[C, L, zip.Out] = zip

    type Aux[C, L <: HList, Out0 <: HList] = ZipConst[C, L] { type Out = Out0 }

    implicit def constZipper[C, L <: HList, M <: HList](implicit mapper: ConstMapper.Aux[C, L, M],
                                                        zipper: Zip[L :: M :: HNil]): Aux[C, L, zipper.Out] =
      new ZipConst[C, L] {
        type Out = zipper.Out
        def apply(c: C, l: L) = zipper(l :: mapper(c, l) :: HNil)
      }
  }

  /**
   * Type class supporting zipping an 'HList' with another 'HList' using a 'Poly2' resulting in an HList
   *
   * @author Stacy Curl
   */
  trait ZipWith[L <: HList, R <: HList, P <: Poly2] extends DepFn2[L, R] { type Out <: HList }

  object ZipWith {
    def apply[L <: HList, R <: HList, P <: Poly2](implicit zipWith: ZipWith[L, R, P]): Aux[L, R, P, zipWith.Out] = zipWith

    type Aux[L <: HList, R <: HList, P <: Poly2, Out0 <: HList] = ZipWith[L, R, P] { type Out = Out0 }

    implicit def hnilZipWithHNil[P <: Poly2]: Aux[HNil, HNil, P, HNil] = constZipWith[HNil, HNil, P]
    implicit def hnilZipWithHList[R <: HList, P <: Poly2]: Aux[HNil, R, P, HNil] = constZipWith[HNil, R, P]
    implicit def hlistZipWithHNil[L <: HList, P <: Poly2]: Aux[L, HNil, P, HNil] = constZipWith[L, HNil, P]

    implicit def hlistZipWithHList[LH, RH, LT <: HList, RT <: HList, P <: Poly2](implicit zipWith: ZipWith[LT, RT, P], clr: Case2[P, LH, RH]): Aux[LH :: LT, RH :: RT, P, clr.Result :: zipWith.Out] =
      new ZipWith[LH :: LT, RH :: RT, P] {
        type Out = clr.Result :: zipWith.Out
        def apply(l: LH :: LT, r: RH :: RT): Out =
          clr(l.head, r.head) :: zipWith(l.tail, r.tail)
      }

    private def constZipWith[L <: HList, R <: HList, P <: Poly2]: Aux[L, R, P, HNil] =
      new ZipWith[L, R, P] {
        type Out = HNil
        def apply(l: L, r: R): HNil = HNil
      }
  }

  /**
   * Type class supporting zipping an `HList` of values with an `HList` of keys to create a record.
   *
   * @author Cody Allen
   */
  trait ZipWithKeys[K <: HList, V <: HList] extends DepFn2[K, V] { type Out <: HList }

  object ZipWithKeys {
    import akka.shapeless.record._

    def apply[K <: HList, V <: HList](implicit zipWithKeys: ZipWithKeys[K, V]): Aux[K, V, zipWithKeys.Out] = zipWithKeys

    type Aux[K <: HList, V <: HList, Out0 <: HList] = ZipWithKeys[K, V] { type Out = Out0 }

    implicit val hnilZipWithKeys: Aux[HNil, HNil, HNil] = new ZipWithKeys[HNil, HNil] {
      type Out = HNil
      def apply(k: HNil, v: HNil) = HNil
    }

    implicit def hconsZipWithKeys[KH, VH, KT <: HList, VT <: HList](implicit zipWithKeys: ZipWithKeys[KT, VT], wkh: Witness.Aux[KH]): Aux[KH :: KT, VH :: VT, FieldType[KH, VH] :: zipWithKeys.Out] =
      new ZipWithKeys[KH :: KT, VH :: VT] {
        type Out = FieldType[KH, VH] :: zipWithKeys.Out
        def apply(k: KH :: KT, v: VH :: VT): Out =
          field[wkh.T](v.head) :: zipWithKeys(k.tail, v.tail)
      }
  }
}
