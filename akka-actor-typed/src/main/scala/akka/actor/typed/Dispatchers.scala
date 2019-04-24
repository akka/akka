/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.annotation.DoNotInherit

import scala.concurrent.ExecutionContextExecutor

object Dispatchers {

  /**
   * The id of the default dispatcher, also the full key of the
   * configuration of the default dispatcher.
   */
  final val DefaultDispatcherId = akka.dispatch.Dispatchers.DefaultDispatcherId
}

/**
 * An [[ActorSystem]] looks up all its thread pools via a Dispatchers instance.
 *
 * Not for user instantiation or extension
 */
@DoNotInherit
abstract class Dispatchers {
  def lookup(selector: DispatcherSelector): ExecutionContextExecutor

  /**
   * A selector that will use the default dispatcher for actors performing blocking operations as configured
   * with the 'akka.actor.blocking-dispatcher' setting.
   */
  def blockingDispatcherSelector: DispatcherSelector

  /**
   * INTERNAL API
   *
   * Dispatcher for internal actors, configured with the 'akka.actor.internal-dispatcher' setting.
   */
  private[akka] def internalDispatcherSelector: DispatcherSelector
}
