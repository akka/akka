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

import scala.language.experimental.macros

object singleton {
  implicit def mkSingletonOps(t: Any): SingletonOps = macro SingletonTypeMacros.mkSingletonOps

  import tag._
  implicit def narrowSymbol[S <: String](t: Symbol): Symbol @@ S = macro SingletonTypeMacros.narrowSymbol[S]
}

trait SingletonOps {
  import record._

  type T

  /**
   * Returns a Witness of the singleton type of this value.
   */
  val witness: Witness.Aux[T]

  /**
   * Narrows this value to its singleton type.
   */
  def narrow: T {} = witness.value

  /**
   * Returns the provided value tagged with the singleton type of this value as its key in a record-like structure.
   */
  def ->>[V](v: V): FieldType[T, V] = field[T](v)
}
