/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka

import java.io.Serializable
import akka.annotation.DoNotInherit

/**
 * Typically used together with `Future` to signal completion
 * but there is no actual value completed. More clearly signals intent
 * than `Unit` and is available both from Scala and Java (which `Unit` is not).
 */
@DoNotInherit sealed abstract class Done extends Serializable

case object Done extends Done {
  /**
   * Java API: the singleton instance
   */
  def getInstance(): Done = this
}
