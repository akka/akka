/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.Matchers
import org.scalatest.WordSpec

class DslFactoriesConsistencySpec extends WordSpec with Matchers {

  // configuration //

  val scalaIgnore =
    Set("equals", "hashCode", "notify", "notifyAll", "wait", "toString", "getClass", "shape")

  val javaIgnore =
    Set("adapt") // the scaladsl -> javadsl bridge

  val `scala -> java aliases` =
    ("apply" → "create") ::
      ("apply" → "of") ::
      ("apply" → "from") ::
      ("apply" -> "fromGraph") ::
      ("apply" -> "fromIterator") ::
      Nil

  // format: OFF
  val `scala -> java types` =
    (classOf[scala.collection.immutable.Iterable[_]],   classOf[java.lang.Iterable[_]]) ::
      (classOf[scala.collection.Iterator[_]],           classOf[java.util.Iterator[_]]) ::
      (classOf[scala.Function0[_]],                     classOf[akka.japi.function.Creator[_]]) ::
      (classOf[scala.Function0[_]],                     classOf[java.util.concurrent.Callable[_]]) ::
      (classOf[scala.Function0[_]],                     classOf[akka.japi.function.Creator[_]]) ::
      (classOf[scala.Function1[_, Unit]],               classOf[akka.japi.function.Procedure[_]]) ::
      (classOf[scala.Function1[_, _]],                  classOf[akka.japi.function.Function[_, _]]) ::
      (classOf[akka.stream.scaladsl.Source[_, _]],      classOf[akka.stream.javadsl.Source[_, _]]) ::
      (classOf[akka.stream.scaladsl.Sink[_, _]],        classOf[akka.stream.javadsl.Sink[_, _]]) ::
      (classOf[akka.stream.scaladsl.Flow[_, _, _]],     classOf[akka.stream.javadsl.Flow[_, _, _]]) ::
      (classOf[akka.stream.scaladsl.RunnableGraph[_]],  classOf[akka.stream.javadsl.RunnableGraph[_]]) ::
      ((2 to 22) map { i => (Class.forName(s"scala.Function$i"), Class.forName(s"akka.japi.function.Function$i")) }).toList
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
        val sClass = akka.stream.scaladsl.Source.getClass
        val jClass = akka.stream.javadsl.Source.getClass
        val jFactory = classOf[akka.stream.javadsl.SourceCreate]

