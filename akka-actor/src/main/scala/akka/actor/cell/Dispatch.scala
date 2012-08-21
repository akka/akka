/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import scala.annotation.tailrec
import akka.actor.{ ActorRef, ActorCell }
import akka.dispatch.{ Terminate, SystemMessage, Suspend, Resume, Recreate, MessageDispatcher, Mailbox, Envelope, Create }
import akka.event.Logging.Error
import akka.util.Unsafe
import scala.util.control.NonFatal
import akka.dispatch.NullMessage

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

  /**
   * Start this cell, i.e. attach it to the dispatcher. The UID must reasonably
   * be different from the previous UID of a possible actor with the same path,
   * which can be achieved by using ThreadLocalRandom.current.nextInt().
   */
  final def start(sendSupervise: Boolean, uid: Int): this.type = {

    /*
     * Create the mailbox and enqueue the Create() message to ensure that
     * this is processed before anything else.
     */
    swapMailbox(dispatcher.createMailbox(this))
    mailbox.setActor(this)

    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    mailbox.systemEnqueue(self, Create(uid))

    if (sendSupervise) {
      // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
      parent.sendSystemMessage(akka.dispatch.Supervise(self, uid))
      parent.tell(NullMessage) // read ScalaDoc of NullMessage to see why
    }

    // This call is expected to start off the actor by scheduling its mailbox.
    dispatcher.attach(this)

    this
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def suspend(): Unit =
    try dispatcher.systemDispatch(this, Suspend())
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "swallowing exception during message send"))
    }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def resume(causedByFailure: Throwable): Unit =
    try dispatcher.systemDispatch(this, Resume(causedByFailure))
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "swallowing exception during message send"))
    }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def restart(cause: Throwable): Unit =
    try dispatcher.systemDispatch(this, Recreate(cause))
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "swallowing exception during message send"))
    }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def stop(): Unit =
    try dispatcher.systemDispatch(this, Terminate())
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "swallowing exception during message send"))
    }

  def tell(message: Any, sender: ActorRef): Unit =
    try dispatcher.dispatch(this, Envelope(message, if (sender eq null) system.deadLetters else sender, system))
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "swallowing exception during message send"))
    }

  override def sendSystemMessage(message: SystemMessage): Unit =
    try dispatcher.systemDispatch(this, message)
    catch {
      case e @ (_: InterruptedException | NonFatal(_)) ⇒
        system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "swallowing exception during message send"))
    }

}