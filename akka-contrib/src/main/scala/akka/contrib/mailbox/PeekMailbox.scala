/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.contrib.mailbox

import java.util.concurrent.{ ConcurrentHashMap, ConcurrentLinkedQueue }

import com.typesafe.config.Config

import akka.actor.{ ActorContext, ActorRef, ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.dispatch.{ Envelope, MailboxType, MessageQueue, UnboundedQueueBasedMessageQueue }

object PeekMailboxExtension extends ExtensionId[PeekMailboxExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ExtendedActorSystem) = new PeekMailboxExtension(s)

  def ack()(implicit context: ActorContext): Unit = PeekMailboxExtension(context.system).ack()
}

class PeekMailboxExtension(val system: ExtendedActorSystem) extends Extension {
  private val mailboxes = new ConcurrentHashMap[ActorRef, PeekMailbox]

  def register(actorRef: ActorRef, mailbox: PeekMailbox): Unit =
    mailboxes.put(actorRef, mailbox)

  def unregister(actorRef: ActorRef): Unit = mailboxes.remove(actorRef)

  def ack()(implicit context: ActorContext): Unit =
    mailboxes.get(context.self) match {
      case null    ⇒ throw new IllegalArgumentException("Mailbox not registered for: " + context.self)
      case mailbox ⇒ mailbox.ack()
    }
}

/**
 * configure the mailbox via dispatcher configuration:
 * {{{
 *   peek-dispatcher {
 *      mailbox-type = "example.PeekMailboxType"
 *   }
 * }}}
 */
class PeekMailboxType(settings: ActorSystem.Settings, config: Config) extends MailboxType {
  override def create(owner: Option[ActorRef], system: Option[ActorSystem]) = (owner, system) match {
    case (Some(o), Some(s)) ⇒
      val retries = config.getInt("max-retries")
      if (retries < 1) throw new akka.ConfigurationException("max-retries must be at least 1")
      val mailbox = new PeekMailbox(o, s, retries)
      PeekMailboxExtension(s).register(o, mailbox)
      mailbox
    case _ ⇒ throw new Exception("no mailbox owner or system given")
  }
}

class PeekMailbox(owner: ActorRef, system: ActorSystem, maxRetries: Int)
  extends UnboundedQueueBasedMessageQueue {
  final val queue = new ConcurrentLinkedQueue[Envelope]()

  /*
   * Since the queue itself is used to determine when to schedule the actor
   * (see Mailbox.hasMessages), we cannot poll() on the first try and then
   * continue handing back out that same message until ACKed, peek() must be
   * used. The retry limit logic is then formulated in terms of the `tries`
   * field, which holds
   *  0             if clean slate (i.e. last dequeue was ack()ed)
   *  1..maxRetries if not yet ack()ed
   *  Marker        if last try was done (at which point we had to poll())
   *  -1            during cleanUp (in order to disable the ack() requirement)
   */
  // the mutable state is only ever accessed by the actor (i.e. dequeue() side)
  var tries = 0
  val Marker = maxRetries + 1

  // this logic does not work if maxRetries==0, but then you could also use a normal mailbox
  override def dequeue(): Envelope = tries match {
    case -1 ⇒
      queue.poll()
    case 0 | Marker ⇒
      val e = queue.peek()
      tries = if (e eq null) 0 else 1
      e
    case `maxRetries` ⇒
      tries = Marker
      queue.poll()
    case n ⇒
      tries = n + 1
      queue.peek()
  }

  def ack(): Unit = {
    // do not dequeue for real if double-ack() or ack() on last try
    if (tries != 0 && tries != Marker) queue.poll()
    tries = 0
  }

  override def cleanUp(owner: ActorRef, deadLetters: MessageQueue): Unit = {
    tries = -1 // put the queue into auto-ack mode
    super.cleanUp(owner, deadLetters)
    PeekMailboxExtension(system).unregister(owner)
  }
}

