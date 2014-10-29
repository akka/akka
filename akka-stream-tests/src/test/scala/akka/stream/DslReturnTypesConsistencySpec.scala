/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.Matchers
import org.scalatest.WordSpec

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DslReturnTypesConsistencySpec extends WordSpec with Matchers {

  // configuration //

  val scalaIgnore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass")

  val javaIgnore =
    Set("adapt") // the scaladsl -> javadsl bridge

  val legalScalaSourceReturnTypes =
    classOf[akka.stream.scaladsl.Source[_]] ::
      classOf[akka.stream.scaladsl.KeyedSource[_]] ::
      Nil
  val legalJavaSourceReturnTypes =
    classOf[akka.stream.javadsl.Source[_]] ::
      classOf[akka.stream.javadsl.KeyedSource[_, _]] ::
      Nil

  val legalScalaSinkReturnTypes =
    classOf[akka.stream.scaladsl.Sink[_]] ::
      classOf[akka.stream.scaladsl.KeyedSink[_]] ::
      Nil
  val legalJavaSinkReturnTypes =
    classOf[akka.stream.javadsl.Sink[_]] ::
      classOf[akka.stream.javadsl.KeyedSink[_, _]] ::
      Nil

  val sSourceClazz = akka.stream.scaladsl.Source.getClass
  val jSourceClazz = akka.stream.javadsl.Source.getClass
  val sSinkClazz = akka.stream.scaladsl.Sink.getClass
  val jSinkClazz = akka.stream.javadsl.Sink.getClass

  "Scala DSL" must {
    s"not expose internal classes through `${sSourceClazz.getSimpleName}.createSomething()` methods" in {
      runSpec(legalScalaSourceReturnTypes, sSourceClazz)
    }

    s"not expose internal classes through `${sSinkClazz.getSimpleName}.createSomething()` methods" in {
      runSpec(legalScalaSinkReturnTypes, sSinkClazz)
    }
  }

  "Java DSL" must {
    s"not expose internal classes through `${jSourceClazz.getSimpleName}.createSomething()` methods" in {
      runSpec(legalJavaSourceReturnTypes, jSourceClazz)
    }

    s"not expose internal classes through `${jSinkClazz.getSimpleName}.createSomething()` methods" in {
      runSpec(legalJavaSinkReturnTypes, jSinkClazz)
    }
  }

  // here be dragons...

  def runSpec(legal: List[Class[_]], clazz: Class[_]) {
    var warnings = 0
    for {
      method ← getSMethods(clazz)
      ret = method.getReturnType
      if !legal.contains(ret)
    } {
      alert(s"Invalid return type: ${ret}, on method: " + method)
      warnings += 1
    }

    require(warnings == 0, s"Found invalid return types on ${clazz.getSimpleName} factory methods! Refer to warnings to see which exctly.")
  }

  private def getJMethods(jClass: Class[_]) = jClass.getDeclaredMethods.filterNot(javaIgnore contains _.getName)
  private def getSMethods(sClass: Class[_]) = sClass.getMethods.filterNot(scalaIgnore contains _.getName)

}