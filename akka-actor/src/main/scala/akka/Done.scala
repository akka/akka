/**
 * Copyright (C) 2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

/**
 * Typically used together with `Future` to signal completion
 * but there is no actual value completed. More clearly signals intent
 * than `Unit` and is available both from Scala and Java (which `Unit` is not).
 */
sealed abstract class Done

case object Done extends Done {
  /**
   * Java API: the singleton instance
   */
  def getInstance(): Done = this
}
