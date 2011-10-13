/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorCell
import akka.AkkaApplication

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class PinnedDispatcher(_app: AkkaApplication, _actor: ActorCell, _name: String, _mailboxType: MailboxType, _timeoutMs: Long)
  extends Dispatcher(_app, _name, Int.MaxValue, -1, _mailboxType, PinnedDispatcher.oneThread(_app), _timeoutMs) {

  protected[akka] val owner = new AtomicReference[ActorCell](_actor)

  //Relies on an external lock provided by MessageDispatcher.attach
  protected[akka] override def register(actorCell: ActorCell) = {
    val actor = owner.get()
    if ((actor ne null) && actorCell != actor) throw new IllegalArgumentException("Cannot register to anyone but " + actor)
    owner.compareAndSet(null, actorCell) //Register if unregistered
    super.register(actorCell)
  }
  //Relies on an external lock provided by MessageDispatcher.detach
  protected[akka] override def unregister(actor: ActorCell) = {
    super.unregister(actor)
    owner.compareAndSet(actor, null) //Unregister (prevent memory leak)
  }
}

object PinnedDispatcher {
  def oneThread(app: AkkaApplication): ThreadPoolConfig = ThreadPoolConfig(app, allowCorePoolTimeout = true, corePoolSize = 1, maxPoolSize = 1)
}

