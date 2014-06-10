/*
 * Copyright (c) 2013-14 Miles Sabin 
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

sealed trait Coproduct

sealed trait :+:[+H, +T <: Coproduct] extends Coproduct

final case class Inl[+H, +T <: Coproduct](head: H) extends :+:[H, T] {
  override def toString = head.toString
}

final case class Inr[+H, +T <: Coproduct](tail: T) extends :+:[H, T] {
  override def toString = tail.toString
}

sealed trait CNil extends Coproduct

object Coproduct {
  import ops.coproduct.Inject
  import syntax.CoproductOps

  class MkCoproduct[C <: Coproduct] {
    def apply[T](t: T)(implicit inj: Inject[C, T]): C = inj(t)
  }

  def apply[C <: Coproduct] = new MkCoproduct[C]

  implicit def cpOps[C <: Coproduct](c: C) = new CoproductOps(c)
}

object union {
  import ops.union.{ Keys, Values }
  import syntax.UnionOps

  implicit def unionOps[C <: Coproduct](u: C): UnionOps[C] = new UnionOps(u)

  trait UnionType {
    type Union <: Coproduct
    type Keys <: HList
    type Values <: Coproduct
  }

  object UnionType {
    type Aux[U, K, V] = UnionType { type Union = U; type Keys = K; type Values = V }

    def apply[U <: Coproduct](implicit keys: Keys[U], values: Values[U]): Aux[U, keys.Out, values.Out] =
      new UnionType {
        type Union = U
        type Keys = keys.Out
        type Values = values.Out
      }

    def like[U <: Coproduct](u: U)(implicit keys: Keys[U], values: Values[U]): Aux[U, keys.Out, values.Out] =
      new UnionType {
        type Union = U
        type Keys = keys.Out
        type Values = values.Out
      }
  }
}
