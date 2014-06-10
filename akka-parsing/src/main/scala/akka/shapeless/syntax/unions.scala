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
 * Discriminated union operations on `Coproducts`'s with field-like elements.
 *
 * @author Miles Sabin
 */
final class UnionOps[C <: Coproduct](c: C) {
  import union._
  import ops.union._

  /**
   * Returns the value associated with the singleton typed key k. Only available if this union has a field with
   * with keyType equal to the singleton type k.T.
   */
  def get(k: Witness)(implicit selector: Selector[C, k.T]): selector.Out = selector(c)

  /**
   * Returns the value associated with the singleton typed key k. Only available if this union has a field with
   * with keyType equal to the singleton type k.T.
   *
   * Note that this can creates a bogus ambiguity with `CoproductOps#apply` as described in
   * https://issues.scala-lang.org/browse/SI-5142. If this method is accessible the conflict can be worked around by
   * using CoproductOps#at instead of `CoproductOps#apply`.
   */
  def apply(k: Witness)(implicit selector: Selector[C, k.T]): selector.Out = selector(c)
}

