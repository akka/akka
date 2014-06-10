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
package ops

object function {
  /**
   * Type class supporting conversion of arbitrary functions to functions of a single `HList`
   * argument.
   *
   * @author Miles Sabin
   */
  trait FnToProduct[F] extends DepFn1[F]

  object FnToProduct extends FnToProductInstances {
    def apply[F](implicit fntop: FnToProduct[F]): Aux[F, fntop.Out] = fntop
  }

  /**
   * Type class supporting conversion of functions of a single `HList` argument to ordinary functions.
   *
   * @author Miles Sabin
   */
  trait FnFromProduct[F] extends DepFn1[F]

  object FnFromProduct extends FnFromProductInstances {
    def apply[F](implicit fnfromp: FnFromProduct[F]): Aux[F, fnfromp.Out] = fnfromp
  }
}
