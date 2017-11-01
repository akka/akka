/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import java.io.Serializable

/**
 * Typically used together with `Future` to signal completion
 * but there is no actual value completed. More clearly signals intent
 * than `Unit` and is available both from Scala and Java (which `Unit` is not).
 */
sealed abstract class Done extends Serializable

case object Done extends Done {
  /**
   * Java API: the singleton instance
   */
  def getInstance(): Done = this
}
