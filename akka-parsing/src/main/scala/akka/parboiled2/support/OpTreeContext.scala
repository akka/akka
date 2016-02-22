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

package akka.parboiled2.support

import scala.annotation.tailrec
import akka.parboiled2._

trait OpTreeContext[OpTreeCtx <: ParserMacros.ParserContext] {
  val c: OpTreeCtx
  import c.universe._

  sealed trait OpTree {
    // renders a Boolean Tree
    def render(wrapped: Boolean): Tree
  }

  sealed abstract class NonTerminalOpTree extends OpTree {
    def bubbleUp: Tree

    // renders a Boolean Tree
    def render(wrapped: Boolean): Tree =
      if (wrapped) q"""
        val start = cursor
        try ${renderInner(wrapped)}
        catch { case e: akka.parboiled2.Parser#TracingBubbleException ⇒ $bubbleUp }"""
      else renderInner(wrapped)

    // renders a Boolean Tree
    protected def renderInner(wrapped: Boolean): Tree
  }

  sealed abstract class DefaultNonTerminalOpTree extends NonTerminalOpTree {
    def bubbleUp: Tree = q"e.bubbleUp($ruleTraceNonTerminalKey, start)"
    def ruleTraceNonTerminalKey: Tree
  }

  sealed abstract class TerminalOpTree extends OpTree {
    def bubbleUp: Tree = q"__bubbleUp($ruleTraceTerminal)"
    def ruleTraceTerminal: Tree

    // renders a Boolean Tree
    final def render(wrapped: Boolean): Tree =
      if (wrapped) q"""
        try ${renderInner(wrapped)}
        catch { case akka.parboiled2.Parser.StartTracingException ⇒ $bubbleUp }"""
      else renderInner(wrapped)

    // renders a Boolean Tree
    protected def renderInner(wrapped: Boolean): Tree
  }

  sealed abstract class PotentiallyNamedTerminalOpTree(arg: Tree) extends TerminalOpTree {
    override def bubbleUp = callName(arg) match {
      case Some(name) ⇒ q"__bubbleUp(akka.parboiled2.RuleTrace.NonTerminal(akka.parboiled2.RuleTrace.Named($name), 0) :: Nil, $ruleTraceTerminal)"
      case None       ⇒ super.bubbleUp
    }
    def ruleTraceTerminal: Tree
  }

  def collector(lifterTree: Tree): Collector =
    lifterTree match {
      case q"support.this.$a.forRule0[$b]" ⇒ rule0Collector
      case q"support.this.$a.forRule1[$b, $c]" ⇒ rule1Collector
      case q"support.this.$a.forReduction[$b, $c, $d]" ⇒ rule0Collector
      case x ⇒ c.abort(x.pos, "Unexpected Lifter: " + lifterTree)
    }

