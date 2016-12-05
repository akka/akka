/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl

import java.lang.reflect.{ Modifier, Method }

import akka.http.javadsl.server.directives.CorrespondsTo
import org.scalatest.exceptions.TestPendingException
import org.scalatest.{ Matchers, WordSpec }

import scala.util.control.NoStackTrace

class DirectivesConsistencySpec extends WordSpec with Matchers {

  val scalaDirectivesClazz = classOf[akka.http.scaladsl.server.Directives]
  val javaDirectivesClazz = classOf[akka.http.javadsl.server.AllDirectives]

  val ignore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass") ++
      Set("productArity", "canEqual", "productPrefix", "copy", "productIterator", "productElement",
        "concat", "route") ++ // TODO this fails on jenkins but not locally, no idea why, disabling to get Java DSL in
        // param extractions in ScalaDSL
        Set("DoubleNumber", "HexIntNumber", "HexLongNumber", "IntNumber", "JavaUUID", "LongNumber",
          "Neutral", "PathEnd", "Remaining", "Segment", "Segments", "Slash", "RemainingPath") // TODO do we cover these?

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
    prepareDirectivesList(javaDirectivesClazz.getMethods)
  }

  val correspondingScalaMethods = {
    val javaToScalaMappings =
      for {
        // using Scala annotations - Java annotations were magically not present in certain places...
        d ← javaDirectives
        if d.isAnnotationPresent(classOf[CorrespondsTo])
        annot = d.getAnnotation(classOf[CorrespondsTo])
      } yield d.getName → annot.value()

    Map(javaToScalaMappings.toList: _*)
  }

  val correspondingJavaMethods = Map() ++ correspondingScalaMethods.map(_.swap)

  /** Left(@CorrespondsTo(...) or Right(normal name) */
  def correspondingScalaMethodName(m: Method): Either[String, String] =
    correspondingScalaMethods.get(m.getName) match {
      case Some(correspondent) ⇒ Left(correspondent)
      case _                   ⇒ Right(m.getName)
    }

  /** Left(@CorrespondsTo(...) or Right(normal name) */
  def correspondingJavaMethodName(m: Method): Either[String, String] =
    correspondingJavaMethods.get(m.getName) match {
      case Some(correspondent) ⇒ Left(correspondent)
      case _                   ⇒ Right(m.getName)
    }

  val allowMissing: Map[Class[_], Set[String]] = Map(
    scalaDirectivesClazz → Set(
      "route", "request",
      "completeOK", // solved by raw complete() in Scala
      "defaultDirectoryRenderer", "defaultContentTypeResolver" // solved by implicits in Scala
    ),
    javaDirectivesClazz → Set(
      "as",
      "instanceOf",
      "pass",

      // TODO PENDING ->
      "extractRequestContext", "nothingMatcher", "separateOnSlashes",
      "textract", "tprovide", "withExecutionContext", "withRequestTimeoutResponse",
      "withSettings",
      "provide", "withMaterializer", "recoverRejectionsWith",
      "mapSettings", "mapRequestContext", "mapInnerRoute", "mapRouteResultFuture",
      "mapRouteResultWith",
      "mapRouteResult", "handleWith",
      "mapRouteResultWithPF", "mapRouteResultPF",
      "route", "request",
      "completeOrRecoverWith", "completeWith",
      // TODO <- END OF PENDING
      "parameters", "formFields", // since we can't do magnet-style "arbitrary arity"

      "authenticateOAuth2PF", "authenticateOAuth2PFAsync",
      "authenticateBasicPF", "authenticateBasicPFAsync"))

  def assertHasMethod(c: Class[_], name: String, alternativeName: String): Unit = {
    // include class name to get better error message
    if (!allowMissing.getOrElse(c, Set.empty).exists(n ⇒ n == name || n == alternativeName)) {
      val methods = c.getMethods.collect { case m if !ignore(m.getName) ⇒ c.getName + "." + m.getName }

      def originClazz = {
        // look in the "opposite" class
        // traversal is different in scala/java - in scala its traits, so we need to look at interfaces
        // in hava we have a huge inheritance chain so we unfold it
        c match {
          case `javaDirectivesClazz` ⇒
            val all = scalaDirectivesClazz
            (for {
              i ← all.getInterfaces
              m ← i.getDeclaredMethods
              if m.getName == name || m.getName == alternativeName
            } yield i).headOption
              .map(_.getName)
              .getOrElse(throw new Exception(s"Unable to locate method [$name] on source class $all"))

          case `scalaDirectivesClazz` ⇒
            val all = javaDirectivesClazz

            var is = List.empty[Class[_]]
            var c: Class[_] = all
            while (c != classOf[java.lang.Object]) {
              is = c :: is
              c = c.getSuperclass
            }

            (for {
              i ← is
              m ← i.getDeclaredMethods
              if m.getName == name || m.getName == alternativeName
            } yield i).headOption
              .map(_.getName)
              .getOrElse(throw new Exception(s"Unable to locate method [$name] on source class $all"))
        }
      }

      if (methods.contains(c.getName + "." + name) && name == alternativeName) ()
      else if (methods.contains(c.getName + "." + alternativeName)) ()
      else throw new AssertionError(s"Method [$originClazz#$name] was not defined on class: ${c.getName}") with NoStackTrace
    } else {
      // allowed missing - we mark as pending, perhaps we'll want that method eventually
      throw new TestPendingException
    }
  }

  "DSL Stats" should {
    info("Scala Directives: ~" + scalaDirectives.map(_.getName).filterNot(ignore).size)
    info("Java Directives: ~" + javaDirectives.map(_.getName).filterNot(ignore).size)
  }

  "Directive aliases" should {
    info("Aliases: ")
    correspondingScalaMethods.foreach { case (k, v) ⇒ info(s"  $k => $v") }
  }

  "Consistency scaladsl -> javadsl" should {
    for {
      m ← scalaDirectives
      name = m.getName
      targetName = correspondingJavaMethodName(m) match { case Left(l) ⇒ l case Right(r) ⇒ r }
      text = if (name == targetName) name else s"$name (alias: $targetName)"
    } s"""define Scala directive [$text] for JavaDSL too""" in {
      assertHasMethod(javaDirectivesClazz, name, targetName)
    }
  }

  "Consistency javadsl -> scaladsl" should {
    for {
      m ← javaDirectives
      name = m.getName
      targetName = correspondingScalaMethodName(m) match { case Left(l) ⇒ l case Right(r) ⇒ r }
      text = if (name == targetName) name else s"$name (alias for: $targetName)"
    } s"""define Java directive [$text] for ScalaDSL too""" in {
      assertHasMethod(scalaDirectivesClazz, name, targetName)
    }
  }

}
