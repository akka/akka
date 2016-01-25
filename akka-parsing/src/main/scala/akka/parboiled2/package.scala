/*
 * Copyright (C) 2009-2016 Mathias Doenitz, Alexander Myltsev
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

package akka

import akka.shapeless._
import java.nio.charset.Charset

package object parboiled2 {

  type Rule0 = RuleN[HNil]
  type Rule1[+T] = RuleN[T :: HNil]
  type Rule2[+A, +B] = RuleN[A :: B :: HNil]
  type RuleN[+L <: HList] = Rule[HNil, L]
  type PopRule[-L <: HList] = Rule[L, HNil]

  val EOI = '\uFFFF'

  val UTF8 = Charset.forName("UTF-8")
  val `ISO-8859-1` = Charset.forName("ISO-8859-1")

  val EmptyArray = Array.empty[Any]
}
