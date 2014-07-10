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

/**
 * Provides a way to convert a value into an HList.
 * If the value is already an HList then it is returned unchanged, otherwise it's wrapped into a single-element HList.
 */
trait HListable[T] {
  type Out <: HList
  def apply(value: T): Out
}

object HListable extends LowerPriorityHListable {
  implicit def fromHList[T <: HList] = new HListable[T] {
    type Out = T
    def apply(value: T) = value
  }
}

private[routing] abstract class LowerPriorityHListable {
  implicit def fromAnyRef[T] = new HListable[T] {
    type Out = T :: HNil
    def apply(value: T) = value :: HNil
  }
}