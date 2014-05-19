/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.headers

import scala.collection.immutable
import akka.http.util.{ Renderer, Rendering, ValueRenderable }

case class EntityTag(tag: String, weak: Boolean = false) extends ValueRenderable {
  def render[R <: Rendering](r: R): r.type = if (weak) r ~~ "W/" ~~#! tag else r ~~#! tag
}

object EntityTag {
  def matchesRange(eTag: EntityTag, entityTagRange: EntityTagRange, weak: Boolean) = entityTagRange match {
    case EntityTagRange.`*`           ⇒ weak || !eTag.weak
    case EntityTagRange.Default(tags) ⇒ tags.exists(matches(eTag, _, weak))
  }
  def matches(eTag: EntityTag, other: EntityTag, weak: Boolean) =
    other.tag == eTag.tag && (weak || !other.weak && !eTag.weak)
}

sealed abstract class EntityTagRange extends ValueRenderable

object EntityTagRange {
  def apply(tags: EntityTag*) = Default(immutable.Seq(tags: _*))

  implicit val tagsRenderer = Renderer.defaultSeqRenderer[EntityTag] // cache

  case object `*` extends EntityTagRange {
    def render[R <: Rendering](r: R): r.type = r ~~ '*'
  }

  case class Default(tags: immutable.Seq[EntityTag]) extends EntityTagRange {
    require(tags.nonEmpty, "tags must not be empty")
    def render[R <: Rendering](r: R): r.type = r ~~ tags
  }
}
