/*
 * Copyright (c) 2011-14 Miles Sabin
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

//object record {
//  Ideally this would be an object rather than a package, however that appears
//  to trip bugs in implicit resolution which manifest in the use of WitnessWith
//  in updateWith
package record {
  import akka.shapeless.record._

  /**
   * Type class supporting record field selection.
   *
   * @author Miles Sabin
   */
  @annotation.implicitNotFound(msg = "No field ${K} in record ${L}")
  trait Selector[L <: HList, K] {
    type Out
    def apply(l: L): Out
  }

  trait LowPrioritySelector {
    type Aux[L <: HList, K, Out0] = Selector[L, K] { type Out = Out0 }

    implicit def hlistSelect[H, T <: HList, K](implicit st: Selector[T, K]): Aux[H :: T, K, st.Out] =
      new Selector[H :: T, K] {
        type Out = st.Out
        def apply(l: H :: T): Out = st(l.tail)
      }
  }

  object Selector extends LowPrioritySelector {
    def apply[L <: HList, K](implicit selector: Selector[L, K]): Aux[L, K, selector.Out] = selector

    implicit def hlistSelect1[K, V, T <: HList]: Aux[FieldType[K, V] :: T, K, V] =
      new Selector[FieldType[K, V]:: T, K] {
        type Out = V
        def apply(l: FieldType[K, V] :: T): Out = l.head
      }
  }

  /**
   * Type class supporting record update and extension.
   *
   * @author Miles Sabin
   */
  trait Updater[L <: HList, F] extends DepFn2[L, F] { type Out <: HList }

  trait LowPriorityUpdater {
    type Aux[L <: HList, F, Out0 <: HList] = Updater[L, F] { type Out = Out0 }

    implicit def hlistUpdater1[H, T <: HList, K, V](implicit ut: Updater[T, FieldType[K, V]]): Aux[H :: T, FieldType[K, V], H :: ut.Out] =
      new Updater[H :: T, FieldType[K, V]] {
        type Out = H :: ut.Out
        def apply(l: H :: T, f: FieldType[K, V]): Out = l.head :: ut(l.tail, f)
      }
  }

  object Updater extends LowPriorityUpdater {
    def apply[L <: HList, F](implicit updater: Updater[L, F]): Aux[L, F, updater.Out] = updater

    implicit def hnilUpdater[L <: HNil, F]: Aux[L, F, F :: HNil] =
      new Updater[L, F] {
        type Out = F :: HNil
        def apply(l: L, f: F): Out = f :: HNil
      }

    implicit def hlistUpdater2[K, V, T <: HList]: Aux[FieldType[K, V] :: T, FieldType[K, V], FieldType[K, V] :: T] =
      new Updater[FieldType[K, V]:: T, FieldType[K, V]] {
        type Out = FieldType[K, V] :: T
        def apply(l: FieldType[K, V] :: T, f: FieldType[K, V]): Out = f :: l.tail
      }
  }

  /**
   * Type class supporting modification of a record field by given function.
   *
   * @author Joni Freeman
   */
  @annotation.implicitNotFound(msg = "No field ${F} with value of type ${A} in record ${L}")
  trait Modifier[L <: HList, F, A, B] extends DepFn2[L, A ⇒ B] { type Out <: HList }

  object Modifier {
    def apply[L <: HList, F, A, B](implicit modifier: Modifier[L, F, A, B]): Aux[L, F, A, B, modifier.Out] = modifier

    type Aux[L <: HList, F, A, B, Out0 <: HList] = Modifier[L, F, A, B] { type Out = Out0 }

    implicit def hlistModify1[F, A, B, T <: HList]: Aux[FieldType[F, A] :: T, F, A, B, FieldType[F, B] :: T] =
      new Modifier[FieldType[F, A]:: T, F, A, B] {
        type Out = FieldType[F, B] :: T
        def apply(l: FieldType[F, A] :: T, f: A ⇒ B): Out = field[F](f(l.head)) :: l.tail
      }

    implicit def hlistModify[H, T <: HList, F, A, B](implicit mt: Modifier[T, F, A, B]): Aux[H :: T, F, A, B, H :: mt.Out] =
      new Modifier[H :: T, F, A, B] {
        type Out = H :: mt.Out
        def apply(l: H :: T, f: A ⇒ B): Out = l.head :: mt(l.tail, f)
      }
  }

  /**
   * Type class supporting record field removal.
   *
   * @author Miles Sabin
   */
  @annotation.implicitNotFound(msg = "No field ${K} in record ${L}")
  trait Remover[L <: HList, K] extends DepFn1[L]

  trait LowPriorityRemover {
    type Aux[L <: HList, K, Out0] = Remover[L, K] { type Out = Out0 }

    implicit def hlistRemove[H, T <: HList, K, V, OutT <: HList](implicit rt: Aux[T, K, (V, OutT)]): Aux[H :: T, K, (V, H :: OutT)] =
      new Remover[H :: T, K] {
        type Out = (V, H :: OutT)
        def apply(l: H :: T): Out = {
          val (v, tail) = rt(l.tail)
          (v, l.head :: tail)
        }
      }
  }

  object Remover extends LowPriorityRemover {
    def apply[L <: HList, K](implicit remover: Remover[L, K]): Aux[L, K, remover.Out] = remover

    implicit def hlistRemove1[K, V, T <: HList]: Aux[FieldType[K, V] :: T, K, (V, T)] =
      new Remover[FieldType[K, V]:: T, K] {
        type Out = (V, T)
        def apply(l: FieldType[K, V] :: T): Out = (l.head, l.tail)
      }
  }

  /**
   * Type class supporting renaming of a record field.
   *
   * @author Joni Freeman
   */
  @annotation.implicitNotFound(msg = "No field ${K1} in record ${L}")
  trait Renamer[L <: HList, K1, K2] extends DepFn1[L] { type Out <: HList }

  object Renamer {
    def apply[L <: HList, K1, K2](implicit renamer: Renamer[L, K1, K2]): Aux[L, K1, K2, renamer.Out] = renamer

    type Aux[L <: HList, K1, K2, Out0 <: HList] = Renamer[L, K1, K2] { type Out = Out0 }

    implicit def hlistRenamer1[T <: HList, K1, K2, V]: Aux[FieldType[K1, V] :: T, K1, K2, FieldType[K2, V] :: T] =
      new Renamer[FieldType[K1, V]:: T, K1, K2] {
        type Out = FieldType[K2, V] :: T
        def apply(l: FieldType[K1, V] :: T): Out = field[K2](l.head: V) :: l.tail
      }

    implicit def hlistRenamer[H, T <: HList, K1, K2, V](implicit rn: Renamer[T, K1, K2]): Aux[H :: T, K1, K2, H :: rn.Out] =
      new Renamer[H :: T, K1, K2] {
        type Out = H :: rn.Out
        def apply(l: H :: T): Out = l.head :: rn(l.tail)
      }
  }

  /**
   * Type class supporting collecting the keys of a record as an `HList`.
   *
   * @author Miles Sabin
   */
  trait Keys[L <: HList] extends DepFn0 { type Out <: HList }

  object Keys {
    def apply[L <: HList](implicit keys: Keys[L]): Aux[L, keys.Out] = keys

    type Aux[L <: HList, Out0 <: HList] = Keys[L] { type Out = Out0 }

    implicit def hnilKeys[L <: HNil]: Aux[L, HNil] =
      new Keys[L] {
        type Out = HNil
        def apply(): Out = HNil
      }

    implicit def hlistKeys[K, V, T <: HList](implicit wk: Witness.Aux[K], kt: Keys[T]): Aux[FieldType[K, V] :: T, K :: kt.Out] =
      new Keys[FieldType[K, V]:: T] {
        type Out = K :: kt.Out
        def apply(): Out = wk.value :: kt()
      }
  }

  /**
   * Type class supporting collecting the value of a record as an `HList`.
   *
   * @author Miles Sabin
   */
  trait Values[L <: HList] extends DepFn1[L] { type Out <: HList }

  object Values {
    def apply[L <: HList](implicit values: Values[L]): Aux[L, values.Out] = values

    type Aux[L <: HList, Out0 <: HList] = Values[L] { type Out = Out0 }

    implicit def hnilValues[L <: HNil]: Aux[L, HNil] =
      new Values[L] {
        type Out = HNil
        def apply(l: L): Out = HNil
      }

    implicit def hlistValues[K, V, T <: HList](implicit vt: Values[T]): Aux[FieldType[K, V] :: T, V :: vt.Out] =
      new Values[FieldType[K, V]:: T] {
        type Out = V :: vt.Out
        def apply(l: FieldType[K, V] :: T): Out = (l.head: V) :: vt(l.tail)
      }
  }
}
