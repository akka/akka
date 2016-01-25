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
import scala.collection.immutable
import akka.parboiled2.support._
import akka.shapeless._

trait RuleDSLCombinators {

  /**
   * Runs its inner rule and succeeds even if the inner rule doesn't.
   * Resulting rule type is
   *   Rule0             if r == Rule0
   *   Rule1[Option[T]]  if r == Rule1[T]
   *   Rule[I, O]        if r == Rule[I, O <: I] // so called "reduction", which leaves the value stack unchanged on a type level
   */
  @compileTimeOnly("Calls to `optional` must be inside `rule` macro")
  def optional[I <: HList, O <: HList](r: Rule[I, O])(implicit l: Lifter[Option, I, O]): Rule[l.In, l.OptionalOut] = `n/a`

  /**
   * Runs its inner rule until it fails, always succeeds.
   * Resulting rule type is
   *   Rule0          if r == Rule0
   *   Rule1[Seq[T]]  if r == Rule1[T]
   *   Rule[I, O]     if r == Rule[I, O <: I] // so called "reduction", which leaves the value stack unchanged on a type level
   */
  @compileTimeOnly("Calls to `zeroOrMore` must be inside `rule` macro")
  def zeroOrMore[I <: HList, O <: HList](r: Rule[I, O])(implicit l: Lifter[immutable.Seq, I, O]): Rule[l.In, l.OptionalOut] with Repeated = `n/a`

  /**
   * Runs its inner rule until it fails, succeeds if its inner rule succeeded at least once.
   * Resulting rule type is
   *   Rule0          if r == Rule0
   *   Rule1[Seq[T]]  if r == Rule1[T]
   *   Rule[I, O]     if r == Rule[I, O <: I] // so called "reduction", which leaves the value stack unchanged on a type level
   */
  @compileTimeOnly("Calls to `oneOrMore` must be inside `rule` macro")
  def oneOrMore[I <: HList, O <: HList](r: Rule[I, O])(implicit l: Lifter[immutable.Seq, I, O]): Rule[l.In, l.StrictOut] with Repeated = `n/a`

  /**
   * Runs its inner rule but resets the parser (cursor and value stack) afterwards,
   * succeeds only if its inner rule succeeded.
   */
  @compileTimeOnly("Calls to `&` must be inside `rule` macro")
  def &(r: Rule[_, _]): Rule0 = `n/a`

  /**
   * Marks a rule as "undividable" from an error reporting perspective.
   * The parser will never report errors *inside* of the marked rule.
   * Rather, if the rule mismatches, the error will be reported at the
   * very beginning of the attempted rule match.
   */
  @compileTimeOnly("Calls to `atomic` must be inside `rule` macro")
  def atomic[I <: HList, O <: HList](r: Rule[I, O]): Rule[I, O] = `n/a`

  /**
   * Marks a rule as "quiet" from an error reporting perspective.
   * Quiet rules only show up in error rule traces if no "unquiet" rules match up to the error location.
   * This marker frequently used for low-level syntax rules (like whitespace or comments) that might be matched
   * essentially everywhere and are therefore not helpful when appearing in the "expected" set of an error report.
   */
  @compileTimeOnly("Calls to `atomic` must be inside `rule` macro")
  def quiet[I <: HList, O <: HList](r: Rule[I, O]): Rule[I, O] = `n/a`

  /**
   * Allows creation of a sub parser and running of one of its rules as part of the current parsing process.
   * The subparser will start parsing at the current input position and the outer parser (this parser)
   * will continue where the sub-parser stopped.
   */
  @compileTimeOnly("Calls to `runSubParser` must be inside `rule` macro")
  def runSubParser[I <: HList, O <: HList](f: ParserInput ⇒ Rule[I, O]): Rule[I, O] = `n/a`

  @compileTimeOnly("Calls to `int2NTimes` must be inside `rule` macro")
  implicit def int2NTimes(i: Int): NTimes = `n/a`
  @compileTimeOnly("Calls to `range2NTimes` must be inside `rule` macro")
  implicit def range2NTimes(range: Range): NTimes = `n/a`
  sealed trait NTimes {
    /**
     * Repeats the given sub rule `r` the given number of times.
     * Both bounds of the range must be positive and the upper bound must be >= the lower bound.
     * If the upper bound is zero the rule is equivalent to `MATCH`.
     *
     * Resulting rule type is
     *   Rule0          if r == Rule0
     *   Rule1[Seq[T]]  if r == Rule1[T]
     *   Rule[I, O]     if r == Rule[I, O <: I] // so called "reduction", which leaves the value stack unchanged on a type level
     */
    @compileTimeOnly("Calls to `times` must be inside `rule` macro")
    def times[I <: HList, O <: HList](r: Rule[I, O])(implicit s: Lifter[immutable.Seq, I, O]): Rule[s.In, s.StrictOut] with Repeated
  }

  @compileTimeOnly("Calls to `rule2WithSeparatedBy` constructor must be inside `rule` macro")
  implicit def rule2WithSeparatedBy[I <: HList, O <: HList](r: Rule[I, O] with Repeated): WithSeparatedBy[I, O] = `n/a`
  trait WithSeparatedBy[I <: HList, O <: HList] {
    def separatedBy(separator: Rule0): Rule[I, O] = `n/a`
  }
}