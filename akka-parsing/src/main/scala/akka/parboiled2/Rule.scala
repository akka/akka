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

import scala.annotation.unchecked.uncheckedVariance
import scala.reflect.internal.annotations.compileTimeOnly
import scala.collection.immutable
import akka.parboiled2.support._
import akka.shapeless.HList

sealed trait RuleX

/**
 * The general model of a parser rule.
 * It is characterized by consuming a certain number of elements from the value stack (whose types are captured by the
 * HList type parameter `I` for "Input") and itself pushing a certain number of elements onto the value stack (whose
 * types are captured by the HList type parameter `O` for "Output").
 *
 * At runtime there are only two instances of this class which signal whether the rule has matched (or mismatched)
 * at the current point in the input.
 */
sealed class Rule[-I <: HList, +O <: HList] extends RuleX {
  // Note: we could model `Rule` as a value class, however, tests have shown that this doesn't result in any measurable
  // performance benefit and, in addition, comes with other drawbacks (like generated bridge methods)

  /**
   * Concatenates this rule with the given other one.
   * The resulting rule type is computed on a type-level.
   * Here is an illustration (using an abbreviated HList notation):
   *   Rule[, A] ~ Rule[, B] = Rule[, A:B]
   *   Rule[A:B:C, D:E:F] ~ Rule[F, G:H] = Rule[A:B:C, D:E:G:H]
   *   Rule[A, B:C] ~ Rule[D:B:C, E:F] = Rule[D:A, E:F]
   */
  @compileTimeOnly("Calls to `~` must be inside `rule` macro")
  def ~[I2 <: HList, O2 <: HList](that: Rule[I2, O2])(implicit
    i: TailSwitch[I2, O @uncheckedVariance, I @uncheckedVariance],
                                                      o: TailSwitch[O @uncheckedVariance, I2, O2]): Rule[i.Out, o.Out] = `n/a`

  /**
   * Same as `~` but with "cut" semantics, meaning that the parser will never backtrack across this boundary.
   * If the rule being concatenated doesn't match a parse error will be triggered immediately.
   */
  @compileTimeOnly("Calls to `~!~` must be inside `rule` macro")
  def ~!~[I2 <: HList, O2 <: HList](that: Rule[I2, O2])(implicit
    i: TailSwitch[I2, O @uncheckedVariance, I @uncheckedVariance],
                                                        o: TailSwitch[O @uncheckedVariance, I2, O2]): Rule[i.Out, o.Out] = `n/a`

  /**
   * Combines this rule with the given other one in a way that the resulting rule matches if this rule matches
   * or the other one matches. If this rule doesn't match the parser is reset and the given alternative tried.
   * This operators therefore implements the "ordered choice' PEG combinator.
   */
  @compileTimeOnly("Calls to `|` must be inside `rule` macro")
  def |[I2 <: I, O2 >: O <: HList](that: Rule[I2, O2]): Rule[I2, O2] = `n/a`

  /**
   * Creates a "negative syntactic predicate", i.e. a rule that matches only if this rule mismatches and vice versa.
   * The resulting rule doesn't cause the parser to make any progress (i.e. match any input) and also clears out all
   * effects that the underlying rule might have had on the value stack.
   */
  @compileTimeOnly("Calls to `unary_!` must be inside `rule` macro")
  def unary_!(): Rule0 = `n/a`

  /**
   * Attaches the given explicit name to this rule.
   */
  @compileTimeOnly("Calls to `named` must be inside `rule` macro")
  def named(name: String): this.type = `n/a`

  /**
   * Postfix shortcut for `optional`.
   */
  @compileTimeOnly("Calls to `.?` must be inside `rule` macro")
  def ?(implicit l: Lifter[Option, I @uncheckedVariance, O @uncheckedVariance]): Rule[l.In, l.OptionalOut] = `n/a`

  /**
   * Postfix shortcut for `zeroOrMore`.
   */
  @compileTimeOnly("Calls to `.*` must be inside `rule` macro")
  def *(implicit l: Lifter[immutable.Seq, I @uncheckedVariance, O @uncheckedVariance]): Rule[l.In, l.OptionalOut] with Repeated = `n/a`

  /**
   * Postfix shortcut for `zeroOrMore(...).separatedBy(...)`.
   */
  @compileTimeOnly("Calls to `.*` must be inside `rule` macro")
  def *(separator: Rule0)(implicit l: Lifter[immutable.Seq, I @uncheckedVariance, O @uncheckedVariance]): Rule[l.In, l.OptionalOut] = `n/a`

  /**
   * Postfix shortcut for `oneOrMore`.
   */
  @compileTimeOnly("Calls to `.+` must be inside `rule` macro")
  def +(implicit l: Lifter[immutable.Seq, I @uncheckedVariance, O @uncheckedVariance]): Rule[l.In, l.StrictOut] with Repeated = `n/a`

  /**
   * Postfix shortcut for `oneOrMore(...).separatedBy(...)`.
   */
  @compileTimeOnly("Calls to `.+` must be inside `rule` macro")
  def +(separator: Rule0)(implicit l: Lifter[immutable.Seq, I @uncheckedVariance, O @uncheckedVariance]): Rule[l.In, l.StrictOut] = `n/a`
}

/**
 * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
 */
object Rule extends Rule0 {
  /**
   * THIS IS NOT PUBLIC API and might become hidden in future. Use only if you know what you are doing!
   */
  implicit class Runnable[L <: HList](rule: RuleN[L]) {
    def run()(implicit scheme: Parser.DeliveryScheme[L]): scheme.Result = macro ParserMacros.runImpl[L]
  }
}

abstract class RuleDSL
  extends RuleDSLBasics
  with RuleDSLCombinators
  with RuleDSLActions

// phantom type for WithSeparatedBy pimp
trait Repeated