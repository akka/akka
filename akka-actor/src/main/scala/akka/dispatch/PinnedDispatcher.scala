/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.dispatch

import java.util.concurrent.atomic.AtomicReference
import akka.actor.ActorInstance

/**
 * Dedicates a unique thread for each actor passed in as reference. Served through its messageQueue.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class PinnedDispatcher(_actor: ActorInstance, _name: String, _mailboxType: MailboxType)
  extends Dispatcher(
    _name, Dispatchers.THROUGHPUT, -1, _mailboxType, PinnedDispatcher.oneThread) {

  def this(_name: String, _mailboxType: MailboxType) = this(null, _name, _mailboxType)

  def this(_actor: ActorInstance, _name: String) = this(_actor, _name, Dispatchers.MAILBOX_TYPE)

  def this(_name: String) = this(null, _name, Dispatchers.MAILBOX_TYPE)

  def this(_mailboxType: MailboxType) = this(null, "anon", _mailboxType)

  def this(_actor: ActorInstance, _mailboxType: MailboxType) = this(_actor, _actor.uuid.toString, _mailboxType)

  def this(_actor: ActorInstance) = this(_actor, _actor.uuid.toString, Dispatchers.MAILBOX_TYPE)

  def this() = this(Dispatchers.MAILBOX_TYPE)

  protected[akka] val owner = new AtomicReference[ActorInstance](_actor)

  //Relies on an external lock provided by MessageDispatcher.attach
  protected[akka] override def register(actorInstance: ActorInstance) = {
    val actor = owner.get()
    if ((actor ne null) && actorInstance != actor) throw new IllegalArgumentException("Cannot register to anyone but " + actor)
    owner.compareAndSet(null, actorInstance) //Register if unregistered
    super.register(actorInstance)
  }
  //Relies on an external lock provided by MessageDispatcher.detach
  protected[akka] override def unregister(actor: ActorInstance) = {
    super.unregister(actor)
    owner.compareAndSet(actor, null) //Unregister (prevent memory leak)
  }
}

object PinnedDispatcher {
  val oneThread: ThreadPoolConfig = ThreadPoolConfig(allowCorePoolTimeout = true, corePoolSize = 1, maxPoolSize = 1)
}

