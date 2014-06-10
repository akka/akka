/*
 * Copyright (C) 2009-2013 Mathias Doenitz, Alexander Myltsev
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

import CharUtils.escape

case class ParseError(position: Position, traces: Seq[RuleTrace]) extends RuntimeException {
  def formatExpectedAsString: String = {
    val expected = formatExpectedAsSeq
    expected.size match {
      case 0 ⇒ "??"
      case 1 ⇒ expected.head
      case _ ⇒ expected.init.mkString(", ") + " or " + expected.last
    }
  }
  def formatExpectedAsSeq: Seq[String] =
    traces.map { trace ⇒
      if (trace.frames.nonEmpty) {
        val exp = trace.frames.last.format
        val nonEmptyExp = if (exp.isEmpty) "?" else exp
        if (trace.isNegated) "!" + nonEmptyExp else nonEmptyExp
      } else "???"
    }.distinct

  def formatTraces: String =
    traces.map(_.format).mkString(traces.size + " rule" + (if (traces.size != 1) "s" else "") +
      " mismatched at error location:\n  ", "\n  ", "\n")
}

case class Position(index: Int, line: Int, column: Int)

// outermost (i.e. highest-level) rule first
case class RuleTrace(frames: Seq[RuleFrame]) {
  def format: String =
    frames.size match {
      case 0 ⇒ "<empty>"
      case 1 ⇒ frames.head.format
      case _ ⇒
        // we don't want to show intermediate Sequence and RuleCall frames in the trace
        def show(frame: RuleFrame) = !(frame.isInstanceOf[RuleFrame.Sequence] || frame.isInstanceOf[RuleFrame.RuleCall])
        frames.init.filter(show).map(_.format).mkString("", " / ", " / " + frames.last.format)
    }

  def isNegated: Boolean = (frames.count(_.anon == RuleFrame.NotPredicate) & 0x01) > 0
}

sealed abstract class RuleFrame {
  import RuleFrame._
  def anon: RuleFrame.Anonymous

  def format: String =
    this match {
      case Named(name, _)              ⇒ name
      case Sequence(_)                 ⇒ "~"
      case FirstOf(_)                  ⇒ "|"
      case CharMatch(c)                ⇒ "'" + escape(c) + '\''
      case StringMatch(s)              ⇒ '"' + escape(s) + '"'
      case MapMatch(m)                 ⇒ m.toString()
      case IgnoreCaseChar(c)           ⇒ "'" + escape(c) + '\''
      case IgnoreCaseString(s)         ⇒ '"' + escape(s) + '"'
      case CharPredicateMatch(_, name) ⇒ if (name.nonEmpty) name else "<anon predicate>"
      case RuleCall(callee)            ⇒ '(' + callee + ')'
      case AnyOf(s)                    ⇒ '[' + escape(s) + ']'
      case NoneOf(s)                   ⇒ s"[^${escape(s)}]"
      case Times(_, _)                 ⇒ "times"
      case CharRange(from, to)         ⇒ s"'${escape(from)}'-'${escape(to)}'"
      case AndPredicate                ⇒ "&"
      case NotPredicate                ⇒ "!"
      case SemanticPredicate           ⇒ "test"
      case ANY                         ⇒ "ANY"
      case _ ⇒ {
        val s = toString
        s.updated(0, s.charAt(0).toLower)
      }
    }
}

object RuleFrame {
  def apply(frame: Anonymous, name: String): RuleFrame =
    if (name.isEmpty) frame else Named(name, frame)

  case class Named(name: String, anon: Anonymous) extends RuleFrame

  sealed abstract class Anonymous extends RuleFrame {
    def anon: Anonymous = this
  }
  case class Sequence(subs: Int) extends Anonymous
  case class FirstOf(subs: Int) extends Anonymous
  case class CharMatch(char: Char) extends Anonymous
  case class StringMatch(string: String) extends Anonymous
  case class MapMatch(map: Map[String, Any]) extends Anonymous
  case class IgnoreCaseChar(char: Char) extends Anonymous
  case class IgnoreCaseString(string: String) extends Anonymous
  case class CharPredicateMatch(predicate: CharPredicate, name: String) extends Anonymous
  case class AnyOf(string: String) extends Anonymous
  case class NoneOf(string: String) extends Anonymous
  case class Times(min: Int, max: Int) extends Anonymous
  case class RuleCall(callee: String) extends Anonymous
  case class CharRange(from: Char, to: Char) extends Anonymous
  case object ANY extends Anonymous
  case object Optional extends Anonymous
  case object ZeroOrMore extends Anonymous
  case object OneOrMore extends Anonymous
  case object AndPredicate extends Anonymous
  case object NotPredicate extends Anonymous
  case object SemanticPredicate extends Anonymous
  case object Capture extends Anonymous
  case object Run extends Anonymous
  case object Push extends Anonymous
  case object Drop extends Anonymous
  case object Action extends Anonymous
  case object RunSubParser extends Anonymous
}
