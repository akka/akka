package akka.makkros

import language.experimental.macros
import scala.reflect.macros.Context
import scala.tools.reflect.ToolBox
import scala.reflect.ClassTag
import scala.tools.reflect.ToolBoxError

object Test {

  def eval(code: String, compileOptions: String = "-cp akka-actor/target/classes:akka-channels/target/classes"): Any = {
    val tb = mkToolbox(compileOptions)
    tb.eval(tb.parse(code))
  }

  def mkToolbox(compileOptions: String = ""): ToolBox[_ <: scala.reflect.api.Universe] = {
    val m = scala.reflect.runtime.currentMirror
    m.mkToolBox(options = compileOptions)
  }

}