  val opTreePF: PartialFunction[Tree, OpTree] = {
    case q"$lhs.~[$a, $b]($rhs)($c, $d)"                   ⇒ Sequence(OpTree(lhs), OpTree(rhs))
    case q"$lhs.~!~[$a, $b]($rhs)($c, $d)"                 ⇒ Cut(OpTree(lhs), OpTree(rhs))
    case q"$lhs.|[$a, $b]($rhs)"                           ⇒ FirstOf(OpTree(lhs), OpTree(rhs))
    case q"$a.this.ch($c)"                                 ⇒ CharMatch(c)
    case q"$a.this.str($s)"                                ⇒ StringMatch(s)
    case q"$a.this.valueMap[$b]($m)($hl)"                  ⇒ MapMatch(m)
    case q"$a.this.ignoreCase($t)"                         ⇒ IgnoreCase(t)
    case q"$a.this.predicate($p)"                          ⇒ CharPredicateMatch(p)
    case q"$a.this.anyOf($s)"                              ⇒ AnyOf(s)
    case q"$a.this.noneOf($s)"                             ⇒ NoneOf(s)
    case q"$a.this.ANY"                                    ⇒ ANY
    case q"$a.this.optional[$b, $c]($arg)($l)"             ⇒ Optional(OpTree(arg), collector(l))
    case q"$base.?($l)"                                    ⇒ Optional(OpTree(base), collector(l))
    case q"$a.this.zeroOrMore[$b, $c]($arg)($l)"           ⇒ ZeroOrMore(OpTree(arg), collector(l))
    case q"$base.*($l)"                                    ⇒ ZeroOrMore(OpTree(base), collector(l))
    case q"$base.*($sep)($l)"                              ⇒ ZeroOrMore(OpTree(base), collector(l), Separator(OpTree(sep)))
    case q"$a.this.oneOrMore[$b, $c]($arg)($l)"            ⇒ OneOrMore(OpTree(arg), collector(l))
    case q"$base.+($l)"                                    ⇒ OneOrMore(OpTree(base), collector(l))
    case q"$base.+($sep)($l)"                              ⇒ OneOrMore(OpTree(base), collector(l), Separator(OpTree(sep)))
    case q"$base.times[$a, $b]($r)($s)"                    ⇒ Times(base, OpTree(r), collector(s))
    case q"$a.this.&($arg)"                                ⇒ AndPredicate(OpTree(arg))
    case q"$a.unary_!()"                                   ⇒ NotPredicate(OpTree(a))
    case q"$a.this.atomic[$b, $c]($arg)"                   ⇒ Atomic(OpTree(arg))
    case q"$a.this.quiet[$b, $c]($arg)"                    ⇒ Quiet(OpTree(arg))
    case q"$a.this.test($flag)"                            ⇒ SemanticPredicate(flag)
    case q"$a.this.capture[$b, $c]($arg)($d)"              ⇒ Capture(OpTree(arg))
    case q"$a.this.run[$b]($arg)($c.fromAux[$d, $e]($rr))" ⇒ RunAction(arg, rr)
    case q"$a.this.push[$b]($arg)($hl)"                    ⇒ PushAction(arg, hl)
    case q"$a.this.drop[$b]($hl)"                          ⇒ DropAction(hl)
    case q"$a.this.runSubParser[$b, $c]($f)"               ⇒ RunSubParser(f)
    case q"$a.this.fail($m)"                               ⇒ Fail(m)
    case q"$a.this.failX[$b, $c]($m)"                      ⇒ Fail(m)
    case q"$a.named($name)"                                ⇒ Named(OpTree(a), name)
    case x @ q"$a.this.str2CharRangeSupport($l).-($r)"     ⇒ CharRange(l, r)
    case q"$a.this.charAndValue[$t]($b.any2ArrowAssoc[$t1]($c).->[$t2]($v))($hl)" ⇒
      Sequence(CharMatch(c), PushAction(v, hl))
    case q"$a.this.stringAndValue[$t]($b.any2ArrowAssoc[$t1]($s).->[$t2]($v))($hl)" ⇒
      Sequence(StringMatch(s), PushAction(v, hl))
    case q"$a.this.rule2ActionOperator[$b1, $b2]($r)($o).~>.apply[..$e]($f)($g, support.this.FCapture.apply[$ts])" ⇒
      Sequence(OpTree(r), Action(f, ts))
    case x @ q"$a.this.rule2WithSeparatedBy[$b1, $b2]($base).separatedBy($sep)" ⇒
      OpTree(base) match {
        case x: WithSeparator ⇒ x.withSeparator(Separator(OpTree(sep)))
        case _                ⇒ c.abort(x.pos, "Illegal `separatedBy` base: " + base)
      }
    case call @ (Apply(_, _) | Select(_, _) | Ident(_) | TypeApply(_, _)) ⇒
      RuleCall(Right(call), Literal(Constant(callName(call) getOrElse c.abort(call.pos, "Illegal rule call: " + call))))
  }

  def OpTree(tree: Tree): OpTree =
    opTreePF.applyOrElse(tree, (t: Tree) ⇒ c.abort(t.pos, "Invalid rule definition: " + t))

  def Sequence(lhs: OpTree, rhs: OpTree): Sequence =
    lhs -> rhs match {
      case (Sequence(lops), Sequence(rops)) ⇒ Sequence(lops ++ rops)
      case (Sequence(lops), _)              ⇒ Sequence(lops :+ rhs)
      case (_, Sequence(ops))               ⇒ Sequence(lhs +: ops)
      case _                                ⇒ Sequence(Seq(lhs, rhs))
    }

  case class Sequence(ops: Seq[OpTree]) extends DefaultNonTerminalOpTree {
    require(ops.size >= 2)
    def ruleTraceNonTerminalKey = reify(RuleTrace.Sequence).tree
    def renderInner(wrapped: Boolean): Tree =
      ops.map(_.render(wrapped)).reduceLeft((l, r) ⇒
        q"val l = $l; if (l) $r else false") // work-around for https://issues.scala-lang.org/browse/SI-8657"
  }

