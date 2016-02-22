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
import java.lang.{ StringBuilder ⇒ JStringBuilder }

/**
 * Abstraction for error formatting logic.
 * Instantiate with a custom configuration or override with custom logic.
 *
 * @param showExpected whether a description of the expected input is to be shown
 * @param showPosition whether the error position is to be shown
 * @param showLine whether the input line with a error position indicator is to be shown
 * @param showTraces whether the error's rule trace are to be shown
 * @param showFrameStartOffset whether formatted traces should include the frame start offset
 * @param expandTabs whether and how tabs in the error input line are to be expanded.
 *                   The value indicates the column multiples that a tab represents
 *                   (equals the number of spaces that a leading tab is expanded into).
 *                   Set to a value < 0 to disable tab expansion.
 * @param traceCutOff the maximum number of (trailing) characters shown for a rule trace
 */
class ErrorFormatter(showExpected: Boolean = true,
                     showPosition: Boolean = true,
                     showLine: Boolean = true,
                     showTraces: Boolean = false,
                     showFrameStartOffset: Boolean = true,
                     expandTabs: Int = -1,
                     traceCutOff: Int = 120) {

  /**
   * Formats the given [[ParseError]] into a String using the settings configured for this formatter instance.
   */
  def format(error: ParseError, input: ParserInput): String =
    format(new JStringBuilder(128), error, input).toString

  /**
   * Formats the given [[ParseError]] into the given StringBuilder
   * using the settings configured for this formatter instance.
   */
  def format(sb: JStringBuilder, error: ParseError, input: ParserInput): JStringBuilder = {
    formatProblem(sb, error, input)
    import error._
    if (showExpected) formatExpected(sb, error)
    if (showPosition) sb.append(" (line ").append(position.line).append(", column ").append(position.column).append(')')
    if (showLine) formatErrorLine(sb.append(':').append('\n'), error, input)
    if (showTraces) sb.append('\n').append('\n').append(formatTraces(error)) else sb
  }

  /**
   * Formats a description of the error's cause into a single line String.
   */
  def formatProblem(error: ParseError, input: ParserInput): String =
    formatProblem(new JStringBuilder(64), error, input).toString

  /**
   * Formats a description of the error's cause into the given StringBuilder.
   */
  def formatProblem(sb: JStringBuilder, error: ParseError, input: ParserInput): JStringBuilder = {
    val ix = error.position.index
    if (ix < input.length) {
      val chars = mismatchLength(error)
      if (chars == 1) sb.append("Invalid input '").append(CharUtils.escape(input charAt ix)).append(''')
      else sb.append("Invalid input \"").append(CharUtils.escape(input.sliceString(ix, ix + chars))).append('"')
    } else sb.append("Unexpected end of input")
  }

  /**
   * Determines the number of characters to be shown as "mismatched" for the given [[ParseError]].
   */
  def mismatchLength(error: ParseError): Int =
    // Failing negative syntactic predicates, i.e. with a succeeding inner match, do not contribute
    // to advancing the principal error location (PEL). Therefore it might be that their succeeding inner match
    // reaches further than the PEL. In these cases we want to show the complete inner match as "mismatched",
    // not just the piece up to the PEL. This is what this method corrects for.
    error.effectiveTraces.foldLeft(error.principalPosition.index - error.position.index + 1) { (len, trace) ⇒
      import RuleTrace._
      trace.terminal match {
        case NotPredicate(_, x) ⇒
          math.max(trace.prefix.collectFirst { case NonTerminal(Atomic, off) ⇒ off + x } getOrElse x, len)
        case _ ⇒ len
      }
    }

  /**
   * Formats what is expected at the error location into a single line String including text padding.
   */
  def formatExpected(error: ParseError): String =
    formatExpected(new JStringBuilder(64), error).toString

  /**
   * Formats what is expected at the error location into the given StringBuilder including text padding.
   */
  def formatExpected(sb: JStringBuilder, error: ParseError): JStringBuilder =
    sb.append(", expected ").append(formatExpectedAsString(error))

  /**
   * Formats what is expected at the error location into a single line String.
   */
  def formatExpectedAsString(error: ParseError): String =
    formatExpectedAsString(new JStringBuilder(64), error).toString

  /**
   * Formats what is expected at the error location into the given StringBuilder.
   */
  def formatExpectedAsString(sb: JStringBuilder, error: ParseError): JStringBuilder = {
    @tailrec def rec(remaining: List[String]): JStringBuilder =
      remaining match {
        case Nil                 ⇒ sb.append("???")
        case head :: Nil         ⇒ sb.append(head)
        case head :: last :: Nil ⇒ sb.append(head).append(" or ").append(last)
        case head :: tail        ⇒ sb.append(head).append(", "); rec(tail)
      }
    rec(formatExpectedAsList(error))
  }

  /**
   * Formats what is expected at the error location as a [[List]] of Strings.
   */
  def formatExpectedAsList(error: ParseError): List[String] = {
    val distinctStrings: Set[String] = error.effectiveTraces.map(formatAsExpected)(collection.breakOut)
    distinctStrings.toList
  }

  /**
   * Formats the given trace into an "expected" string.
   */
  def formatAsExpected(trace: RuleTrace): String =
    if (trace.prefix.isEmpty) formatTerminal(trace.terminal)
    else formatNonTerminal(trace.prefix.head, showFrameStartOffset = false)

  /**
   * Formats the input line in which the error occurred and underlines
   * the given error's position in the line with a caret.
   */
  def formatErrorLine(error: ParseError, input: ParserInput): String =
    formatErrorLine(new JStringBuilder(64), error, input).toString

  /**
   * Formats the input line in which the error occurred and underlines
   * the given error's position in the line with a caret.
   */
  def formatErrorLine(sb: JStringBuilder, error: ParseError, input: ParserInput): JStringBuilder = {
    import error.position._
    val (expandedCol, expandedLine) = expandErrorLineTabs(input getLine line, column)
    sb.append(expandedLine).append('\n')
    for (i ← 1 until expandedCol) sb.append(' ')
    sb.append('^')
  }

  /**
   * Performs tab expansion as configured by the `expandTabs` member.
   * The `errorColumn` as well as the returned [[Int]] value are both 1-based.
   */
  def expandErrorLineTabs(line: String, errorColumn: Int): (Int, String) = {
    val sb = new StringBuilder
    @tailrec def rec(inCol: Int, errorCol: Int): Int =
      if (inCol < line.length) {
        val ec = if (inCol == errorColumn - 1) sb.length else errorCol
        line.charAt(inCol) match {
          case '\t' ⇒ sb.append(new String(Array.fill[Char](expandTabs - (sb.length % expandTabs))(' ')))
          case c    ⇒ sb.append(c)
        }
        rec(inCol + 1, ec)
      } else errorCol + 1
    if (expandTabs >= 0) rec(0, 0) -> sb.toString()
    else errorColumn -> line
  }

  /**
   * Formats a [[Vector]] of [[RuleTrace]] instances into a String.
   */
  def formatTraces(error: ParseError): String = {
    import error._
    traces.map(formatTrace(_, position.index)).mkString(traces.size + " rule" + (if (traces.size != 1) "s" else "") +
      " mismatched at error location:\n  ", "\n  ", "\n")
  }

  /**
   * Formats a [[RuleTrace]] into a String.
   */
  def formatTrace(trace: RuleTrace, errorIndex: Int): String = {
    import RuleTrace._
    val sb = new JStringBuilder
    val doSep: String ⇒ JStringBuilder = sb.append
    val dontSep: String ⇒ JStringBuilder = _ ⇒ sb
    def render(names: List[String], sep: String = "") = if (names.nonEmpty) names.reverse.mkString("", ":", sep) else ""
    @tailrec def rec(remainingPrefix: List[RuleTrace.NonTerminal], names: List[String],
                     sep: String ⇒ JStringBuilder): JStringBuilder =
      remainingPrefix match {
        case NonTerminal(Named(name), _) :: tail ⇒
          rec(tail, name :: names, sep)
        case NonTerminal(RuleCall, _) :: tail ⇒
          sep(" ").append('/').append(render(names)).append("/ ")
          rec(tail, Nil, dontSep)
        case NonTerminal(Sequence, _) :: tail if names.isEmpty ⇒
          rec(tail, Nil, sep)
        case NonTerminal(Sequence, _) :: tail ⇒
          sep(" / ").append(render(names))
          rec(tail, Nil, doSep)
        case x :: tail ⇒
          sep(" / ").append(render(names, ":")).append(formatNonTerminal(x))
          rec(tail, Nil, doSep)
        case Nil ⇒
          sep(" / ").append(render(names, ":")).append(formatTerminal(trace.terminal))
      }
    rec(trace.prefix, Nil, dontSep)
    if (sb.length > traceCutOff) "..." + sb.substring(math.max(sb.length - traceCutOff - 3, 0)) else sb.toString
  }

  /**
   * Formats the head element of a [[RuleTrace]] into a String.
   */
  def formatNonTerminal(nonTerminal: RuleTrace.NonTerminal,
                        showFrameStartOffset: Boolean = showFrameStartOffset): String = {
    import RuleTrace._
    import CharUtils.escape
    val keyString = nonTerminal.key match {
      case Action              ⇒ "<action>"
      case Atomic              ⇒ "atomic"
      case AndPredicate        ⇒ "&"
      case Capture             ⇒ "capture"
      case Cut                 ⇒ "cut"
      case FirstOf             ⇒ "|"
      case x: IgnoreCaseString ⇒ '"' + escape(x.string) + '"'
      case x: MapMatch         ⇒ x.map.toString()
      case x: Named            ⇒ x.name
      case OneOrMore           ⇒ "+"
      case Optional            ⇒ "?"
      case Quiet               ⇒ "quiet"
      case RuleCall            ⇒ "call"
      case Run                 ⇒ "<run>"
      case RunSubParser        ⇒ "runSubParser"
      case Sequence            ⇒ "~"
      case x: StringMatch      ⇒ '"' + escape(x.string) + '"'
      case x: Times            ⇒ "times"
      case ZeroOrMore          ⇒ "*"
    }
    if (nonTerminal.offset != 0 && showFrameStartOffset) keyString + ':' + nonTerminal.offset else keyString
  }

  def formatTerminal(terminal: RuleTrace.Terminal): String = {
    import RuleTrace._
    import CharUtils.escape
    terminal match {
      case ANY                                       ⇒ "ANY"
      case AnyOf(s)                                  ⇒ '[' + escape(s) + ']'
      case CharMatch(c)                              ⇒ "'" + escape(c) + '\''
      case CharPredicateMatch(_)                     ⇒ "<CharPredicate>"
      case CharRange(from, to)                       ⇒ s"'${escape(from)}'-'${escape(to)}'"
      case Fail(expected)                            ⇒ expected
      case IgnoreCaseChar(c)                         ⇒ "'" + escape(c) + '\''
      case NoneOf(s)                                 ⇒ s"[^${escape(s)}]"
      case NotPredicate(NotPredicate.Terminal(t), _) ⇒ "!" + formatTerminal(t)
      case NotPredicate(NotPredicate.RuleCall(t), _) ⇒ "!" + t
      case NotPredicate(NotPredicate.Named(n), _)    ⇒ "!" + n
      case NotPredicate(NotPredicate.Anonymous, _)   ⇒ "!<anon>"
      case SemanticPredicate                         ⇒ "test"
    }
  }
}
