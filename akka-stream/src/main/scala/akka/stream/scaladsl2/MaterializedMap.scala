/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

/**
 * Returned by [[RunnableFlow#run]] and [[FlowGraph#run]] and can be used to retrieve the materialized
 * `Tap` inputs or `Drain` outputs, e.g. [[SubscriberTap]] or [[PublisherDrain]].
 */
trait MaterializedMap {

  /**
   * Retrieve a materialized `Tap`, e.g. the `Subscriber` of a [[SubscriberTap]].
   */
  def materializedTap(key: TapWithKey[_]): key.MaterializedType

  /**
   * Retrieve a materialized `Drain`, e.g. the `Publisher` of a [[PublisherDrain]].
   */
  def materializedDrain(key: DrainWithKey[_]): key.MaterializedType

}