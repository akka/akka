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

/**
 * Record operations on `HList`'s with field-like elements.
 *
 * @author Miles Sabin
 */
object record {
  import ops.hlist.Union
  import ops.record.{ Keys, Values }
  import syntax.RecordOps

  implicit def recordOps[L <: HList](l: L): RecordOps[L] = new RecordOps(l)

  /**
   * The type of fields with keys of singleton type `K` and value type `V`.
   */
  type FieldType[K, V] = V with KeyTag[K, V]
  trait KeyTag[K, V]

  /**
   * Yields a result encoding the supplied value with the singleton type `K' of its key.
   */
  def field[K] = new FieldBuilder[K]

  class FieldBuilder[K] {
    def apply[V](v: V): FieldType[K, V] = v.asInstanceOf[FieldType[K, V]]
  }

  /**
   * Utility trait intended for inferring a record type from a sample value and unpacking it into its
   * key and value types.
   */
  trait RecordType {
    type Record <: HList
    type Union <: Coproduct
    type Keys <: HList
    type Values <: HList
  }

  object RecordType {
    type Aux[L <: HList, C <: Coproduct, K, V] = RecordType {
      type Record = L; type Union = C; type Keys = K; type Values = V
    }

    def apply[L <: HList](implicit union: Union[L], keys: Keys[L], values: Values[L]): Aux[L, union.Out, keys.Out, values.Out] =
      new RecordType {
        type Record = L
        type Union = union.Out
        type Keys = keys.Out
        type Values = values.Out
      }

    def like[L <: HList](l: ⇒ L)(implicit union: Union[L], keys: Keys[L], values: Values[L]): Aux[L, union.Out, keys.Out, values.Out] =
      new RecordType {
        type Record = L
        type Union = union.Out
        type Keys = keys.Out
        type Values = values.Out
      }
  }
}

/**
 * Polymorphic function that allows modifications on record fields while preserving the
 * original key types.
 *
 * @author Dario Rexin
 */
trait FieldPoly extends Poly1 {
  import record._

  class FieldCaseBuilder[A, T] {
    def apply[Res](fn: A ⇒ Res) = new Case[FieldType[T, A]] {
      type Result = FieldType[T, Res]
      val value: Function1[A :: HNil, FieldType[T, Res]] =
        (l: A :: HNil) ⇒ field[T](fn(l.head))
    }
  }

  def atField[A](w: Witness) = new FieldCaseBuilder[A, w.T]
}

/**
 * Field with values of type `V`.
 *
 * Record keys of this form should be objects which extend this trait. Keys may also be arbitrary singleton typed
 * values, however keys of this form enforce the type of their values.
 *
 * @author Miles Sabin
 */
trait FieldOf[V] {
  import record._

  def ->>(v: V): FieldType[this.type, V] = field[this.type](v)
}
