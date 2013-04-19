/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dungeon

import scala.annotation.tailrec
import akka.dispatch.{ MessageDispatcher, Mailbox, Envelope }
import akka.dispatch.sysmsg._
import akka.event.Logging.Error
import akka.util.Unsafe
import akka.dispatch.NullMessage
import akka.actor.{ NoSerializationVerificationNeeded, InvalidMessageException, ActorRef, ActorCell }
import akka.serialization.SerializationExtension
import scala.util.control.NonFatal
import scala.util.control.Exception.Catcher
import scala.concurrent.ExecutionContext

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

  final def isTerminated: Boolean = mailbox.isClosed

  /**
   * Initialize this cell, i.e. set up mailboxes and supervision. The UID must be
   * reasonably different from the previous UID of a possible actor with the same path,
   * which can be achieved by using ThreadLocalRandom.current.nextInt().
   */
  final def init(sendSupervise: Boolean): this.type = {
    /*
     * Create the mailbox and enqueue the Create() message to ensure that
     * this is processed before anything else.
     */
    val mbox = dispatcher.createMailbox(this)
    val actorClass = this.props.actorClass
    if (this.system.mailboxes.hasRequiredType(actorClass)) {
      this.system.mailboxes.getRequiredType(actorClass).foreach {
        case c if !c.isAssignableFrom(mbox.messageQueue.getClass) ⇒
          // FIXME 3237 throw an exception here instead of just logging it,
          // and update the comment on the RequiresMessageQueue trait
          val e = new IllegalArgumentException(s"Actor [${this.self.path}] requires mailbox type [${c}]" +
            s" got [${mbox.messageQueue.getClass}]")
          this.systemImpl.eventStream.publish(Error(e, getClass.getName, getClass, e.getMessage))
        case _ ⇒
      }
    }
    swapMailbox(mbox)
    mailbox.setActor(this)

    // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
    mailbox.systemEnqueue(self, Create())

    if (sendSupervise) {
      // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
      parent.sendSystemMessage(akka.dispatch.sysmsg.Supervise(self, async = false))
      parent ! NullMessage // read ScalaDoc of NullMessage to see why
    }
    this
  }

  /**
   * Start this cell, i.e. attach it to the dispatcher.
   */
  final def start(): this.type = {
    // This call is expected to start off the actor by scheduling its mailbox.
    dispatcher.attach(this)
    this
  }

  private def handleException: Catcher[Unit] = {
    case e: InterruptedException ⇒
      system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "interrupted during message send"))
      Thread.currentThread.interrupt()
    case NonFatal(e) ⇒
      system.eventStream.publish(Error(e, self.path.toString, clazz(actor), "swallowing exception during message send"))
  }

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def suspend(): Unit = try dispatcher.systemDispatch(this, Suspend()) catch handleException

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def resume(causedByFailure: Throwable): Unit = try dispatcher.systemDispatch(this, Resume(causedByFailure)) catch handleException

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def restart(cause: Throwable): Unit = try dispatcher.systemDispatch(this, Recreate(cause)) catch handleException

  // ➡➡➡ NEVER SEND THE SAME SYSTEM MESSAGE OBJECT TO TWO ACTORS ⬅⬅⬅
  final def stop(): Unit = try dispatcher.systemDispatch(this, Terminate()) catch handleException

  def sendMessage(msg: Envelope): Unit =
    try {
      val m = msg.message.asInstanceOf[AnyRef]
      if (system.settings.SerializeAllMessages && !m.isInstanceOf[NoSerializationVerificationNeeded]) {
        val s = SerializationExtension(system)
        s.deserialize(s.serialize(m).get, m.getClass).get
      }
      dispatcher.dispatch(this, msg)
    } catch handleException

  override def sendSystemMessage(message: SystemMessage): Unit = try dispatcher.systemDispatch(this, message) catch handleException

}
