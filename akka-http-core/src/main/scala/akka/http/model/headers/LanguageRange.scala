/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import scala.collection.immutable
import akka.http.util._

import akka.http.model.japi.JavaMapping.Implicits._

sealed trait LanguageRange extends japi.headers.LanguageRange with ValueRenderable with WithQValue[LanguageRange] {
  def qValue: Float
  def primaryTag: String
  def subTags: immutable.Seq[String]
  def matches(lang: Language): Boolean
  final def render[R <: Rendering](r: R): r.type = {
    r ~~ primaryTag
    if (subTags.nonEmpty) subTags.foreach(r ~~ '-' ~~ _)
    if (qValue < 1.0f) r ~~ ";q=" ~~ qValue
    r
  }

  /** Java API */
  def matches(language: japi.headers.Language): Boolean = matches(language.asScala)
  def getSubTags: java.lang.Iterable[String] = subTags.asJava
}
object LanguageRange {
  case class `*`(qValue: Float) extends LanguageRange {
    require(0.0f <= qValue && qValue <= 1.0f, "qValue must be >= 0 and <= 1.0")
    def primaryTag = "*"
    def subTags = Nil
    def matches(lang: Language): Boolean = true
    def withQValue(qValue: Float) =
      if (qValue == 1.0f) `*` else if (qValue != this.qValue) `*`(qValue.toFloat) else this
  }
  object `*` extends `*`(1.0f)
}

final case class Language(primaryTag: String, subTags: immutable.Seq[String], qValue: Float = 1.0f) extends japi.headers.Language with LanguageRange {
  require(0.0f <= qValue && qValue <= 1.0f, "qValue must be >= 0 and <= 1.0")
  def matches(lang: Language): Boolean = lang.primaryTag == this.primaryTag && lang.subTags == this.subTags
  def withQValue(qValue: Float): Language = Language(primaryTag, subTags, qValue)
  override def withQValue(qValue: Double): Language = withQValue(qValue.toFloat)
}
object Language {
  def apply(primaryTag: String, subTags: String*) = new Language(primaryTag, immutable.Seq(subTags: _*))
}