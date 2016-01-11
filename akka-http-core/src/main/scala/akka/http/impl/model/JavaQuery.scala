/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.model

import java.{ util ⇒ ju }
import akka.http.impl.model.parser.CharacterClasses
import akka.http.impl.util.StringRendering
import akka.http.javadsl.model.HttpCharset
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.scaladsl.model.UriRendering
import akka.http.scaladsl.{ model ⇒ sm }
import akka.japi.{ Pair, Option }
import akka.parboiled2.CharPredicate

import scala.collection.JavaConverters._
import akka.http.impl.util.JavaMapping.Implicits._

/** INTERNAL API */
case class JavaQuery(query: sm.Uri.Query) extends jm.Query {
  override def get(key: String): Option[String] = query.get(key)
  override def toMap: ju.Map[String, String] = query.toMap.asJava
  override def toList: ju.List[Pair[String, String]] = query.map(_.asJava).asJava
  override def getOrElse(key: String, _default: String): String = query.getOrElse(key, _default)
  override def toMultiMap: ju.Map[String, ju.List[String]] = query.toMultiMap.map { case (k, v) ⇒ (k, v.asJava) }.asJava
  override def getAll(key: String): ju.List[String] = query.getAll(key).asJava
  override def toString = query.toString
  override def withParam(key: String, value: String): jm.Query = jm.Query.create(query.map(_.asJava) :+ Pair(key, value): _*)
  override def render(charset: HttpCharset): String =
    UriRendering.renderQuery(new StringRendering, query, charset.nioCharset, CharacterClasses.unreserved).get
  override def render(charset: HttpCharset, keep: CharPredicate): String = render(charset, keep)
}
