/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import scala.concurrent.ExecutionContextExecutor

object Dispatchers {
  /**
   * The id of the default dispatcher, also the full key of the
   * configuration of the default dispatcher.
   */
  final val DefaultDispatcherId = "akka.actor.default-dispatcher"
}

/**
 * An [[ActorSystem]] looks up all its thread pools via a Dispatchers instance.
 */
abstract class Dispatchers {
  def lookup(selector: DispatcherSelector): ExecutionContextExecutor
  def shutdown(): Unit
}
