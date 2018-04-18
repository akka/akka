/**
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import java.io.File
import java.lang.reflect.{ Method, Modifier }
import java.nio.file.{ Path, Paths }

import akka.event.Logging
import org.scalatest.{ Matchers, WordSpec }

class DocsStageCoverageSpec extends WordSpec with Matchers {

  val sFlowClass: Class[_] = classOf[akka.stream.scaladsl.Flow[_, _, _]]
  val jFlowClass: Class[_] = classOf[akka.stream.javadsl.Flow[_, _, _]]

  val sSubFlowClass: Class[_] = classOf[DslConsistencySpec.ScalaSubFlow[_, _, _]]
  val jSubFlowClass: Class[_] = classOf[akka.stream.javadsl.SubFlow[_, _, _]]

  val sSourceClass: Class[_] = classOf[akka.stream.scaladsl.Source[_, _]]
  val jSourceClass: Class[_] = classOf[akka.stream.javadsl.Source[_, _]]

  val sSubSourceClass: Class[_] = classOf[DslConsistencySpec.ScalaSubSource[_, _]]
  val jSubSourceClass: Class[_] = classOf[akka.stream.javadsl.SubSource[_, _]]

  val sSinkClass: Class[_] = classOf[akka.stream.scaladsl.Sink[_, _]]
  val jSinkClass: Class[_] = classOf[akka.stream.javadsl.Sink[_, _]]

  val jRunnableGraphClass: Class[_] = classOf[akka.stream.javadsl.RunnableGraph[_]]
  val sRunnableGraphClass: Class[_] = classOf[akka.stream.scaladsl.RunnableGraph[_]]

  val ignore: Set[String] =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass") ++
      Set("productArity", "canEqual", "productPrefix", "copy", "productIterator", "productElement") ++
      Set("create", "apply", "ops", "appendJava", "andThen", "andThenMat", "isIdentity", "withAttributes", "transformMaterializing") ++
      Set("asScala", "asJava", "deprecatedAndThen", "deprecatedAndThenMat")

  val graphHelpers = Set("zipGraph", "zipWithGraph", "mergeGraph", "mergeSortedGraph", "interleaveGraph", "concatGraph", "prependGraph", "alsoToGraph", "wireTapGraph", "orElseGraph", "divertToGraph")

  val allowMissing: Map[Class[_], Set[String]] = Map(
    jFlowClass → graphHelpers,
    jSourceClass → (graphHelpers ++ Set("watch", "ask")),
    // Java subflows can only be nested using .via and .to (due to type system restrictions)
    jSubFlowClass → (graphHelpers ++ Set("groupBy", "splitAfter", "splitWhen", "subFlow", "watch", "ask")),
    jSubSourceClass → (graphHelpers ++ Set("groupBy", "splitAfter", "splitWhen", "subFlow", "watch", "ask")),

    sFlowClass → Set("of"),
    sSourceClass → Set("adapt", "from", "watch"),
    sSinkClass → Set("adapt"),
    sSubFlowClass → Set(),
    sSubSourceClass → Set(),

    sRunnableGraphClass → Set("builder"))

  def materializing(m: Method): Boolean = m.getParameterTypes.contains(classOf[ActorMaterializer])

  val DocsRoot = Paths.get("akka-docs/src/main/paradox/stream")

  def assertPageExists(c: Class[_], name: String): Unit = {
    val operatorDocFile: Path = operatorDocFilePath(c, name)
    assert(operatorDocFile.toFile.exists(), s"Expected [$operatorDocFile] to exist and document [$name] operator on [$c]")
  }

  private def operatorDocFilePath(c: Class[_], name: String): Path =
    DocsRoot.resolve(Logging.simpleName(c)).resolve(name + ".md")

  "Java and Scala DSLs" must {

    ("Source" → List[Class[_]](sSourceClass, jSourceClass)) ::
      //      ("SubSource" → List[Class[_]](sSubSourceClass, jSubSourceClass)) ::
      ("Flow" → List[Class[_]](sFlowClass, jFlowClass)) ::
      //      ("SubFlow" → List[Class[_]](sSubFlowClass, jSubFlowClass)) ::
      ("Sink" → List[Class[_]](sSinkClass, jSinkClass)) ::
      ("RunnableFlow" → List[Class[_]](sRunnableGraphClass, jRunnableGraphClass)) ::
      Nil foreach {
        case (element, classes) ⇒

          val allOps =
            (for {
              c ← classes
              m ← c.getMethods
              if !Modifier.isStatic(m.getModifiers)
              if !ignore(m.getName)
              if !m.getName.contains("$")
              if !materializing(m)
            } yield m.getName).toSet

          allOps foreach { opName ⇒
            s"document [$element] operation [$opName]" in {
              assertPageExists(classes.head, opName)
            }
          }
      }
  }
}