        runSpec(getSMethods(sClass), getJMethods(jClass) ++ getJMethods(jFactory).map(adaptCreate))
      }
    }
    "Flow" which {
      "allows creating the same Sources as Scala DSL" in {
        val sClass = akka.stream.scaladsl.Flow.getClass
        val jClass = akka.stream.javadsl.Flow.getClass
        val jFactory = classOf[akka.stream.javadsl.FlowCreate]

        runSpec(getSMethods(sClass), getJMethods(jClass) ++ getJMethods(jFactory).map(adaptCreate))
      }
    }
    "Sink" which {
      "allows creating the same Sources as Scala DSL" in {
        val sClass = akka.stream.scaladsl.Sink.getClass
        val jClass = akka.stream.javadsl.Sink.getClass
        val jFactory = classOf[akka.stream.javadsl.SinkCreate]

        runSpec(getSMethods(sClass), getJMethods(jClass) ++ getJMethods(jFactory).map(adaptCreate))
      }
    }
  }

  // here be dragons...

  private def getJMethods(jClass: Class[_]): Array[Method] = jClass.getDeclaredMethods.filterNot(javaIgnore contains _.getName).map(toMethod).filterNot(ignore)
  private def getSMethods(sClass: Class[_]): Array[Method] = sClass.getMethods.filterNot(scalaIgnore contains _.getName).map(toMethod).filterNot(ignore)

  private def toMethod(m: java.lang.reflect.Method): Method =
    Method(m.getName, List(m.getParameterTypes: _*), m.getReturnType, m.getDeclaringClass)

  private case class Ignore(cls: Class[_] ⇒ Boolean, name: String ⇒ Boolean, parameters: Int ⇒ Boolean, paramTypes: List[Class[_]] ⇒ Boolean)

  private def ignore(m: Method): Boolean = {
    val ignores = Seq(
      // private scaladsl method
      Ignore(_ == akka.stream.scaladsl.Source.getClass, _ == "apply", _ == 1, _ == List(classOf[akka.stream.impl.SourceModule[_, _]])),
      // corresponding matches on java side would need to have Function23
      Ignore(_ == akka.stream.scaladsl.Source.getClass, _ == "apply", _ == 24, _ ⇒ true),
      Ignore(_ == akka.stream.scaladsl.Flow.getClass, _ == "apply", _ == 24, _ ⇒ true),
      Ignore(_ == akka.stream.scaladsl.Sink.getClass, _ == "apply", _ == 24, _ ⇒ true),
      // all generated methods like scaladsl.Sink$.akka$stream$scaladsl$Sink$$newOnCompleteStage$1
      Ignore(_ ⇒ true, _.contains("$"), _ ⇒ true, _ ⇒ true))

    ignores.foldLeft(false) {
      case (acc, i) ⇒
        acc || (i.cls(m.declaringClass) && i.name(m.name) && i.parameters(m.parameterTypes.length) && i.paramTypes(m.parameterTypes))
    }
  }

  private val adaptCreate: PartialFunction[Method, Method] = {
    case m if m.parameterTypes.size > 1 ⇒
      // rename createN => create
      // and adapt java side non curried functions to scala side like
      m.copy(name = "create", parameterTypes = m.parameterTypes.dropRight(1) :+ classOf[akka.japi.function.Function[_, _]])
    case m ⇒ m
  }

  def runSpec(sMethods: Array[Method], jMethods: Array[Method]) {
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
        row._2 foreach { m ⇒ alert(s" > ${m.j.toString}: ${m.reason}") }
      } else if (matches.length == 1) {
        info("Matched: Scala:" + row._1.name + "(" + row._1.parameterTypes.map(_.getName).mkString(",") + "): " + returnTypeString(row._1) +
          " == " +
          "Java:" + matches.head.j.name + "(" + matches.head.j.parameterTypes.map(_.getName).mkString(",") + "): " + returnTypeString(matches.head.j))
      } else {
        warnings += 1
        alert("Multiple matches for " + row._1 + "!")
        matches foreach { m ⇒ alert(s" > ${m.j.toString}") }
      }
    }

    if (warnings > 0) {
      jMethods foreach { m ⇒ info("  java: " + m + ": " + returnTypeString(m)) }
      sMethods foreach { m ⇒ info(" scala: " + m + ": " + returnTypeString(m)) }
      fail("Warnings were issued! Fix name / type mappings or delegation code!")
    }
  }

  def returnTypeString(m: Method): String =
    m.returnType.getName.drop("akka.stream.".length)

  case class Method(name: String, parameterTypes: List[Class[_]], returnType: Class[_], declaringClass: Class[_])

  sealed trait MatchResult {
    def j: Method
    def s: Method
    def reason: String
    def matches: Boolean
  }
  case class MatchFailure(s: Method, j: Method, reason: String = "") extends MatchResult { val matches = false }
  case class Match(s: Method, j: Method, reason: String = "") extends MatchResult { val matches = true }

  def delegationCheck(s: Method, j: Method): MatchResult = {
    if (nameMatch(s.name, j.name)) {
      if (s.parameterTypes.length == j.parameterTypes.length)
        if (typeMatch(s.parameterTypes, j.parameterTypes))
          if (returnTypeMatch(s.returnType, j.returnType))
            Match(s, j)
          else
            MatchFailure(s, j, "Return types don't match! " + s.returnType + ", " + j.returnType)
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

  def typeMatch(scalaParams: List[Class[_]], javaParams: List[Class[_]]): Boolean =
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
