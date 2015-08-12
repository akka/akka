/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query

import akka.stream.scaladsl.Source
import com.typesafe.config.{ Config, ConfigFactory }

/**
 * Use for tests only!
 * Emits infinite stream of strings (representing queried for events).
 */
class MockReadJournal extends scaladsl.ReadJournal {
  override def query[T, M](q: Query[T, M], hints: Hint*): Source[T, M] =
    Source(() ⇒ Iterator.from(0)).map(_.toString).asInstanceOf[Source[T, M]]
}

object MockReadJournal {
  final val Identifier = "akka.persistence.query.journal.mock"

  final val config: Config = ConfigFactory.parseString(
    s"""
      |$Identifier {
      |  class = "${classOf[MockReadJournal].getCanonicalName}"
      |}
    """.stripMargin)
}