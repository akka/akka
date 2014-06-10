/*
 * Copyright (c) 2013 Miles Sabin 
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
 * Carrier for `Coproduct` operations.
 *
 * These methods are implemented here and extended onto the minimal `Coproduct` types to avoid issues that would
 * otherwise be caused by the covariance of `:+:[H, T]`.
 *
 * @author Miles Sabin
 */
final class CoproductOps[C <: Coproduct](c: C) {
  import ops.coproduct._

  def map(f: Poly)(implicit mapper: Mapper[f.type, C]): mapper.Out = mapper(c)

  def select[T](implicit selector: Selector[C, T]): Option[T] = selector(c)

  def unify(implicit unifier: Unifier[C]): unifier.Out = unifier(c)

  def zipWithKeys[K <: HList](keys: K)(implicit zipWithKeys: ZipWithKeys[K, C]): zipWithKeys.Out = zipWithKeys(keys, c)
}
