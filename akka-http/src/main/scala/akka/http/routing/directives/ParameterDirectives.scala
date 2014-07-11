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

import java.lang.IllegalStateException

trait ParameterDirectives extends ToNameReceptaclePimps {

  /**
   * Extracts the requests query parameters as a Map[String, String].
   */
  def parameterMap: Directive1[Map[String, String]] = ParameterDirectives._parameterMap

  /**
   * Extracts the requests query parameters as a Map[String, List[String]].
   */
  def parameterMultiMap: Directive1[Map[String, List[String]]] = ParameterDirectives._parameterMultiMap

  /**
   * Extracts the requests query parameters as a Seq[(String, String)].
   */
  def parameterSeq: Directive1[Seq[(String, String)]] = ParameterDirectives._parameterSeq

  /**
   * Rejects the request if the query parameter matcher(s) defined by the definition(s) don't match.
   * Otherwise the parameter value(s) are extracted and passed to the inner route.
   */
  /* directive */ def parameter(pdm: ParamDefMagnet): pdm.Out = pdm()

  /**
   * Rejects the request if the query parameter matcher(s) defined by the definition(s) don't match.
   * Otherwise the parameter value(s) are extracted and passed to the inner route.
   */
  /* directive */ def parameters(pdm: ParamDefMagnet): pdm.Out = pdm()

}

object ParameterDirectives extends ParameterDirectives {
  import BasicDirectives._

  private val _parameterMap: Directive1[Map[String, String]] =
    extract(_.request.uri.query.toMap)

  private val _parameterMultiMap: Directive1[Map[String, List[String]]] =
    extract(_.request.uri.query.toMultiMap)

  private val _parameterSeq: Directive1[Seq[(String, String)]] =
    extract(_.request.uri.query.toSeq)
}

trait ParamDefMagnet {
  type Out
  def apply(): Out
}
object ParamDefMagnet {
  implicit def apply[T](value: T)(implicit pdm2: ParamDefMagnet2[T]) = new ParamDefMagnet {
    type Out = pdm2.Out
    def apply() = pdm2(value)
  }
}
