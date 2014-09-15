/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.lang.reflect.Method
import org.scalatest.Matchers
import org.scalatest.WordSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DslConsistencySpec extends WordSpec with Matchers {

  val ignore = Set("equals", "hashCode", "notify", "notifyAll", "wait", "create", "apply", "toString", "getClass",
    "ops", "appendJava")
  val allowMissing: Map[Class[_], Set[String]] = Map.empty

  def materializing(m: Method): Boolean = m.getParameterTypes.contains(classOf[FlowMaterializer])

  val sflowClass = classOf[akka.stream.scaladsl.Flow[_]]
  val sductClass = classOf[akka.stream.scaladsl.Duct[_, _]]
  val jflowClass = classOf[akka.stream.javadsl.Flow[_]]
  val jductClass = classOf[akka.stream.javadsl.Duct[_, _]]

  def assertHasMethod(c: Class[_], name: String): Unit = {
    // include class name to get better error message
    if (!allowMissing.getOrElse(c, Set.empty).contains(name))
      c.getMethods.collect { case m if !ignore(m.getName) ⇒ c.getName + "." + m.getName } should
        contain(c.getName + "." + name)
  }

  "Java and Scala DSLs" must {
    "provide same Flow and Duct transforming operators" in {
      val classes = List(sflowClass, sductClass, jflowClass, jductClass)
      val allOps =
        (for {
          c ← classes
          m ← c.getMethods
          if !ignore(m.getName)
          if !materializing(m)
        } yield m.getName).toSet

      for (c ← classes; op ← allOps)
        assertHasMethod(c, op)
    }

    "provide same Flow materializing operators" in {
      val classes = List(sflowClass, jflowClass)
      val materializingOps =
        (for {
          c ← classes
          m ← c.getMethods
          if !ignore(m.getName)
          if materializing(m)
        } yield m.getName).toSet

      for (c ← classes; op ← materializingOps)
        assertHasMethod(c, op)
    }

    "provide same Duct materializing operators" in {
      val classes = List(sductClass, jductClass)
      val materializingOps =
        (for {
          c ← classes
          m ← c.getMethods
          if !ignore(m.getName)
          if materializing(m)
        } yield m.getName).toSet

      for (c ← classes; op ← materializingOps)
        assertHasMethod(c, op)
    }
  }
}