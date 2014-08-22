/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing

import akka.shapeless._
import ops.hlist.Prepend

// TODO: check whether we can remove this additional layer on top of PrependAux
trait Prepender[P <: HList, S <: HList] {
  type Out <: HList
  def apply(prefix: P, suffix: S): Out
}

object Prepender {
  implicit def hnilPrepend[P <: HList, S <: HNil] = new Prepender[P, S] {
    type Out = P
    def apply(prefix: P, suffix: S) = prefix
  }

  implicit def apply[P <: HList, S <: HList, Out0 <: HList](implicit prepend: Prepend.Aux[P, S, Out0]) =
    new Prepender[P, S] {
      type Out = Out0
      def apply(prefix: P, suffix: S): Out = prepend(prefix, suffix)
    }
}