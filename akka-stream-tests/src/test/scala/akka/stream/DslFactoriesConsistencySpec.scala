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
      ("apply" -> "fromGraph") ::
      Nil

  // format: OFF
  val `scala -> java types` =
    (classOf[scala.collection.immutable.Iterable[_]],   classOf[java.lang.Iterable[_]]) ::
      (classOf[scala.collection.Iterator[_]],           classOf[java.util.Iterator[_]]) ::
      (classOf[scala.Function0[_]],                     classOf[akka.japi.function.Creator[_]]) ::
      (classOf[scala.Function0[_]],                     classOf[java.util.concurrent.Callable[_]]) ::
      (classOf[scala.Function1[_, Unit]],               classOf[akka.japi.function.Procedure[_]]) ::
      (classOf[scala.Function1[_, _]],                  classOf[akka.japi.function.Function[_, _]]) ::
      (classOf[scala.Function1[_, _]],                  classOf[akka.japi.function.Creator[_]]) ::
      (classOf[scala.Function2[_, _, _]],               classOf[akka.japi.function.Function2[_, _, _]]) ::
      (classOf[akka.stream.scaladsl.Source[_, _]],      classOf[akka.stream.javadsl.Source[_, _]]) ::
      (classOf[akka.stream.scaladsl.Sink[_, _]],        classOf[akka.stream.javadsl.Sink[_, _]]) ::
      (classOf[akka.stream.scaladsl.Flow[_, _, _]],     classOf[akka.stream.javadsl.Flow[_, _, _]]) ::
      (classOf[akka.stream.scaladsl.RunnableFlow[_]],   classOf[akka.stream.javadsl.RunnableFlow[_]]) ::
      Nil
  // format: ON

  val sSource = classOf[scaladsl.Source[_, _]]
  val jSource = classOf[javadsl.Source[_, _]]

  val sSink = classOf[scaladsl.Sink[_, _]]
  val jSink = classOf[javadsl.Sink[_, _]]

  val sFlow = classOf[scaladsl.Flow[_, _, _]]
  val jFlow = classOf[javadsl.Flow[_, _, _]]

  "Java DSL" must provide {
    "Source" which {
      "allows creating the same Sources as Scala DSL" in {
        pending
        val sClass = akka.stream.scaladsl.Source.getClass
        val jClass = akka.stream.javadsl.Source.getClass

        runSpec(sClass, jClass)
      }
    }
    "Flow" which {
      "allows creating the same Sources as Scala DSL" in {
        pending
        val sClass = akka.stream.scaladsl.Flow.getClass
        val jClass = akka.stream.javadsl.Flow.getClass

        runSpec(sClass, jClass)
      }
    }
    "Sink" which {
      "allows creating the same Sources as Scala DSL" in {
        pending
        val sClass = akka.stream.scaladsl.Sink.getClass
        val jClass = akka.stream.javadsl.Sink.getClass

        runSpec(sClass, jClass)
      }
    }
  }

  // here be dragons...

  private def getJMethods(jClass: Class[_]): Array[Method] = jClass.getDeclaredMethods.filterNot(javaIgnore contains _.getName).filter(include)
  private def getSMethods(sClass: Class[_]): Array[Method] = sClass.getMethods.filterNot(scalaIgnore contains _.getName).filter(include)

  private def include(m: Method): Boolean = {
    if (m.getDeclaringClass == akka.stream.scaladsl.Source.getClass
      && m.getName == "apply"
      && m.getParameterTypes.length == 1
      && m.getParameterTypes()(0) == classOf[scala.Function1[_, _]])
      false // conflict between two Source.apply(Function1)
    else
      true
  }

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
        info("Matched: Scala:" + row._1.getName + "(" + row._1.getParameterTypes.map(_.getName).mkString(",") + "): " + returnTypeString(row._1) +
          " == " +
          "Java:" + matches.head.j.getName + "(" + matches.head.j.getParameterTypes.map(_.getName).mkString(",") + "): " + returnTypeString(matches.head.j))
      } else {
        warnings += 1
        alert("Multiple matches for " + row._1 + "!")
        matches foreach { m ⇒ alert(m.toString) }
      }
    }

    if (warnings > 0) {
      jMethods foreach { m ⇒ info("  java: " + m + ": " + returnTypeString(m)) }
      sMethods foreach { m ⇒ info(" scala: " + m + ": " + returnTypeString(m)) }
      fail("Warnings were issued! Fix name / type mappings or delegation code!")
    }
  }

  def returnTypeString(m: Method): String =
    m.getReturnType.getName.drop("akka.stream.".length)

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
          if (returnTypeMatch(s.getReturnType, j.getReturnType))
            Match(s, j)
          else
            MatchFailure(s, j, "Return types don't match! " + s.getReturnType + ", " + j.getReturnType)
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

  /**
   * Keyed elements in scaladsl must also be keyed elements in javadsl.
   * If scaladsl is not a keyed type, javadsl shouldn't be as well.
   */
  def returnTypeMatch(s: Class[_], j: Class[_]): Boolean =
    (sSource.isAssignableFrom(s) && jSource.isAssignableFrom(j)) ||
      (sSink.isAssignableFrom(s) && jSink.isAssignableFrom(j)) ||
      (sFlow.isAssignableFrom(s) && jFlow.isAssignableFrom(j))

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
