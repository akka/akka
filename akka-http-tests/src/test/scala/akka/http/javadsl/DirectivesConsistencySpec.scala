/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import java.lang.reflect.{ Modifier, Method }

import akka.http.javadsl.server.directives.CorrespondsTo
import org.scalatest.{ Matchers, WordSpec }

class DirectivesConsistencySpec extends WordSpec with Matchers {

  val scalaDirectivesClazz = classOf[akka.http.scaladsl.server.Directives]
  val javaDirectivesClazz = classOf[akka.http.javadsl.server.AllDirectives]

  val ignore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass") ++
      Set("productArity", "canEqual", "productPrefix", "copy", "productIterator", "productElement") ++
      // param extractions in ScalaDSL
      Set("DoubleNumber", "HexIntNumber", "HexLongNumber", "IntNumber", "JavaUUID", "LongNumber",
        "Neutral", "PathEnd") // TODO do we cover these?

  def prepareDirectivesList(in: Array[Method]): List[Method] = {
    in.toSet[Method]
      .toList
      .foldLeft[List[Method]](Nil) {
        (l, s) ⇒
          {
            val test = l find { _.getName.toLowerCase == s.getName.toLowerCase }
            if (test.isEmpty) s :: l else l
          }
      }
      .sortBy(_.getName)
      .iterator
      .filterNot(m ⇒ Modifier.isStatic(m.getModifiers))
      .filterNot(m ⇒ ignore(m.getName))
      .filterNot(m ⇒ m.getName.contains("$"))
      .filterNot(m ⇒ m.getName.startsWith("_"))
      .toList
  }

  val scalaDirectives = {
    prepareDirectivesList(scalaDirectivesClazz.getMethods)
  }
  val javaDirectives = {
    prepareDirectivesList(scalaDirectivesClazz.getMethods)
  }

  val correspondingScalaMethods = {
    val javaToScalaMappings =
      for {
        d ← javaDirectives
        if d.isAnnotationPresent(classOf[CorrespondsTo])
        correspondingScalaMethod = d.getAnnotation(classOf[CorrespondsTo]).value()
      } yield d.getName -> correspondingScalaMethod

    Map(javaToScalaMappings: _*)
  }

  val correspondingJavaMethods = Map() ++ correspondingScalaMethods.map(_.swap)

  def correspondingScalaMethodName(m: Method): String =
    correspondingScalaMethods.get(m.getName) match {
      case Some(correspondent) ⇒ correspondent
      case _                   ⇒ m.getName
    }

  def correspondingJavaMethodName(m: Method): String =
    correspondingJavaMethods.get(m.getName) match {
      case Some(correspondent) ⇒ correspondent
      case _                   ⇒ m.getName
    }

  val allowMissing: Map[Class[_], Set[String]] = Map(
    scalaDirectivesClazz -> Set(),
    javaDirectivesClazz -> Set())

  def assertHasMethod(c: Class[_], name: String): Unit = {
    // include class name to get better error message
    if (!allowMissing.getOrElse(c, Set.empty).contains(name))
      c.getMethods.collect { case m if !ignore(m.getName) ⇒ c.getName + "." + m.getName } should contain(c.getName + "." + name)
  }

  "DSL Stats" should {
    info("Scala Directives: ~" + scalaDirectives.map(_.getName).filterNot(ignore).size)
    info("Java Directives: ~" + javaDirectives.map(_.getName).filterNot(ignore).size)
  }

  "Consistency scaladsl -> javadsl" should {
    for {
      m ← scalaDirectives
      name = m.getName
      targetName = correspondingJavaMethodName(m)
      text = if (name == targetName) name else s"$name (alias: $targetName)"
    } s"""define Scala directive [$text] for JavaDSL too""" in {
      assertHasMethod(javaDirectivesClazz, name)
    }
  }

  "Consistency javadsl -> scaladsl" should {
    for {
      m ← javaDirectives
      name = m.getName
      targetName = correspondingScalaMethodName(m)
      text = if (name == targetName) name else s"$name (alias: $targetName)"
    } s"""define Java directive [$text] for ScalaDSL too""" in {
      assertHasMethod(scalaDirectivesClazz, name)
    }
  }

}
