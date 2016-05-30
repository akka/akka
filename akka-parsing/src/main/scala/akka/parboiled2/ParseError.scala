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

import scala.annotation.tailrec
import scala.collection.immutable

case class ParseError(
  position:          Position,
  principalPosition: Position,
  traces:            immutable.Seq[RuleTrace]) extends RuntimeException {
  require(principalPosition.index >= position.index, "principalPosition must be > position")
  def format(parser: Parser): String = format(parser.input)
  def format(parser: Parser, formatter: ErrorFormatter): String = format(parser.input, formatter)
  def format(input: ParserInput): String = format(input, new ErrorFormatter())
  def format(input: ParserInput, formatter: ErrorFormatter): String = formatter.format(this, input)

  override def toString = s"ParseError($position, $principalPosition, <${traces.size} traces>)"

  lazy val effectiveTraces: immutable.Seq[RuleTrace] =
    traces map {
      val commonPrefixLen = RuleTrace.commonNonAtomicPrefixLength(traces)
      if (commonPrefixLen > 0) t ⇒ t.copy(prefix = t.prefix.drop(commonPrefixLen)).dropUnreportedPrefix
      else _.dropUnreportedPrefix
    }
}

/**
 * Defines a position in an [[ParserInput]].
 *
 * @param index index into the input buffer (0-based)
 * @param line the text line the error occurred in (1-based)
 * @param column the text column the error occurred in (1-based)
 */
case class Position(index: Int, line: Int, column: Int)

object Position {
  def apply(index: Int, input: ParserInput): Position = {
    @tailrec def rec(ix: Int, line: Int, col: Int): Position =
      if (ix >= index) Position(index, line, col)
      else if (ix >= input.length || input.charAt(ix) != '\n') rec(ix + 1, line, col + 1)
      else rec(ix + 1, line + 1, 1)
    rec(ix = 0, line = 1, col = 1)
  }
}

case class RuleTrace(prefix: List[RuleTrace.NonTerminal], terminal: RuleTrace.Terminal) {
  import RuleTrace._

  /**
   * Returns a RuleTrace starting with the first [[RuleTrace.Atomic]] element or the first sub-trace whose
   * offset from the reported error index is zero (e.g. the [[RuleTrace.Terminal]]).
   * If this is wrapped in one or more [[RuleTrace.NonTerminal.Named]] the outermost of these is returned instead.
   */
  def dropUnreportedPrefix: RuleTrace = {
    @tailrec def rec(current: List[NonTerminal], named: List[NonTerminal]): List[NonTerminal] =
      current match {
        case NonTerminal(Named(_), _) :: tail ⇒ rec(tail, if (named.isEmpty) current else named)
        case NonTerminal(RuleCall, _) :: tail ⇒ rec(tail, named) // RuleCall elements allow the name to be carried over
        case NonTerminal(Atomic, _) :: tail   ⇒ if (named.isEmpty) tail else named
        case x :: tail                        ⇒ if (x.offset >= 0 && named.nonEmpty) named else rec(tail, Nil)
        case Nil                              ⇒ named
      }
    val newPrefix = rec(prefix, Nil)
    if (newPrefix ne prefix) copy(prefix = newPrefix) else this
  }

  /**
   * Wraps this trace with a [[RuleTrace.Named]] wrapper if the given name is non-empty.
   */
  def named(name: String): RuleTrace = {
    val newHead = NonTerminal(Named(name), if (prefix.isEmpty) 0 else prefix.head.offset)
    if (name.isEmpty) this else copy(prefix = newHead :: prefix)
  }
}

object RuleTrace {

  def commonNonAtomicPrefixLength(traces: Seq[RuleTrace]): Int =
    if (traces.size > 1) {
      val tracesTail = traces.tail
      def hasElem(ix: Int, elem: NonTerminal): RuleTrace ⇒ Boolean =
        _.prefix.drop(ix) match {
          case `elem` :: _ ⇒ true
          case _           ⇒ false
        }
      @tailrec def rec(current: List[NonTerminal], namedIx: Int, ix: Int): Int =
        current match {
          case head :: tail if tracesTail forall hasElem(ix, head) ⇒
            head.key match {
              case Named(_) ⇒ rec(tail, if (namedIx >= 0) namedIx else ix, ix + 1)
              case RuleCall ⇒ rec(tail, namedIx, ix + 1) // RuleCall elements allow the name to be carried over
              case Atomic   ⇒ if (namedIx >= 0) namedIx else ix // Atomic elements always terminate a common prefix
              case _        ⇒ rec(tail, -1, ix + 1) // otherwise the name chain is broken
            }
          case _ ⇒ if (namedIx >= 0) namedIx else ix
        }
      rec(traces.head.prefix, namedIx = -1, ix = 0)
    } else 0

  // offset: the number of characters before the reported error index that the rule corresponding
  // to this trace head started matching.
  final case class NonTerminal(key: NonTerminalKey, offset: Int)
  sealed trait NonTerminalKey
  case object Action extends NonTerminalKey
  case object AndPredicate extends NonTerminalKey
  case object Atomic extends NonTerminalKey
  case object Capture extends NonTerminalKey
  case object Cut extends NonTerminalKey
  case object FirstOf extends NonTerminalKey
  final case class IgnoreCaseString(string: String) extends NonTerminalKey
  final case class MapMatch(map: Map[String, Any]) extends NonTerminalKey
  final case class Named(name: String) extends NonTerminalKey
  case object OneOrMore extends NonTerminalKey
  case object Optional extends NonTerminalKey
  case object Quiet extends NonTerminalKey
  case object RuleCall extends NonTerminalKey
  case object Run extends NonTerminalKey
  case object RunSubParser extends NonTerminalKey
  case object Sequence extends NonTerminalKey
  final case class StringMatch(string: String) extends NonTerminalKey
  final case class Times(min: Int, max: Int) extends NonTerminalKey
  case object ZeroOrMore extends NonTerminalKey

  sealed trait Terminal
  case object ANY extends Terminal
  final case class AnyOf(string: String) extends Terminal
  final case class CharMatch(char: Char) extends Terminal
  final case class CharPredicateMatch(predicate: CharPredicate) extends Terminal
  final case class CharRange(from: Char, to: Char) extends Terminal
  final case class Fail(expected: String) extends Terminal
  final case class IgnoreCaseChar(char: Char) extends Terminal
  final case class NoneOf(string: String) extends Terminal
  final case class NotPredicate(base: NotPredicate.Base, baseMatchLength: Int) extends Terminal
  case object SemanticPredicate extends Terminal

  object NotPredicate {
    sealed trait Base
    case object Anonymous extends Base
    final case class Named(name: String) extends Base
    final case class RuleCall(target: String) extends Base
    final case class Terminal(terminal: RuleTrace.Terminal) extends Base
  }
}