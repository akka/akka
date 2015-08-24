/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.journal.leveldb

import java.util.ArrayList
import scala.collection.JavaConverters._

/**
 * The LevelDB journal supports tagging of events that are used
 * by the `EventsByTag` query. To specify the tags you create an
 * [[akka.persistence.journal.EventAdapter]] that wraps the events
 * in a `Tagged` with the given `tags`.
 *
 * The journal will unwrap the event and store the `payload`.
 */
case class Tagged(payload: Any, tags: Set[String]) {

  /**
   * Java API
   */
  def this(payload: Any, tags: java.util.Set[String]) = {
    this(payload, tags.asScala.toSet)
  }
}
