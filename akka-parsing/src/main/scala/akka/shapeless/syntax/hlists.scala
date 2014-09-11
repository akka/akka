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
package syntax

import scala.annotation.tailrec

/**
 * Carrier for `HList` operations.
 *
 * These methods are implemented here and pimped onto the minimal `HList` types to avoid issues that would otherwise be
 * caused by the covariance of `::[H, T]`.
 *
 * @author Miles Sabin
 */
final class HListOps[L <: HList](l: L) {
  import ops.hlist._

  /**
   * Returns the head of this `HList`. Available only if there is evidence that this `HList` is composite.
   */
  def head(implicit c: IsHCons[L]): c.H = c.head(l)

  /**
   * Returns the tail of this `HList`. Available only if there is evidence that this `HList` is composite.
   */
  def tail(implicit c: IsHCons[L]): c.T = c.tail(l)

  /**
   * Prepend the argument element to this `HList`.
   */
  def ::[H](h: H): H :: L = akka.shapeless.::(h, l)

  /**
   * Prepend the argument element to this `HList`.
   */
  def +:[H](h: H): H :: L = akka.shapeless.::(h, l)

  /**
   * Append the argument element to this `HList`.
   */
  def :+[T](t: T)(implicit prepend: Prepend[L, T :: HNil]): prepend.Out = prepend(l, t :: HNil)

  /**
   * Append the argument `HList` to this `HList`.
   */
  def ++[S <: HList](suffix: S)(implicit prepend: Prepend[L, S]): prepend.Out = prepend(l, suffix)

  /**
   * Prepend the argument `HList` to this `HList`.
   */
  def ++:[P <: HList](prefix: P)(implicit prepend: Prepend[P, L]): prepend.Out = prepend(prefix, l)

  /**
   * Prepend the argument `HList` to this `HList`.
   */
  def :::[P <: HList](prefix: P)(implicit prepend: Prepend[P, L]): prepend.Out = prepend(prefix, l)

  /**
   * Prepend the reverse of the argument `HList` to this `HList`.
   */
  //def reverse_:::[P <: HList](prefix: P)(implicit prepend: ReversePrepend[P, L]): prepend.Out = prepend(prefix, l)

  /**
   * Reverses this `HList`.
   */
  def reverse(implicit reverse: Reverse[L]): reverse.Out = reverse(l)
}
