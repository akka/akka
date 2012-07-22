/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import scala.annotation.tailrec

import akka.actor.{ ActorRef, ActorCell }
import akka.dispatch.{ Terminate, SystemMessage, Suspend, Resume, Recreate, MessageDispatcher, Mailbox, Envelope, Create }
import akka.util.Unsafe

private[akka] trait Dispatch { this: ActorCell ⇒

  @volatile private var _mailboxDoNotCallMeDirectly: Mailbox = _ //This must be volatile since it isn't protected by the mailbox status

  @inline final def mailbox: Mailbox = Unsafe.instance.getObjectVolatile(this, AbstractActorCell.mailboxOffset).asInstanceOf[Mailbox]

  @tailrec final def swapMailbox(newMailbox: Mailbox): Mailbox = {
    val oldMailbox = mailbox
    if (!Unsafe.instance.compareAndSwapObject(this, AbstractActorCell.mailboxOffset, oldMailbox, newMailbox)) swapMailbox(newMailbox)
    else oldMailbox
  }

  final def hasMessages: Boolean = mailbox.hasMessages

  final def numberOfMessages: Int = mailbox.numberOfMessages

  val dispatcher: MessageDispatcher = system.dispatchers.lookup(props.dispatcher)

  /**
   * UntypedActorContext impl
   */
  final def getDispatcher(): MessageDispatcher = dispatcher

  final def isTerminated: Boolean = mailbox.isClosed

  final def start(sendSupervise: Boolean): this.type = {

    /*
     * Create the mailbox and enqueue the Create() message to ensure that
     * this is processed before anything else.
     */
    swapMailbox(dispatcher.createMailbox(this))
    mailbox.setActor(this)

    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    mailbox.systemEnqueue(self, Create())

    if (sendSupervise) {
      // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
      parent.sendSystemMessage(akka.dispatch.Supervise(self))
    }

    // This call is expected to start off the actor by scheduling its mailbox.
    dispatcher.attach(this)

    this
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def suspend(): Unit = dispatcher.systemDispatch(this, Suspend())

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def resume(inResponseToFailure: Boolean): Unit = dispatcher.systemDispatch(this, Resume(inResponseToFailure))

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def restart(cause: Throwable): Unit = dispatcher.systemDispatch(this, Recreate(cause))

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def stop(): Unit = dispatcher.systemDispatch(this, Terminate())

  def tell(message: Any, sender: ActorRef): Unit =
    dispatcher.dispatch(this, Envelope(message, if (sender eq null) system.deadLetters else sender, system))

  override def sendSystemMessage(message: SystemMessage): Unit = dispatcher.systemDispatch(this, message)

}