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

import akka.http.javadsl.server.JavaScalaTypeEquivalence._
import akka.http.javadsl.server.Route
import akka.http.javadsl.server.Unmarshaller

import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.http.scaladsl.server.directives.ParameterDirectives._

abstract class FormFieldDirectives extends FileUploadDirectives {

  def formField(name: String, inner: JFunction[String, Route]): Route = ScalaRoute(
    D.formField(name) { value ⇒
      inner.apply(value).toScala
    })

  def formFieldOptional(name: String, inner: JFunction[Optional[String], Route]): Route = ScalaRoute(
    D.formField(name.?) { value ⇒
      inner.apply(value.asJava).toScala
    })

  def formFieldList(name: String, inner: JFunction[java.util.List[String], Route]): Route = ScalaRoute(
    D.formField(string2NR(name).*) { values ⇒
      inner.apply(values.toSeq.asJava).toScala
    })

  def formField[T](t: Unmarshaller[String, T], name: String, inner: JFunction[T, Route]): Route = {
    import t.asScala
    ScalaRoute(
      D.formField(name.as[T]) { value ⇒
        inner.apply(value).toScala
      })
  }

  def formFieldOptional[T](t: Unmarshaller[String, T], name: String, inner: JFunction[Optional[T], Route]): Route = {
    import t.asScala
    ScalaRoute(
      D.formField(name.as[T].?) { value ⇒
        inner.apply(value.asJava).toScala
      })
  }

  def formFieldList[T](t: Unmarshaller[String, T], name: String, inner: JFunction[java.util.List[T], Route]): Route = {
    import t.asScala
    ScalaRoute(
      D.formField(name.as[T].*) { values ⇒
        inner.apply(values.toSeq.asJava).toScala
      })
  }

  def formFieldMap(inner: JFunction[JMap[String, String], Route]): Route = ScalaRoute {
    D.formFieldMap { map ⇒ inner.apply(map.asJava).toScala }
  }

  def formFieldMultiMap(inner: JFunction[JMap[String, JList[String]], Route]): Route = ScalaRoute {
    D.formFieldMultiMap { map ⇒ inner.apply(map.mapValues { l ⇒ l.asJava }.asJava).toScala }
  }

  def formFieldList(inner: JFunction[JList[JMap.Entry[String, String]], Route]): Route = ScalaRoute {
    D.formFieldSeq { list ⇒
      val entries: Seq[JMap.Entry[String, String]] = list.map { e ⇒ new SimpleImmutableEntry(e._1, e._2) }
      inner.apply(entries.asJava).toScala
    }
  }

}