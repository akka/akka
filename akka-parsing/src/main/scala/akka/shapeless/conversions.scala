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

import ops.hlist.Tupler

/**
 * Higher ranked function which converts `HLists` to tuples.
 */
object tupled extends Poly1 {
  implicit def caseHList[L <: HList](implicit tupler: Tupler[L]) = at[L](tupler(_))
}

/**
 * Higher ranked function which converts products to `HLists`.
 */
object productElements extends Poly1 {
  implicit def caseProduct[P](implicit gen: Generic[P]) = at[P](p ⇒ gen.to(p))
}
