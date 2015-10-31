/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import org.scalatest.Matchers
import org.scalatest.WordSpec

class DslConsistencySpec extends WordSpec with Matchers {

  val sFlowClass = classOf[akka.stream.scaladsl.Flow[_, _, _]]
  val jFlowClass = classOf[akka.stream.javadsl.Flow[_, _, _]]

  val sSourceClass = classOf[akka.stream.scaladsl.Source[_, _]]
  val jSourceClass = classOf[akka.stream.javadsl.Source[_, _]]

  val sSinkClass = classOf[akka.stream.scaladsl.Sink[_, _]]
  val jSinkClass = classOf[akka.stream.javadsl.Sink[_, _]]

  val jRunnableGraphClass = classOf[akka.stream.javadsl.RunnableGraph[_]]
  val sRunnableGraphClass = classOf[akka.stream.scaladsl.RunnableGraph[_]]

  val ignore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass") ++
      Set("productArity", "canEqual", "productPrefix", "copy", "productIterator", "productElement") ++
      Set("create", "apply", "ops", "appendJava", "andThen", "andThenMat", "isIdentity", "withAttributes", "transformMaterializing") ++
      Set("asScala", "asJava", "deprecatedAndThen", "deprecatedAndThenMat")

  val allowMissing: Map[Class[_], Set[String]] = Map(
    sFlowClass -> Set("of"),
    sSourceClass -> Set("adapt", "from"),
    sSinkClass -> Set("adapt"),

    jSinkClass -> Set(),

    sRunnableGraphClass -> Set("builder"))

  def materializing(m: Method): Boolean = m.getParameterTypes.contains(classOf[ActorMaterializer])

  def assertHasMethod(c: Class[_], name: String): Unit = {
    // include class name to get better error message
    if (!allowMissing.getOrElse(c, Set.empty).contains(name))
      c.getMethods.collect { case m if !ignore(m.getName) ⇒ c.getName + "." + m.getName } should contain(c.getName + "." + name)
  }

  "Java and Scala DSLs" must {

    ("Source" -> List(sSourceClass, jSourceClass)) ::
      ("Flow" -> List(sFlowClass, jFlowClass)) ::
      ("Sink" -> List(sSinkClass, jSinkClass)) ::
      ("RunanbleFlow" -> List(sRunnableGraphClass, jRunnableGraphClass)) ::
      Nil foreach {
        case (element, classes) ⇒

          s"provide same $element transforming operators" in {
            val allOps =
              (for {
                c ← classes
                m ← c.getMethods
                if !Modifier.isStatic(m.getModifiers)
                if !ignore(m.getName)
                if !m.getName.contains("$")
                if !materializing(m)
              } yield m.getName).toSet

            for (c ← classes; op ← allOps)
              assertHasMethod(c, op)
          }

          s"provide same $element materializing operators" in {
            val materializingOps =
              (for {
                c ← classes
                m ← c.getMethods
                if !Modifier.isStatic(m.getModifiers)
                if !ignore(m.getName)
                if !m.getName.contains("$")
                if materializing(m)
              } yield m.getName).toSet

            for (c ← classes; op ← materializingOps)
              assertHasMethod(c, op)
          }

      }
  }

}
