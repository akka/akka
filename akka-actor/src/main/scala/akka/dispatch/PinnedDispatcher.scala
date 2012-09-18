/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import akka.actor.ActorCell
import scala.concurrent.util.Duration
import scala.concurrent.util.FiniteDuration

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * The preferred way of creating dispatchers is to define configuration of it and use the
 * the `lookup` method in [[akka.dispatch.Dispatchers]].
 */
class PinnedDispatcher(
  _prerequisites: DispatcherPrerequisites,
  _actor: ActorCell,
  _id: String,
  _mailboxType: MailboxType,
  _shutdownTimeout: FiniteDuration,
  _threadPoolConfig: ThreadPoolConfig = ThreadPoolConfig())
  extends Dispatcher(_prerequisites,
    _id,
    Int.MaxValue,
    Duration.Zero,
    _mailboxType,
    _threadPoolConfig.copy(allowCorePoolTimeout = true, corePoolSize = 1, maxPoolSize = 1),
    _shutdownTimeout) {

  @volatile
  private var owner: ActorCell = _actor

  //Relies on an external lock provided by MessageDispatcher.attach
  protected[akka] override def register(actorCell: ActorCell) = {
    val actor = owner
    if ((actor ne null) && actorCell != actor) throw new IllegalArgumentException("Cannot register to anyone but " + actor)
    owner = actorCell
    super.register(actorCell)
  }
  //Relies on an external lock provided by MessageDispatcher.detach
  protected[akka] override def unregister(actor: ActorCell) = {
    super.unregister(actor)
    owner = null
  }
}

