/*
 * Copyright (c) 2011 Miles Sabin 
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

import ops.hlist.Selector

/**
 * Type class witnessing that every element of `L` has `TC` as its outer type constructor.
 */
trait UnaryTCConstraint[L <: HList, TC[_]]

object UnaryTCConstraint {
  type *->*[TC[_]] = {
    type λ[L <: HList] = UnaryTCConstraint[L, TC]
  }

  implicit def hnilUnaryTC[TC[_]] = new UnaryTCConstraint[HNil, TC] {}
  implicit def hlistUnaryTC1[H, T <: HList, TC[_]](implicit utct: UnaryTCConstraint[T, TC]) =
    new UnaryTCConstraint[TC[H]:: T, TC] {}

  implicit def hlistUnaryTC2[L <: HList] = new UnaryTCConstraint[L, Id] {}

  implicit def hlistUnaryTC3[H] = new UnaryTCConstraint[HNil, Const[H]#λ] {}
  implicit def hlistUnaryTC4[H, T <: HList](implicit utct: UnaryTCConstraint[T, Const[H]#λ]) =
    new UnaryTCConstraint[H :: T, Const[H]#λ] {}
}

/**
 * Type class witnessing that every element of `L` is an element of `M`.
 */
trait BasisConstraint[L <: HList, M <: HList]

object BasisConstraint {
  type Basis[M <: HList] = {
    type λ[L <: HList] = BasisConstraint[L, M]
  }

  implicit def hnilBasis[M <: HList] = new BasisConstraint[HNil, M] {}
  implicit def hlistBasis[H, T <: HList, M <: HList](implicit bct: BasisConstraint[T, M], sel: Selector[M, H]) =
    new BasisConstraint[H :: T, M] {}
}

/**
 * Type class witnessing that every element of `L` is a subtype of `B`.
 */
trait LUBConstraint[L <: HList, B]

object LUBConstraint {
  type <<:[B] = {
    type λ[L <: HList] = LUBConstraint[L, B]
  }

  implicit def hnilLUB[T] = new LUBConstraint[HNil, T] {}
  implicit def hlistLUB[H, T <: HList, B](implicit bct: LUBConstraint[T, B], ev: H <:< B) =
    new LUBConstraint[H :: T, B] {}
}

/**
 * Type class witnessing that every element of `L` is of the form `FieldType[K, V]` where `K` is an element of `M`.
 */
trait KeyConstraint[L <: HList, M <: HList]

object KeyConstraint {
  import record._

  type Keys[M <: HList] = {
    type λ[L <: HList] = KeyConstraint[L, M]
  }

  implicit def hnilKeys[M <: HList] = new KeyConstraint[HNil, M] {}
  implicit def hlistKeys[K, V, T <: HList, M <: HList](implicit bct: KeyConstraint[T, M], sel: Selector[M, K]) = new KeyConstraint[FieldType[K, V]:: T, M] {}
}

/**
 * Type class witnessing that every element of `L` is of the form `FieldType[K, V]` where `V` is an element of `M`.
 */
trait ValueConstraint[L <: HList, M <: HList]

object ValueConstraint {
  import record._

  type Values[M <: HList] = {
    type λ[L <: HList] = ValueConstraint[L, M]
  }

  implicit def hnilValues[M <: HList] = new ValueConstraint[HNil, M] {}
  implicit def hlistValues[K, V, T <: HList, M <: HList](implicit bct: ValueConstraint[T, M], sel: Selector[M, V]) = new ValueConstraint[FieldType[K, V]:: T, M] {}
}
