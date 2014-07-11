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
package directives

trait FormFieldDirectives extends ToNameReceptaclePimps {

  /**
   * Rejects the request if the form field parameter matcher(s) defined by the definition(s) don't match.
   * Otherwise the field content(s) are extracted and passed to the inner route.
   */
  /* directive */ def formField(fdm: FieldDefMagnet): fdm.Out = fdm()

  /**
   * Rejects the request if the form field parameter matcher(s) defined by the definition(s) don't match.
   * Otherwise the field content(s) are extracted and passed to the inner route.
   */
  /* directive */ def formFields(fdm: FieldDefMagnet): fdm.Out = fdm()

}

object FormFieldDirectives extends FormFieldDirectives

trait FieldDefMagnet {
  type Out
  def apply(): Out
}
object FieldDefMagnet {
  implicit def apply[T](value: T)(implicit fdm2: FieldDefMagnet2[T]) = new FieldDefMagnet {
    type Out = fdm2.Out
    def apply() = fdm2(value)
  }
}
