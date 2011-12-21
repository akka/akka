/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorCell
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.actor.Scheduler
import akka.util.Duration
import java.util.concurrent.TimeUnit

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * The preferred way of creating dispatchers is to define configuration of it and use the
 * the `lookup` method in [[akka.dispatch.Dispatchers]].
 */
class PinnedDispatcher(
  _prerequisites: DispatcherPrerequisites,
  _actor: ActorCell,
  _name: String,
  _id: String,
  _mailboxType: MailboxType,
  _shutdownTimeout: Duration)
  extends Dispatcher(_prerequisites,
    _name,
    _id,
    Int.MaxValue,
    Duration.Zero,
    _mailboxType,
    ThreadPoolConfig(allowCorePoolTimeout = true, corePoolSize = 1, maxPoolSize = 1),
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

