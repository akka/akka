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

import scala.collection.{ GenTraversable, GenTraversableLike }

object traversable {
  /**
   * Type class supporting type safe conversion of `Traversables` to `HLists`.
   *
   * @author Miles Sabin
   */
  trait FromTraversable[Out <: HList] {
    def apply(l: GenTraversable[_]): Option[Out]
  }

  /**
   * `FromTraversable` type class instances.
   *
   * @author Miles Sabin
   */
  object FromTraversable {
    def apply[Out <: HList](implicit from: FromTraversable[Out]) = from

    import syntax.typeable._

    implicit def hnilFromTraversable[T] = new FromTraversable[HNil] {
      def apply(l: GenTraversable[_]) =
        if (l.isEmpty) Some(HNil) else None
    }

    implicit def hlistFromTraversable[OutH, OutT <: HList](implicit flt: FromTraversable[OutT], oc: Typeable[OutH]) = new FromTraversable[OutH :: OutT] {
      def apply(l: GenTraversable[_]): Option[OutH :: OutT] =
        if (l.isEmpty) None
        else for (h ← l.head.cast[OutH]; t ← flt(l.tail)) yield h :: t
    }
  }
}
