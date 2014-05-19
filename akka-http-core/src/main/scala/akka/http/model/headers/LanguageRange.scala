/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import scala.collection.immutable
import akka.http.util._

sealed abstract class LanguageRange extends ValueRenderable with WithQValue[LanguageRange] {
  def qValue: Float
  def primaryTag: String
  def subTags: immutable.Seq[String]
  def matches(lang: Language): Boolean
  def render[R <: Rendering](r: R): r.type = {
    r ~~ primaryTag
    if (subTags.nonEmpty) subTags.foreach(r ~~ '-' ~~ _)
    if (qValue < 1.0f) r ~~ ";q=" ~~ qValue
    r
  }
}
object LanguageRange {
  case class `*`(qValue: Float) extends LanguageRange {
    def primaryTag = "*"
    def subTags = Nil
    def matches(lang: Language): Boolean = true
    def withQValue(qValue: Float) =
      if (qValue == 1.0f) `*` else if (qValue != this.qValue) `*`(qValue.toFloat) else this
  }
  object `*` extends `*`(1.0f)
}

case class Language(primaryTag: String, subTags: immutable.Seq[String], qValue: Float = 1.0f) extends LanguageRange {
  def matches(lang: Language): Boolean = lang.primaryTag == this.primaryTag && lang.subTags == this.subTags
  def withQValue(qValue: Float) = Language(primaryTag, subTags, qValue)
}
object Language {
  def apply(primaryTag: String, subTags: String*) = new Language(primaryTag, immutable.Seq(subTags: _*))
}