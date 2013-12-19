package akka.http.model.headers

import akka.http.util._

sealed abstract class LanguageRange extends ValueRenderable {
  def primaryTag: String
  def subTags: Seq[String]
  def matches(lang: Language): Boolean
  def render[R <: Rendering](r: R): r.type = {
    r ~~ primaryTag
    if (subTags.nonEmpty) subTags.foreach(r ~~ '-' ~~ _)
    r
  }
}

case class Language(primaryTag: String, subTags: String*) extends LanguageRange {
  def matches(lang: Language): Boolean = lang == this
}

object LanguageRanges {

  case object `*` extends LanguageRange {
    def primaryTag = "*"
    def subTags = Nil
    def matches(lang: Language): Boolean = true
  }
}
