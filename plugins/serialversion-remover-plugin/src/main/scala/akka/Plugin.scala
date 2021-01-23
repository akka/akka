/*
 * Copyright (C) 2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import dotty.tools.dotc.ast.Trees._
import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.core.Constants.Constant
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.plugins.{ PluginPhase, StandardPlugin }
import dotty.tools.backend.jvm.GenBCode
import dotty.tools.dotc.typer.FrontEnd

// import dotty.tools.dotc.core.NameKinds._
// import dotty.tools.dotc.core.Types._
// import dotty.tools.dotc.core.Contexts._
// import dotty.tools.dotc.util.Spans._
// import dotty.tools.dotc.report
// import dotty.tools.dotc.transform.SymUtils._

class SerialVersionRemoverPlugin extends StandardPlugin {

  val name = "serialversion-remover-plugin"
  val description = "Remove SerialVersionUid annotation from traits"

  def init(options: List[String]): List[PluginPhase] = {
    (new SerialVersionRemoverPhase()) :: Nil
  }
}

import dotty.tools.backend.jvm.BCodeHelpers
import dotty.tools.backend.jvm.DottyBackendInterface
import scala.collection.{ mutable, immutable }
import scala.annotation.switch

import scala.tools.asm
import scala.tools.asm.util.{TraceMethodVisitor, ASMifier}
import java.io.PrintWriter

import dotty.tools.dotc.ast.tpd
import dotty.tools.dotc.ast.TreeTypeMap
import dotty.tools.dotc.CompilationUnit
import dotty.tools.dotc.core.Annotations.Annotation
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.StdNames._
import dotty.tools.dotc.core.NameKinds._
import dotty.tools.dotc.core.Names.TermName
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.core.Types._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.util.Spans._
import dotty.tools.dotc.report
import dotty.tools.dotc.transform.SymUtils._


class SerialVersionRemoverPhase extends PluginPhase {
  import tpd._

  // import int.{_, given}
  // import DottyBackendInterface.{symExtensions, _}
  // // import tpd._
  // import bTypes._
  // import coreBTypes._
  // import bCodeAsmCommon._

  val phaseName = "serialversion-remover"

  // override val runsAfter = Set(FrontEnd.name)
  override val runsBefore = Set(GenBCode.name)

  override def transformApply(tree: Apply)(implicit ctx: Context): Tree = {
    // println("SericalVersionRemover!!!!")
    // println(tree)
     //    throw new Exception("stop")
    // System.exit(-1)
    tree match {
      case Apply(Select(x, name), _) if x.toString.contains("Trait") => {//if /*x.symbol.getAnnotation(defn.SerialVersionUIDAnnot).isDefined &&*/ x.symbol.is(Trait) => {
        println("from here?")
        println(name)
        println(x)
        // System.exit(1)
        throw new Exception("stop")
        x
      }
      case _ => ()
    }
    tree
  }
}
