/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorCell
import akka.actor.ActorSystem
import akka.event.EventStream
import akka.actor.Scheduler

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class PinnedDispatcher(
  _deadLetterMailbox: Mailbox,
  _eventStream: EventStream,
  _scheduler: Scheduler,
  _actor: ActorCell,
  _name: String,
  _mailboxType: MailboxType,
  _timeoutMs: Long)
  extends Dispatcher(_deadLetterMailbox, _eventStream, _scheduler, _name, Int.MaxValue, -1, _mailboxType, PinnedDispatcher.oneThread(_eventStream), _timeoutMs) {

  @volatile
  protected[akka] var owner: ActorCell = _actor

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

object PinnedDispatcher {
  def oneThread(eventStream: EventStream): ThreadPoolConfig = ThreadPoolConfig(eventStream, allowCorePoolTimeout = true, corePoolSize = 1, maxPoolSize = 1)
}

