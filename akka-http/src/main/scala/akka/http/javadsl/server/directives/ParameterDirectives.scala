/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.{ Map ⇒ JMap, List ⇒ JList }
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }

import scala.collection.JavaConverters._
import scala.compat.java8.OptionConverters._

import akka.http.javadsl.server.{ Route, Unmarshaller }
import akka.http.scaladsl.server.directives.{ ParameterDirectives ⇒ D }
import akka.http.scaladsl.server.directives.ParameterDirectives._
import akka.http.scaladsl.unmarshalling.PredefinedFromStringUnmarshallers._

abstract class ParameterDirectives extends MiscDirectives {

  def parameter(name: String, inner: java.util.function.Function[String, Route]): Route = RouteAdapter(
    D.parameter(name) { value ⇒
      inner.apply(value).delegate
    })

  @CorrespondsTo("parameter")
  def parameterOptional(name: String, inner: java.util.function.Function[Optional[String], Route]): Route = RouteAdapter(
    D.parameter(name.?) { value ⇒
      inner.apply(value.asJava).delegate
    })

  @CorrespondsTo("parameterSeq")
  def parameterList(name: String, inner: java.util.function.Function[java.util.List[String], Route]): Route = RouteAdapter(
    D.parameter(_string2NR(name).*) { values ⇒
      inner.apply(values.toSeq.asJava).delegate
    })

  def parameter[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[T, Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T]) { value ⇒
        inner.apply(value).delegate
      })
  }

  @CorrespondsTo("parameter")
  def parameterOptional[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[Optional[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T].?) { value ⇒
        inner.apply(value.asJava).delegate
      })
  }

  @CorrespondsTo("parameter")
  def parameterOrDefault[T](t: Unmarshaller[String, T], defaultValue: T, name: String, inner: java.util.function.Function[T, Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T].?(defaultValue)) { value ⇒
        inner.apply(value).delegate
      })
  }

  @CorrespondsTo("parameterSeq")
  def parameterList[T](t: Unmarshaller[String, T], name: String, inner: java.util.function.Function[java.util.List[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.parameter(name.as[T].*) { values ⇒
        inner.apply(values.toSeq.asJava).delegate
      })
  }

  def parameterMap(inner: JFunction[JMap[String, String], Route]): Route = RouteAdapter {
    D.parameterMap { map ⇒ inner.apply(map.asJava).delegate }
  }

  def parameterMultiMap(inner: JFunction[JMap[String, JList[String]], Route]): Route = RouteAdapter {
    D.parameterMultiMap { map ⇒ inner.apply(map.mapValues { l ⇒ l.asJava }.asJava).delegate }
  }

  @CorrespondsTo("parameterSeq")
  def parameterList(inner: JFunction[JList[JMap.Entry[String, String]], Route]): Route = RouteAdapter {
    D.parameterSeq { list ⇒
      val entries: Seq[JMap.Entry[String, String]] = list.map { e ⇒ new SimpleImmutableEntry(e._1, e._2) }
      inner.apply(entries.asJava).delegate
    }
  }

}
