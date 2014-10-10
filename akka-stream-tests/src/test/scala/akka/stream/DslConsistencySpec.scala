/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.lang.reflect.Method
import org.scalatest.Matchers
import org.scalatest.WordSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DslConsistencySpec extends WordSpec with Matchers {

  val sFlowClass = classOf[akka.stream.scaladsl2.Flow[_, _]]
  val jFlowClass = classOf[akka.stream.javadsl.Flow[_, _]]

  val sSourceClass = classOf[akka.stream.scaladsl2.Source[_]]
  val jSourceClass = classOf[akka.stream.javadsl.Source[_]]

  val sSinkClass = classOf[akka.stream.scaladsl2.Sink[_]]
  val jSinkClass = classOf[akka.stream.javadsl.Sink[_]]

  val jFlowGraphClass = classOf[akka.stream.javadsl.FlowGraph]
  val sFlowGraphClass = classOf[akka.stream.scaladsl2.FlowGraph]

  val jPartialFlowGraphClass = classOf[akka.stream.javadsl.PartialFlowGraph]
  val sPartialFlowGraphClass = classOf[akka.stream.scaladsl2.PartialFlowGraph]

  val ignore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass") ++
      Set("create", "apply", "ops", "appendJava", "andThen") ++
      Seq("asScala", "asJava")

  val allowMissing: Map[Class[_], Set[String]] = Map(
    sFlowClass -> Set("of"),
    sSourceClass -> Set("adapt", "from"),
    sSinkClass -> Set("adapt"),
    sFlowGraphClass -> Set("builder"),
    jFlowGraphClass → Set("graph"),
    jPartialFlowGraphClass → Set("graph"))

  def materializing(m: Method): Boolean = m.getParameterTypes.contains(classOf[FlowMaterializer])

  def assertHasMethod(c: Class[_], name: String): Unit = {
    // include class name to get better error message
    if (!allowMissing.getOrElse(c, Set.empty).contains(name))
      c.getMethods.collect { case m if !ignore(m.getName) ⇒ c.getName + "." + m.getName } should contain(c.getName + "." + name)
  }

  "Java and Scala DSLs" must {

    ("Source" -> List(sSourceClass, jSourceClass)) ::
      ("Flow" -> List(sFlowClass, jFlowClass)) ::
      ("Sink" -> List(sSinkClass, jSinkClass)) ::
      ("FlowGraph" -> List(sFlowGraphClass, jFlowGraphClass)) ::
      ("PartialFlowGraph" -> List(sPartialFlowGraphClass, jPartialFlowGraphClass)) ::
      Nil foreach {
        case (element, classes) ⇒

          s"provide same $element transforming operators" in {
            val allOps =
              (for {
                c ← classes
                m ← c.getMethods
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