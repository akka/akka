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
package syntax

/**
 * Record operations on `HList`'s with field-like elements.
 *
 * @author Miles Sabin
 */
final class RecordOps[L <: HList](l: L) {
  import record._
  import ops.record._

  /**
   * Returns the value associated with the singleton typed key k. Only available if this record has a field with
   * with keyType equal to the singleton type k.T.
   */
  def get(k: Witness)(implicit selector: Selector[L, k.T]): selector.Out = selector(l)

  /**
   * Returns the value associated with the singleton typed key k. Only available if this record has a field with
   * with keyType equal to the singleton type k.T.
   *
   * Note that this can creates a bogus ambiguity with `HListOps#apply` as described in
   * https://issues.scala-lang.org/browse/SI-5142. If this method is accessible the conflict can be worked around by
   * using HListOps#at instead of `HListOps#apply`.
   */
  def apply(k: Witness)(implicit selector: Selector[L, k.T]): selector.Out = selector(l)

  /**
   * Updates or adds to this record a field with key type F and value type F#valueType.
   */
  def updated[V](k: Witness, v: V)(implicit updater: Updater[L, FieldType[k.T, V]]): updater.Out = updater(l, field[k.T](v))

  /**
   * Updates a field having a value with type A by given function.
   */
  def updateWith[W](k: WitnessWith[FSL])(f: k.Out ⇒ W)(implicit modifier: Modifier[L, k.T, k.Out, W]): modifier.Out = modifier(l, f)
  type FSL[K] = Selector[L, K]

  /**
   * Remove the field associated with the singleton typed key k, returning both the corresponding value and the updated
   * record. Only available if this record has a field with keyType equal to the singleton type k.T.
   */
  def remove(k: Witness)(implicit remover: Remover[L, k.T]): remover.Out = remover(l)

  /**
   * Updates or adds to this record a field of type F.
   */
  def +[F](f: F)(implicit updater: Updater[L, F]): updater.Out = updater(l, f)

  /**
   * Remove the field associated with the singleton typed key k, returning the updated record. Only available if this
   * record has a field with keyType equal to the singleton type k.T.
   */
  def -[V, Out <: HList](k: Witness)(implicit remover: Remover.Aux[L, k.T, (V, Out)]): Out = remover(l)._2

  /**
   * Rename the field associated with the singleton typed key oldKey. Only available if this
   * record has a field with keyType equal to the singleton type oldKey.T.
   */
  def renameField(oldKey: Witness, newKey: Witness)(implicit renamer: Renamer[L, oldKey.T, newKey.T]): renamer.Out = renamer(l)

  /**
   * Returns the keys of this record as an HList of singleton typed values.
   */
  def keys(implicit keys: Keys[L]): keys.Out = keys()

  /**
   * Returns an HList of the values of this record.
   */
  def values(implicit values: Values[L]): values.Out = values(l)
}