  case class Cut(lhs: OpTree, rhs: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.Cut).tree
    def renderInner(wrapped: Boolean): Tree = q"""
      var matched = ${lhs.render(wrapped)}
      if (matched) {
        matched = ${rhs.render(wrapped)}
        if (!matched) throw akka.parboiled2.Parser.CutError
        true
      } else false""" // work-around for https://issues.scala-lang.org/browse/SI-8657
  }

  def FirstOf(lhs: OpTree, rhs: OpTree): FirstOf =
    lhs -> rhs match {
      case (FirstOf(lops), FirstOf(rops)) ⇒ FirstOf(lops ++ rops)
      case (FirstOf(lops), _)             ⇒ FirstOf(lops :+ rhs)
      case (_, FirstOf(ops))              ⇒ FirstOf(lhs +: ops)
      case _                              ⇒ FirstOf(Seq(lhs, rhs))
    }

  case class FirstOf(ops: Seq[OpTree]) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.FirstOf).tree
    def renderInner(wrapped: Boolean): Tree =
      q"""val mark = __saveState; ${
        ops.map(_.render(wrapped)).reduceLeft((l, r) ⇒
          q"val l = $l; if (!l) { __restoreState(mark); $r } else true // work-around for https://issues.scala-lang.org/browse/SI-8657")
      }"""
  }

  case class CharMatch(charTree: Tree) extends TerminalOpTree {
    def ruleTraceTerminal = q"akka.parboiled2.RuleTrace.CharMatch($charTree)"
    def renderInner(wrapped: Boolean): Tree = {
      val unwrappedTree = q"cursorChar == $charTree && __advance()"
      if (wrapped) q"$unwrappedTree && __updateMaxCursor() || __registerMismatch()" else unwrappedTree
    }
  }

  case class StringMatch(stringTree: Tree) extends OpTree {
    final private val autoExpandMaxStringLength = 8
    def render(wrapped: Boolean): Tree = {
      def unrollUnwrapped(s: String, ix: Int = 0): Tree =
        if (ix < s.length) q"""
          if (cursorChar == ${s charAt ix}) {
            __advance()
            ${unrollUnwrapped(s, ix + 1)}:Boolean
          } else false"""
        else q"true"
      def unrollWrapped(s: String, ix: Int = 0): Tree =
        if (ix < s.length) {
          val ch = s charAt ix
          q"""if (cursorChar == $ch) {
            __advance()
            __updateMaxCursor()
            ${unrollWrapped(s, ix + 1)}
          } else {
            try __registerMismatch()
            catch {
              case akka.parboiled2.Parser.StartTracingException ⇒
                import akka.parboiled2.RuleTrace._
                __bubbleUp(NonTerminal(StringMatch($stringTree), -$ix) :: Nil, CharMatch($ch))
            }
          }"""
        } else q"true"

      stringTree match {
        case Literal(Constant(s: String)) if s.length <= autoExpandMaxStringLength ⇒
          if (s.isEmpty) q"true" else if (wrapped) unrollWrapped(s) else unrollUnwrapped(s)
        case _ ⇒
          if (wrapped) q"__matchStringWrapped($stringTree)"
          else q"__matchString($stringTree)"
      }
    }
  }

  case class MapMatch(mapTree: Tree) extends OpTree {
    def render(wrapped: Boolean): Tree = if (wrapped) q"__matchMapWrapped($mapTree)" else q"__matchMap($mapTree)"
  }

  def IgnoreCase(argTree: Tree): OpTree = {
    val argTypeSymbol = argTree.tpe.typeSymbol
    if (argTypeSymbol == definitions.CharClass) IgnoreCaseChar(argTree)
    else if (argTypeSymbol == definitions.StringClass) IgnoreCaseString(argTree)
    else c.abort(argTree.pos, "Unexpected `ignoreCase` argument type: " + argTypeSymbol)
  }

  case class IgnoreCaseChar(charTree: Tree) extends TerminalOpTree {
    def ruleTraceTerminal = q"akka.parboiled2.RuleTrace.IgnoreCaseChar($charTree)"
    def renderInner(wrapped: Boolean): Tree = {
      val unwrappedTree = q"_root_.java.lang.Character.toLowerCase(cursorChar) == $charTree && __advance()"
      if (wrapped) q"$unwrappedTree && __updateMaxCursor() || __registerMismatch()" else unwrappedTree
    }
  }

  case class IgnoreCaseString(stringTree: Tree) extends OpTree {
    final private val autoExpandMaxStringLength = 8
    def render(wrapped: Boolean): Tree = {
      def unrollUnwrapped(s: String, ix: Int = 0): Tree =
        if (ix < s.length) q"""
          if (_root_.java.lang.Character.toLowerCase(cursorChar) == ${s charAt ix}) {
            __advance()
            ${unrollUnwrapped(s, ix + 1)}
          } else false"""
        else q"true"
      def unrollWrapped(s: String, ix: Int = 0): Tree =
        if (ix < s.length) {
          val ch = s charAt ix
          q"""if (_root_.java.lang.Character.toLowerCase(cursorChar) == $ch) {
            __advance()
            __updateMaxCursor()
            ${unrollWrapped(s, ix + 1)}
          } else {
            try __registerMismatch()
            catch {
              case akka.parboiled2.Parser.StartTracingException ⇒
                import akka.parboiled2.RuleTrace._
                __bubbleUp(NonTerminal(IgnoreCaseString($stringTree), -$ix) :: Nil, IgnoreCaseChar($ch))
            }
          }"""
        } else q"true"

      stringTree match {
        case Literal(Constant(s: String)) if s.length <= autoExpandMaxStringLength ⇒
          if (s.isEmpty) q"true" else if (wrapped) unrollWrapped(s) else unrollUnwrapped(s)
        case _ ⇒
          if (wrapped) q"__matchIgnoreCaseStringWrapped($stringTree)"
          else q"__matchIgnoreCaseString($stringTree)"
      }
    }
  }

  case class CharPredicateMatch(predicateTree: Tree) extends PotentiallyNamedTerminalOpTree(predicateTree) {
    def ruleTraceTerminal = q"akka.parboiled2.RuleTrace.CharPredicateMatch($predicateTree)"
    def renderInner(wrapped: Boolean): Tree = {
      val unwrappedTree = q"$predicateTree(cursorChar) && __advance()"
      if (wrapped) q"$unwrappedTree && __updateMaxCursor() || __registerMismatch()" else unwrappedTree
    }
  }

  case class AnyOf(stringTree: Tree) extends TerminalOpTree {
    def ruleTraceTerminal = q"akka.parboiled2.RuleTrace.AnyOf($stringTree)"
    def renderInner(wrapped: Boolean): Tree = {
      val unwrappedTree = q"__matchAnyOf($stringTree)"
      if (wrapped) q"$unwrappedTree && __updateMaxCursor() || __registerMismatch()" else unwrappedTree
    }
  }

  case class NoneOf(stringTree: Tree) extends TerminalOpTree {
    def ruleTraceTerminal = q"akka.parboiled2.RuleTrace.NoneOf($stringTree)"
    def renderInner(wrapped: Boolean): Tree = {
      val unwrappedTree = q"__matchNoneOf($stringTree)"
      if (wrapped) q"$unwrappedTree && __updateMaxCursor() || __registerMismatch()" else unwrappedTree
    }
  }

  case object ANY extends TerminalOpTree {
    def ruleTraceTerminal = reify(RuleTrace.ANY).tree
    def renderInner(wrapped: Boolean): Tree = {
      val unwrappedTree = q"cursorChar != EOI && __advance()"
      if (wrapped) q"$unwrappedTree && __updateMaxCursor() || __registerMismatch()" else unwrappedTree
    }
  }

  case class Optional(op: OpTree, collector: Collector) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.Optional).tree
    def renderInner(wrapped: Boolean): Tree = q"""
      val mark = __saveState
      val matched = ${op.render(wrapped)}
      if (matched) {
        ${collector.pushSomePop}
      } else {
        __restoreState(mark)
        ${collector.pushNone}
      }
      true"""
  }

  sealed abstract class WithSeparator extends DefaultNonTerminalOpTree {
    def withSeparator(sep: Separator): OpTree
  }

  case class ZeroOrMore(op: OpTree, collector: Collector, separator: Separator = null) extends WithSeparator {
    def withSeparator(sep: Separator) = copy(separator = sep)
    def ruleTraceNonTerminalKey = reify(RuleTrace.ZeroOrMore).tree
    def renderInner(wrapped: Boolean): Tree = {
      val recurse =
        if (separator eq null) q"rec(__saveState)"
        else q"val m = __saveState; if (${separator(wrapped)}) rec(m) else m"

      q"""
      ${collector.valBuilder}

      @_root_.scala.annotation.tailrec def rec(mark: akka.parboiled2.Parser.Mark): akka.parboiled2.Parser.Mark = {
        val matched = ${op.render(wrapped)}
        if (matched) {
          ${collector.popToBuilder}
          $recurse
        } else mark
      }

      __restoreState(rec(__saveState))
      ${collector.pushBuilderResult}"""
    }
  }

  case class OneOrMore(op: OpTree, collector: Collector, separator: Separator = null) extends WithSeparator {
    def withSeparator(sep: Separator) = copy(separator = sep)
    def ruleTraceNonTerminalKey = reify(RuleTrace.OneOrMore).tree
    def renderInner(wrapped: Boolean): Tree = {
      val recurse =
        if (separator eq null) q"rec(__saveState)"
        else q"val m = __saveState; if (${separator(wrapped)}) rec(m) else m"

      q"""
      val firstMark = __saveState
      ${collector.valBuilder}

      @_root_.scala.annotation.tailrec def rec(mark: akka.parboiled2.Parser.Mark): akka.parboiled2.Parser.Mark = {
        val matched = ${op.render(wrapped)}
        if (matched) {
          ${collector.popToBuilder}
          $recurse
        } else mark
      }

      val mark = rec(firstMark)
      mark != firstMark && {
        __restoreState(mark)
        ${collector.pushBuilderResult}
      }"""
    }
  }

  def Times(base: Tree, rule: OpTree, collector: Collector, separator: Separator = null): OpTree = {
    def handleRange(mn: Tree, mx: Tree, r: Tree) = (mn, mx) match {
      case (Literal(Constant(min: Int)), Literal(Constant(max: Int))) ⇒
        if (min <= 0) c.abort(mn.pos, "`min` in `(min to max).times` must be positive")
        else if (max <= 0) c.abort(mx.pos, "`max` in `(min to max).times` must be positive")
        else if (max < min) c.abort(mx.pos, "`max` in `(min to max).times` must be >= `min`")
        else Times(rule, q"val min = $mn; val max = $mx", collector, separator)
      case ((Ident(_) | Select(_, _)), (Ident(_) | Select(_, _))) ⇒
        Times(rule, q"val min = $mn; val max = $mx", collector, separator)
      case _ ⇒ c.abort(r.pos, "Invalid int range expression for `.times(...)`: " + r)
    }

    base match {
      case q"$a.this.int2NTimes($n)" ⇒ n match {
        case Literal(Constant(i: Int)) ⇒
          if (i <= 0) c.abort(base.pos, "`x` in `x.times` must be positive")
          else if (i == 1) rule
          else Times(rule, q"val min, max = $n", collector, separator)
        case x@(Ident(_) | Select(_, _)) ⇒ Times(rule, q"val min = $n; val max = min", collector, separator)
        case _ ⇒ c.abort(n.pos, "Invalid int base expression for `.times(...)`: " + n)
      }
      case q"$a.this.range2NTimes($r)" ⇒ r match {
        case q"scala.Predef.intWrapper($mn).to($mx)" ⇒ handleRange(mn, mx, r) // Scala 2.12
        case q"scala.this.Predef.intWrapper($mn).to($mx)" ⇒ handleRange(mn, mx, r) // Scala 2.11
        case x@(Ident(_) | Select(_, _)) ⇒
          Times(rule, q"val r = $r; val min = r.start; val max = r.end", collector, separator)
        case _ ⇒ c.abort(r.pos, "Invalid range base expression for `.times(...)`: " + r)
      }
      case _ ⇒ c.abort(base.pos, "Invalid base expression for `.times(...)`: " + base)
    }
  }

  case class Times(op: OpTree, init: Tree, collector: Collector, separator: Separator) extends WithSeparator {
    def withSeparator(sep: Separator) = copy(separator = sep)
    val Block(inits, _) = init
    def ruleTraceNonTerminalKey = q"..$inits; akka.parboiled2.RuleTrace.Times(min, max)"
    def renderInner(wrapped: Boolean): Tree = {
      val recurse =
        if (separator eq null) q"rec(count + 1, __saveState)"
        else q"""
          val m = __saveState; if (${separator(wrapped)}) rec(count + 1, m)
          else (count >= min) && { __restoreState(m); true }"""

      q"""
      ${collector.valBuilder}
      ..$inits

      @_root_.scala.annotation.tailrec def rec(count: Int, mark: akka.parboiled2.Parser.Mark): Boolean = {
        val matched = ${op.render(wrapped)}
        if (matched) {
          ${collector.popToBuilder}
          if (count < max) $recurse else true
        } else (count > min) && { __restoreState(mark); true }
      }

      (max <= 0) || rec(1, __saveState) && ${collector.pushBuilderResult}"""
    }
  }

  case class AndPredicate(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.AndPredicate).tree
    def renderInner(wrapped: Boolean): Tree = q"""
      val mark = __saveState
      val matched = ${op.render(wrapped)}
      __restoreState(mark)
      matched"""
  }

  case class NotPredicate(op: OpTree) extends OpTree {
    def render(wrapped: Boolean): Tree = {
      val unwrappedTree = q"""
        val mark = __saveState
        val saved = __enterNotPredicate()
        val matched = ${op.render(wrapped)}
        __exitNotPredicate(saved)
        ${if (wrapped) q"matchEnd = cursor" else q"()"}
        __restoreState(mark)
        !matched"""
      if (wrapped) {
        val base = op match {
          case x: TerminalOpTree   ⇒ q"akka.parboiled2.RuleTrace.NotPredicate.Terminal(${x.ruleTraceTerminal})"
          case x: RuleCall         ⇒ q"akka.parboiled2.RuleTrace.NotPredicate.RuleCall(${x.calleeNameTree})"
          case x: StringMatch      ⇒ q"""akka.parboiled2.RuleTrace.NotPredicate.Named('"' + ${x.stringTree} + '"')"""
          case x: IgnoreCaseString ⇒ q"""akka.parboiled2.RuleTrace.NotPredicate.Named('"' + ${x.stringTree} + '"')"""
          case x: Named            ⇒ q"akka.parboiled2.RuleTrace.NotPredicate.Named(${x.stringTree})"
          case _                   ⇒ q"akka.parboiled2.RuleTrace.NotPredicate.Anonymous"
        }
        q"""
        var matchEnd = 0
        try $unwrappedTree || __registerMismatch()
        catch {
          case akka.parboiled2.Parser.StartTracingException ⇒ __bubbleUp {
            akka.parboiled2.RuleTrace.NotPredicate($base, matchEnd - cursor)
          }
        }"""
      } else unwrappedTree
    }
  }

  case class Atomic(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.Atomic).tree
    def renderInner(wrapped: Boolean): Tree =
      if (wrapped) q"""
        val saved = __enterAtomic(start)
        val matched = ${op.render(wrapped)}
        __exitAtomic(saved)
        matched"""
      else op.render(wrapped)
  }

  case class Quiet(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.Quiet).tree
    def renderInner(wrapped: Boolean): Tree =
      if (wrapped) q"""
        val saved = __enterQuiet()
        val matched = ${op.render(wrapped)}
        __exitQuiet(saved)
        matched"""
      else op.render(wrapped)
  }

  case class SemanticPredicate(flagTree: Tree) extends TerminalOpTree {
    def ruleTraceTerminal = reify(RuleTrace.SemanticPredicate).tree
    def renderInner(wrapped: Boolean): Tree =
      if (wrapped) q"$flagTree || __registerMismatch()" else flagTree
  }

  case class Capture(op: OpTree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.Capture).tree
    def renderInner(wrapped: Boolean): Tree = q"""
      ${if (!wrapped) q"val start = cursor" else q"();"}
      val matched = ${op.render(wrapped)}
      if (matched) {
        valueStack.push(input.sliceString(start, cursor))
        true
      } else false"""
  }

  case class RunAction(argTree: Tree, rrTree: Tree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.Run).tree
    def renderInner(wrapped: Boolean): Tree = {
      def renderFunctionAction(resultTypeTree: Tree, argTypeTrees: Tree*): Tree = {
        def actionBody(tree: Tree): Tree =
          tree match {
            case Block(statements, res) ⇒ block(statements, actionBody(res))

            case q"(..$args ⇒ $body)" ⇒
              def rewrite(tree: Tree): Tree =
                tree match {
                  case Block(statements, res) ⇒ block(statements, rewrite(res))
                  case x if isSubClass(resultTypeTree.tpe, "akka.parboiled2.Rule") ⇒ expand(x, wrapped)
                  case x ⇒ q"__push($x)"
                }
              val valDefs = args.zip(argTypeTrees).map { case (a, t) ⇒ q"val ${a.name} = valueStack.pop().asInstanceOf[${t.tpe}]" }.reverse
              block(valDefs, rewrite(body))

            case x ⇒ c.abort(argTree.pos, "Unexpected `run` argument: " + show(argTree))
          }

        actionBody(c.resetLocalAttrs(argTree))
      }

      rrTree match {
        case q"RunResult.this.Aux.forAny[$t]"                                   ⇒ block(argTree, q"true")

        case q"RunResult.this.Aux.forRule[$t]"                                  ⇒ expand(argTree, wrapped)

        case q"RunResult.this.Aux.forF1[$z, $r, $in, $out]($a)"                 ⇒ renderFunctionAction(r, z)
        case q"RunResult.this.Aux.forF2[$y, $z, $r, $in, $out]($a)"             ⇒ renderFunctionAction(r, y, z)
        case q"RunResult.this.Aux.forF3[$x, $y, $z, $r, $in, $out]($a)"         ⇒ renderFunctionAction(r, x, y, z)
        case q"RunResult.this.Aux.forF4[$w, $x, $y, $z, $r, $in, $out]($a)"     ⇒ renderFunctionAction(r, w, x, y, z)
        case q"RunResult.this.Aux.forF5[$v, $w, $x, $y, $z, $r, $in, $out]($a)" ⇒ renderFunctionAction(r, v, w, x, y, z)

        case q"RunResult.this.Aux.forFHList[$il, $r, $in, $out]($a)" ⇒
          c.abort(argTree.pos, "`run` with a function taking an HList is not yet implemented") // TODO: implement

        case x ⇒ c.abort(rrTree.pos, "Unexpected RunResult.Aux: " + show(x))
      }
    }
  }

  case class PushAction(argTree: Tree, hlTree: Tree) extends OpTree {
    def render(wrapped: Boolean): Tree =
      block(hlTree match {
        case q"support.this.HListable.fromUnit"       ⇒ argTree
        case q"support.this.HListable.fromHList[$t]"  ⇒ q"valueStack.pushAll(${c.resetLocalAttrs(argTree)})"
        case q"support.this.HListable.fromAnyRef[$t]" ⇒ q"valueStack.push(${c.resetLocalAttrs(argTree)})"
        case x                                        ⇒ c.abort(hlTree.pos, "Unexpected HListable: " + show(x))
      }, q"true")
  }

  case class DropAction(hlTree: Tree) extends OpTree {
    def render(wrapped: Boolean): Tree =
      hlTree match {
        case q"support.this.HListable.fromUnit"       ⇒ q"true"
        case q"support.this.HListable.fromAnyRef[$t]" ⇒ q"valueStack.pop(); true"
        case q"support.this.HListable.fromHList[$t]" ⇒
          @tailrec def rec(t: Type, result: List[Tree] = Nil): List[Tree] =
            t match { // TODO: how can we use type quotes here, e.g. tq"shapeless.HNil"?
              case TypeRef(_, sym, List(_, tail)) if sym == HListConsTypeSymbol ⇒ rec(tail, q"valueStack.pop()" :: result)
              case TypeRef(_, sym, _) if sym == HNilTypeSymbol                  ⇒ result
            }
          Block(rec(t.tpe), q"true")
        case x ⇒ c.abort(hlTree.pos, "Unexpected HListable: " + show(x))
      }
  }

  case class RuleCall(call: Either[OpTree, Tree], calleeNameTree: Tree) extends NonTerminalOpTree {
    def bubbleUp = q"""
      import akka.parboiled2.RuleTrace._
      e.prepend(RuleCall, start).bubbleUp(Named($calleeNameTree), start)"""
    override def render(wrapped: Boolean) =
      call match {
        case Left(_)  ⇒ super.render(wrapped)
        case Right(x) ⇒ q"$x ne null"
      }
    def renderInner(wrapped: Boolean) = call.asInstanceOf[Left[OpTree, Tree]].a.render(wrapped)
  }

  def CharRange(lowerTree: Tree, upperTree: Tree): CharacterRange = {
    val (lower, upper) = lowerTree -> upperTree match {
      case (Literal(Constant(l: String)), Literal(Constant(u: String))) ⇒ l -> u
      case _ ⇒ c.abort(lowerTree.pos, "Character ranges must be specified with string literals")
    }
    if (lower.length != 1) c.abort(lowerTree.pos, "lower bound must be a single char string")
    if (upper.length != 1) c.abort(upperTree.pos, "upper bound must be a single char string")
    val lowerBoundChar = lower.charAt(0)
    val upperBoundChar = upper.charAt(0)
    if (lowerBoundChar > upperBoundChar) c.abort(lowerTree.pos, "lower bound must not be > upper bound")
    CharacterRange(lowerBoundChar, upperBoundChar)
  }

  case class CharacterRange(lowerBound: Char, upperBound: Char) extends TerminalOpTree {
    def ruleTraceTerminal = q"akka.parboiled2.RuleTrace.CharRange($lowerBound, $upperBound)"
    def renderInner(wrapped: Boolean): Tree = {
      val unwrappedTree = q"""
        val char = cursorChar
        $lowerBound <= char && char <= $upperBound && __advance()"""
      if (wrapped) q"$unwrappedTree && __updateMaxCursor() || __registerMismatch()" else unwrappedTree
    }
  }

  case class Action(actionTree: Tree, actionTypeTree: Tree) extends DefaultNonTerminalOpTree {
    val actionType: List[Type] = actionTypeTree.tpe match {
      case TypeRef(_, _, args) if args.nonEmpty ⇒ args
      case x                                    ⇒ c.abort(actionTree.pos, "Unexpected action type: " + x)
    }
    def ruleTraceNonTerminalKey = reify(RuleTrace.Action).tree
    def renderInner(wrapped: Boolean): Tree = {
      val argTypes = actionType dropRight 1

      def popToVals(valNames: List[TermName]): List[Tree] =
        (valNames zip argTypes).map { case (n, t) ⇒ q"val $n = valueStack.pop().asInstanceOf[$t]" }.reverse

      def actionBody(tree: Tree): Tree =
        tree match {
          case Block(statements, res) ⇒ block(statements, actionBody(res))

          case x @ (Ident(_) | Select(_, _)) ⇒
            val valNames: List[TermName] = argTypes.indices.map { i ⇒ newTermName("value" + i) }(collection.breakOut)
            val args = valNames map Ident.apply
            block(popToVals(valNames), q"__push($x(..$args))")

          case q"(..$args ⇒ $body)" ⇒
            def rewrite(tree: Tree): Tree =
              tree match {
                case Block(statements, res) ⇒ block(statements, rewrite(res))
                case x if isSubClass(actionType.last, "akka.parboiled2.Rule") ⇒ expand(x, wrapped)
                case x ⇒ q"__push($x)"
              }
            block(popToVals(args.map(_.name)), rewrite(body))
        }

      actionBody(c.resetLocalAttrs(actionTree))
    }
  }

  case class RunSubParser(fTree: Tree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = reify(RuleTrace.RunSubParser).tree
    def renderInner(wrapped: Boolean): Tree = {
      def rewrite(arg: TermName, tree: Tree): Tree =
        tree match {
          case Block(statements, res) ⇒ block(statements, rewrite(arg, res))
          case q"$p.$rule" ⇒ q"""
            val $arg = new __SubParserInput()  // TODO: avoid re-allocation by re-using a cached instance
            val __subParser = $p
            val offset = cursor
            __subParser.copyStateFrom(this, offset)
            try __subParser.$rule ne null
            finally this.copyStateFrom(__subParser, -offset)"""
          case x ⇒ c.abort(x.pos, "Illegal runSubParser expr: " + show(x))
        }

      val q"($arg ⇒ $body)" = c.resetLocalAttrs(fTree)
      rewrite(arg.name, body)
    }
  }

  case class Fail(stringTree: Tree) extends OpTree {
    def render(wrapped: Boolean): Tree = q"throw new akka.parboiled2.Parser.Fail($stringTree)"
  }

  case class Named(op: OpTree, stringTree: Tree) extends DefaultNonTerminalOpTree {
    def ruleTraceNonTerminalKey = q"akka.parboiled2.RuleTrace.Named($stringTree)"
    def renderInner(wrapped: Boolean): Tree = op.render(wrapped)
  }

  /////////////////////////////////// helpers ////////////////////////////////////

  class Collector(
    val valBuilder: Tree,
    val popToBuilder: Tree,
    val pushBuilderResult: Tree,
    val pushSomePop: Tree,
    val pushNone: Tree)

  lazy val rule0Collector = {
    val unit = q"()"
    new Collector(unit, unit, q"true", unit, unit)
  }

  lazy val rule1Collector = new Collector(
    valBuilder = q"val builder = new scala.collection.immutable.VectorBuilder[Any]",
    popToBuilder = q"builder += valueStack.pop()",
    pushBuilderResult = q"valueStack.push(builder.result()); true",
    pushSomePop = q"valueStack.push(Some(valueStack.pop()))",
    pushNone = q"valueStack.push(None)")

  type Separator = Boolean ⇒ Tree

  def Separator(op: OpTree): Separator = wrapped ⇒ op.render(wrapped)

  lazy val HListConsTypeSymbol = c.mirror.staticClass("shapeless.$colon$colon")
  lazy val HNilTypeSymbol = c.mirror.staticClass("shapeless.HNil")

  // tries to match and expand the leaves of the given Tree
  def expand(tree: Tree, wrapped: Boolean): Tree =
    tree match {
      case Block(statements, res)     ⇒ block(statements, expand(res, wrapped))
      case If(cond, thenExp, elseExp) ⇒ If(cond, expand(thenExp, wrapped), expand(elseExp, wrapped))
      case Match(selector, cases)     ⇒ Match(selector, cases.map(expand(_, wrapped).asInstanceOf[CaseDef]))
      case CaseDef(pat, guard, body)  ⇒ CaseDef(pat, guard, expand(body, wrapped))
      case x                          ⇒ opTreePF.andThen(_.render(wrapped)).applyOrElse(tree, (t: Tree) ⇒ q"$t ne null")
    }

  @tailrec
  private def callName(tree: Tree): Option[String] =
    tree match {
      case Ident(name)       ⇒ Some(name.decodedName.toString)
      case Select(_, name)   ⇒ Some(name.decodedName.toString)
      case Apply(fun, _)     ⇒ callName(fun)
      case TypeApply(fun, _) ⇒ callName(fun)
      case _                 ⇒ None
    }

  def block(a: Tree, b: Tree): Tree =
    a match {
      case Block(a1, a2) ⇒ b match {
        case Block(b1, b2) ⇒ Block(a1 ::: a2 :: b1, b2)
        case _             ⇒ Block(a1 ::: a2 :: Nil, b)
      }
      case _ ⇒ b match {
        case Block(b1, b2) ⇒ Block(a :: b1, b2)
        case _             ⇒ Block(a :: Nil, b)
      }
    }

  def block(stmts: List[Tree], expr: Tree): Tree =
    expr match {
      case Block(a, b) ⇒ block(stmts ::: a ::: Nil, b)
      case _           ⇒ Block(stmts, expr)
    }

  def isSubClass(t: Type, fqn: String) = t.baseClasses.contains(c.mirror.staticClass(fqn))
}
