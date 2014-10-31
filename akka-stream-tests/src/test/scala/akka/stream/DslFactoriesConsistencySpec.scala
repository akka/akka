/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import java.lang.reflect.Method

import org.scalatest.Matchers
import org.scalatest.WordSpec

class DslFactoriesConsistencySpec extends WordSpec with Matchers {

  // configuration //

  val scalaIgnore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass")

  val javaIgnore =
    Set("adapt") // the scaladsl -> javadsl bridge

  val `scala -> java aliases` =
    ("apply" → "create") ::
      ("apply" → "of") ::
      ("apply" → "from") ::
      Nil

  // format: OFF
  val `scala -> java types` =
    (classOf[scala.collection.immutable.Iterable[_]],   classOf[java.lang.Iterable[_]]) ::
      (classOf[scala.collection.Iterator[_]],           classOf[java.util.Iterator[_]]) ::
      (classOf[scala.Function0[_]],                     classOf[akka.stream.javadsl.japi.Creator[_]]) ::
      (classOf[scala.Function0[_]],                     classOf[java.util.concurrent.Callable[_]]) ::
      (classOf[scala.Function1[_, Unit]],               classOf[akka.stream.javadsl.japi.Procedure[_]]) ::
      (classOf[scala.Function1[_, _]],                  classOf[akka.stream.javadsl.japi.Function[_, _]]) ::
      (classOf[scala.Function1[_, _]],                  classOf[akka.stream.javadsl.japi.Creator[_]]) ::
      (classOf[scala.Function2[_, _, _]],               classOf[akka.stream.javadsl.japi.Function2[_, _, _]]) ::
      (classOf[akka.stream.scaladsl.Source[_]],        classOf[akka.stream.javadsl.Source[_]]) ::
      (classOf[akka.stream.scaladsl.Sink[_]],          classOf[akka.stream.javadsl.Sink[_]]) ::
      (classOf[akka.stream.scaladsl.Flow[_, _]],       classOf[akka.stream.javadsl.Flow[_, _]]) ::
      (classOf[akka.stream.scaladsl.FlowGraph],        classOf[akka.stream.javadsl.FlowGraph]) ::
      (classOf[akka.stream.scaladsl.PartialFlowGraph], classOf[akka.stream.javadsl.PartialFlowGraph]) ::
      Nil
  // format: ON

  "Java DSL" must provide {
    "Source" which {
      "allows creating the same Sources as Scala DSL" in {
        val sClass = akka.stream.scaladsl.Source.getClass
        val jClass = akka.stream.javadsl.Source.getClass

        runSpec(sClass, jClass)
      }
    }
    "Flow" which {
      "allows creating the same Sources as Scala DSL" in {
        val sClass = akka.stream.scaladsl.Flow.getClass
        val jClass = akka.stream.javadsl.Flow.getClass

        runSpec(sClass, jClass)
      }
    }
    "Sink" which {
      "allows creating the same Sources as Scala DSL" in {
        val sClass = akka.stream.scaladsl.Sink.getClass
        val jClass = akka.stream.javadsl.Sink.getClass

        runSpec(sClass, jClass)
      }
    }
  }

  // here be dragons...

  private def getJMethods(jClass: Class[_]) = jClass.getDeclaredMethods.filterNot(javaIgnore contains _.getName)
  private def getSMethods(sClass: Class[_]) = sClass.getMethods.filterNot(scalaIgnore contains _.getName)

  def runSpec(sClass: Class[_], jClass: Class[_]) {
    val jMethods = getJMethods(jClass)
    val sMethods = getSMethods(sClass)

    var warnings = 0

    val results = for {
      s ← sMethods
      j ← jMethods
      result = delegationCheck(s, j)
    } yield {
      result
    }

    for {
      row ← results.groupBy(_.s)
      matches = row._2.filter(_.matches)
    } {
      if (matches.length == 0) {
        warnings += 1
        alert("No match for " + row._1)
        row._2 foreach { m ⇒ alert(" > " + m.toString) }
      } else if (matches.length == 1) {
        info("Matched: Scala:" + row._1.getName + "(" + row._1.getParameterTypes.map(_.getName).mkString(",") + ")" +
          " == " +
          "Java:" + matches.head.j.getName + "(" + matches.head.j.getParameterTypes.map(_.getName).mkString(",") + ")")
      } else {
        warnings += 1
        alert("Multiple matches for " + row._1 + "!")
        matches foreach { m ⇒ alert(m.toString) }
      }
    }

    if (warnings > 0) {
      jMethods foreach { m ⇒ info("  java: " + m) }
      sMethods foreach { m ⇒ info(" scala: " + m) }
      fail("Warnings were issued! Fix name / type mappings or delegation code!")
    }
  }

  sealed trait MatchResult {
    def j: Method
    def s: Method
    def matches: Boolean
  }
  case class MatchFailure(s: Method, j: Method, reason: String = "") extends MatchResult { val matches = false }
  case class Match(s: Method, j: Method, reason: String = "") extends MatchResult { val matches = true }

  def delegationCheck(s: Method, j: Method): MatchResult = {
    if (nameMatch(s.getName, j.getName)) {
      if (s.getParameterTypes.length == j.getParameterTypes.length)
        if (typeMatch(s.getParameterTypes, j.getParameterTypes))
          Match(s, j)
        else
          MatchFailure(s, j, "Types of parameters don't match!")
      else
        MatchFailure(s, j, "Same name, but different number of parameters!")
    } else {
      MatchFailure(s, j, "Names don't match!")
    }
  }

  def nameMatch(scalaName: String, javaName: String): Boolean =
    (scalaName, javaName) match {
      case (s, j) if s == j                        ⇒ true
      case t if `scala -> java aliases` contains t ⇒ true
      case t                                       ⇒ false
    }

  def typeMatch(scalaParams: Array[Class[_]], javaParams: Array[Class[_]]): Boolean =
    (scalaParams.toList, javaParams.toList) match {
      case (s, j) if s == j                     ⇒ true
      case (s, j) if s.zip(j).forall(typeMatch) ⇒ true
      case _                                    ⇒ false
    }

  def typeMatch(p: (Class[_], Class[_])): Boolean =
    if (p._1 == p._2) true
    else if (`scala -> java types` contains p) true
    else false

  private def provide = afterWord("provide")

}