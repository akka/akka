/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent.atomic.AtomicReference
import akka.actor.{ LocalActorRef, ActorRef }

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class PinnedDispatcher(_actor: ActorRef, _name: String, _mailboxType: MailboxType)
  extends Dispatcher(
    _name, Dispatchers.THROUGHPUT, -1, _mailboxType, PinnedDispatcher.oneThread) {

  def this(_name: String, _mailboxType: MailboxType) = this(null, _name, _mailboxType)

  def this(_actor: ActorRef, _name: String) = this(_actor, _name, Dispatchers.MAILBOX_TYPE)

  def this(_name: String) = this(null, _name, Dispatchers.MAILBOX_TYPE)

  def this(_mailboxType: MailboxType) = this(null, "anon", _mailboxType)

  def this(_actor: ActorRef, _mailboxType: MailboxType) = this(_actor, _actor.uuid.toString, _mailboxType)

  def this(_actor: ActorRef) = this(_actor, _actor.uuid.toString, Dispatchers.MAILBOX_TYPE)

  def this() = this(Dispatchers.MAILBOX_TYPE)

  private[akka] val owner = new AtomicReference[ActorRef](_actor)

  //Relies on an external lock provided by MessageDispatcher.attach
  private[akka] override def register(actorRef: LocalActorRef) = {
    val actor = owner.get()
    if ((actor ne null) && actorRef != actor) throw new IllegalArgumentException("Cannot register to anyone but " + actor)
    owner.compareAndSet(null, actorRef) //Register if unregistered
    super.register(actorRef)
  }
  //Relies on an external lock provided by MessageDispatcher.detach
  private[akka] override def unregister(actorRef: LocalActorRef) = {
    super.unregister(actorRef)
    owner.compareAndSet(actorRef, null) //Unregister (prevent memory leak)
  }
}

object PinnedDispatcher {
  val oneThread: ThreadPoolConfig = ThreadPoolConfig(allowCorePoolTimeout = true, corePoolSize = 1, maxPoolSize = 1)
}

