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

package akka.parboiled2

import scala.reflect.internal.annotations.compileTimeOnly
import akka.parboiled2.support._
import akka.shapeless.HList

trait RuleDSLBasics {

  /**
   * Matches the given single character.
   */
  @compileTimeOnly("Calls to `ch` must be inside `rule` macro")
  implicit def ch(c: Char): Rule0 = `n/a`

  /**
   * Matches the given string of characters.
   */
  @compileTimeOnly("Calls to `str` must be inside `rule` macro")
  implicit def str(s: String): Rule0 = `n/a`

  /**
   * Matches any (single) character matched by the given `CharPredicate`.
   */
  @compileTimeOnly("Calls to `predicate` must be inside `rule` macro")
  implicit def predicate(p: CharPredicate): Rule0 = `n/a`

  /**
   * Matches any of the given maps keys and pushes the respective value upon
   * a successful match.
   */
  @compileTimeOnly("Calls to `valueMap` must be inside `rule` macro")
  implicit def valueMap[T](m: Map[String, T])(implicit h: HListable[T]): RuleN[h.Out] = `n/a`

  /**
   * Matches any single one of the given characters.
   *
   * Note: This helper has O(n) runtime with n being the length of the given string.
   * If your string consists only of 7-bit ASCII chars using a pre-allocated
   * [[CharPredicate]] will be more efficient.
   */
  @compileTimeOnly("Calls to `anyOf` must be inside `rule` macro")
  def anyOf(chars: String): Rule0 = `n/a`

  /**
   * Matches any single character except the ones in the given string and except EOI.
   *
   * Note: This helper has O(n) runtime with n being the length of the given string.
   * If your string consists only of 7-bit ASCII chars using a pre-allocated
   * [[CharPredicate]] will be more efficient.
   */
  @compileTimeOnly("Calls to `noneOf` must be inside `rule` macro")
  def noneOf(chars: String): Rule0 = `n/a`

  /**
   * Matches the given single character case insensitively.
   * Note: the given character must be specified in lower-case!
   * This requirement is currently NOT enforced!
   */
  @compileTimeOnly("Calls to `ignoreCase` must be inside `rule` macro")
  def ignoreCase(c: Char): Rule0 = `n/a`

  /**
   * Matches the given string of characters case insensitively.
   * Note: the given string must be specified in all lower-case!
   * This requirement is currently NOT enforced!
   */
  @compileTimeOnly("Calls to `ignoreCase` must be inside `rule` macro")
  def ignoreCase(s: String): Rule0 = `n/a`

  /**
   * Matches any character except EOI.
   */
  @compileTimeOnly("Calls to `ANY` must be inside `rule` macro")
  def ANY: Rule0 = `n/a`

  /**
   * Matches the EOI (end-of-input) character.
   */
  def EOI: Char = akka.parboiled2.EOI

  /**
   * Matches no character (i.e. doesn't cause the parser to make any progress) but succeeds always (as a rule).
   */
  def MATCH: Rule0 = Rule

  /**
   * A Rule0 that always fails.
   */
  def MISMATCH0: Rule0 = MISMATCH

  /**
   * A generic Rule that always fails.
   */
  def MISMATCH[I <: HList, O <: HList]: Rule[I, O] = null

  /**
   * A rule that always fails and causes the parser to immediately terminate the parsing run.
   * The resulting parse error only has a single trace with a single frame which holds the given error message.
   */
  def fail(expected: String): Rule0 = `n/a`

  /**
   * Fully generic variant of [[fail]].
   */
  def failX[I <: HList, O <: HList](expected: String): Rule[I, O] = `n/a`

  @compileTimeOnly("Calls to `str2CharRangeSupport` must be inside `rule` macro")
  implicit def str2CharRangeSupport(s: String): CharRangeSupport = `n/a`
  sealed trait CharRangeSupport {
    def -(other: String): Rule0
  }
}
