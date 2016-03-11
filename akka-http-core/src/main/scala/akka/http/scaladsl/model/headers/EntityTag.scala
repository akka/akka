/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import akka.http.impl.model.JavaInitialization
import akka.util.Unsafe

import scala.collection.immutable
import akka.http.impl.util.{ Renderer, Rendering, ValueRenderable }
import akka.http.javadsl.{ model ⇒ jm }

final case class EntityTag(tag: String, weak: Boolean = false) extends jm.headers.EntityTag with ValueRenderable {
  def render[R <: Rendering](r: R): r.type = if (weak) r ~~ "W/" ~~#! tag else r ~~#! tag
}

object EntityTag {
  def matchesRange(eTag: EntityTag, entityTagRange: EntityTagRange, weakComparison: Boolean) =
    entityTagRange match {
      case EntityTagRange.`*`           ⇒ weakComparison || !eTag.weak
      case EntityTagRange.Default(tags) ⇒ tags.exists(matches(eTag, _, weakComparison))
    }
  def matches(eTag: EntityTag, other: EntityTag, weakComparison: Boolean) =
    other.tag == eTag.tag && (weakComparison || !other.weak && !eTag.weak)
}

sealed abstract class EntityTagRange extends jm.headers.EntityTagRange with ValueRenderable

object EntityTagRange {
  def apply(tags: EntityTag*) = Default(immutable.Seq(tags: _*))

  implicit val tagsRenderer = Renderer.defaultSeqRenderer[EntityTag] // cache

  case object `*` extends EntityTagRange {
    def render[R <: Rendering](r: R): r.type = r ~~ '*'
  }

  final case class Default(tags: immutable.Seq[EntityTag]) extends EntityTagRange {
    require(tags.nonEmpty, "tags must not be empty")
    def render[R <: Rendering](r: R): r.type = r ~~ tags
  }

  JavaInitialization.initializeStaticFieldWith(
    `*`, classOf[jm.headers.EntityTagRange].getField("ALL"))

}
