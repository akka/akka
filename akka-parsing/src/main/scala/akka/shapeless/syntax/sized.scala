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

import scala.collection.{ GenTraversable, GenTraversableLike }

object sized {
  implicit def genTraversableSizedConv[CC[X] <: GenTraversable[X], T](cc: CC[T])(implicit conv: CC[T] ⇒ GenTraversableLike[T, CC[T]]) = new SizedConv[T, CC[T]](cc)

  implicit def stringSizedConv(s: String) = new SizedConv[Char, String](s)
}

final class SizedConv[A, Repr <% GenTraversableLike[A, Repr]](r: Repr) {
  import ops.nat._
  import Sized._

  def sized[L <: Nat](implicit toInt: ToInt[L]) =
    if (r.size == toInt()) Some(wrap[Repr, L](r)) else None

  def sized(l: Nat)(implicit toInt: ToInt[l.N]) =
    if (r.size == toInt()) Some(wrap[Repr, l.N](r)) else None

  def ensureSized[L <: Nat](implicit toInt: ToInt[L]) = {
    assert(r.size == toInt())
    wrap[Repr, L](r)
  }
}
