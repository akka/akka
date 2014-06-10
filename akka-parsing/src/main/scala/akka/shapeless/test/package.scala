/*
 * Copyright (c) 2014 Miles Sabin 
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

import scala.reflect.runtime.universe._

package object test {
  def typed[T](t: ⇒ T) {}

  def sameTyped[T](t1: ⇒ T)(t2: ⇒ T) {}

  def showType[T: TypeTag]: String = typeOf[T].normalize.toString

  def showType[T: TypeTag](t: ⇒ T): String = typeOf[T].normalize.toString
}
