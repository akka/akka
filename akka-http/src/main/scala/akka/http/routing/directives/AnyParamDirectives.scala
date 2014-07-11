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

trait AnyParamDirectives {
  /**
   * Extracts a parameter either from a form field or from query parameters (in that order), and passes the value(s)
   * to the inner route.
   *
   * Rejects the request if both form field and query parameter matcher(s) defined by the definition(s) don't match.
   */
  /* directive */ def anyParam(apdm: AnyParamDefMagnet): apdm.Out = apdm()

  /**
   * Extracts a parameter either from a form field or from query parameters (in that order), and passes the value(s)
   * to the inner route.
   *
   * Rejects the request if both form field and query parameter matcher(s) defined by the definition(s) don't match.
   */
  /* directive */ def anyParams(apdm: AnyParamDefMagnet): apdm.Out = apdm()
}

object AnyParamDirectives extends AnyParamDirectives

trait AnyParamDefMagnet {
  type Out
  def apply(): Out
}

object AnyParamDefMagnet {
  private type APDM2Tuple1[T] = AnyParamDefMagnet2[Tuple1[T]]

  private def apply[T](value: T)(implicit apdm2Tuple1: APDM2Tuple1[T]) =
    new AnyParamDefMagnet {
      type Out = apdm2Tuple1.Out
      def apply() = apdm2Tuple1(Tuple1(value))
    }

  implicit def forString[T <: String](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)
  implicit def forSymbol[T <: Symbol](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)
  implicit def forNR[T <: NameReceptacle[_]](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)
  implicit def forNDesR[T <: NameDeserializerReceptacle[_]](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)
  implicit def forNDefR[T <: NameDefaultReceptacle[_]](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)
  implicit def forNDesDefR[T <: NameDeserializerDefaultReceptacle[_]](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)

  implicit def forRVR[T <: RequiredValueReceptacle[_]](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)
  implicit def forRVDR[T <: RequiredValueDeserializerReceptacle[_]](value: T)(implicit apdm2: APDM2Tuple1[T]) = apply(value)

  implicit def forTuple[T <: Product](value: T)(implicit apdm21: AnyParamDefMagnet2[T]) =
    new AnyParamDefMagnet {
      type Out = apdm21.Out
      def apply() = apdm21(value)
    }
}
