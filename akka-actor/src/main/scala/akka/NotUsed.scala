/**
 *  Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

/**
 * This type is used in generic type signatures wherever the actual value is of no importance.
 * It is a combination of Scala’s `Unit` and Java’s `Void`, which both have different issues when
 * used from the other language. An example use-case is the materialized value of an Akka Stream for cases
 * where no result shall be returned from materialization.
 */
sealed abstract class NotUsed

case object NotUsed extends NotUsed {
  /**
   * Java API: the singleton instance
   */
  def getInstance(): NotUsed = this
}
