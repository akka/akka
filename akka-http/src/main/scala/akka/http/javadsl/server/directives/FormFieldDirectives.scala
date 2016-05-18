/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server.directives

import java.util.{ Map ⇒ JMap, List ⇒ JList }
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.Optional
import java.util.function.{ Function ⇒ JFunction }

import scala.collection.JavaConverters._
import akka.http.impl.util.JavaMapping.Implicits._

import akka.http.javadsl.server.{ Route, Unmarshaller }

import akka.http.scaladsl.server.{ Directives ⇒ D }
import akka.http.scaladsl.server.directives.ParameterDirectives._

import scala.compat.java8.OptionConverters

abstract class FormFieldDirectives extends FileUploadDirectives {

  def formField(name: String, inner: JFunction[String, Route]): Route = RouteAdapter(
    D.formField(name) { value ⇒
      inner.apply(value).delegate
    })

  @CorrespondsTo("formField")
  def formFieldOptional(name: String, inner: JFunction[Optional[String], Route]): Route = RouteAdapter(
    D.formField(name.?) { value ⇒
      inner.apply(value.asJava).delegate
    })

  @CorrespondsTo("formFieldSeq")
  def formFieldList(name: String, inner: JFunction[java.util.List[String], Route]): Route = RouteAdapter(
    D.formField(_string2NR(name).*) { values ⇒
      inner.apply(values.toSeq.asJava).delegate
    })

  def formField[T](t: Unmarshaller[String, T], name: String, inner: JFunction[T, Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.formField(name.as[T]) { value ⇒
        inner.apply(value).delegate
      })
  }

  @CorrespondsTo("formField")
  def formFieldOptional[T](t: Unmarshaller[String, T], name: String, inner: JFunction[Optional[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.formField(name.as[T].?) { value ⇒
        inner.apply(OptionConverters.toJava(value)).delegate
      })
  }

  @CorrespondsTo("formFieldSeq")
  def formFieldList[T](t: Unmarshaller[String, T], name: String, inner: JFunction[java.util.List[T], Route]): Route = {
    import t.asScala
    RouteAdapter(
      D.formField(name.as[T].*) { values ⇒
        inner.apply(values.toSeq.asJava).delegate
      })
  }

  /**
   * Extracts HTTP form fields from the request as a ``Map<String, String>``.
   */
  def formFieldMap(inner: JFunction[JMap[String, String], Route]): Route = RouteAdapter {
    D.formFieldMap { map ⇒ inner.apply(map.asJava).delegate }
  }

  /**
   * Extracts HTTP form fields from the request as a ``Map<String, List<String>>``.
   */
  def formFieldMultiMap(inner: JFunction[JMap[String, JList[String]], Route]): Route = RouteAdapter {
    D.formFieldMultiMap { map ⇒ inner.apply(map.mapValues { l ⇒ l.asJava }.asJava).delegate }
  }

  /**
   * Extracts HTTP form fields from the request as a ``Map.Entry<String, String>>``.
   */
  @CorrespondsTo("formFieldSeq")
  def formFieldList(inner: JFunction[JList[JMap.Entry[String, String]], Route]): Route = RouteAdapter {
    D.formFieldSeq { list ⇒
      val entries: Seq[JMap.Entry[String, String]] = list.map { e ⇒ new SimpleImmutableEntry(e._1, e._2) }
      inner.apply(entries.asJava).delegate
    }
  }

}