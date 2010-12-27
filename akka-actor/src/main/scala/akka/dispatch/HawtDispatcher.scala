/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.dispatch

import akka.actor.ActorRef
import akka.util.Switch

import org.fusesource.hawtdispatch.DispatchQueue
import org.fusesource.hawtdispatch.ScalaDispatch._
import org.fusesource.hawtdispatch.DispatchQueue.QueueType
import org.fusesource.hawtdispatch.ListEventAggregator

import java.util.concurrent.atomic.{AtomicInteger, AtomicBoolean}
import java.util.concurrent.CountDownLatch

/**
 * Holds helper methods for working with actors that are using a HawtDispatcher as it's dispatcher.
 */
object HawtDispatcher {

  private val retained = new AtomicInteger()

  @volatile private var shutdownLatch: CountDownLatch = _

  private def retainNonDaemon = if (retained.getAndIncrement == 0) {
    shutdownLatch = new CountDownLatch(1)
    new Thread("HawtDispatch Non-Daemon") {
      override def run = {
        try {
          shutdownLatch.await
        } catch {
          case _ =>
        }
      }
    }.start()
  }

  private def releaseNonDaemon = if (retained.decrementAndGet == 0) {
    shutdownLatch.countDown
    shutdownLatch = null
  }

  /**
   * @return the mailbox associated with the actor
   */
  private def mailbox(actorRef: ActorRef) = actorRef.mailbox.asInstanceOf[HawtDispatcherMailbox]

  /**
   * @return the dispatch queue associated with the actor
   */
  def queue(actorRef: ActorRef) = mailbox(actorRef).queue

  /**
   * <p>
   * Pins an actor to a random thread queue.  Once pinned the actor will always execute
   * on the same thread.
   * </p>
   *
   * <p>
   * This method can only succeed if the actor it's dispatcher is set to a HawtDispatcher and it has been started
   * </p>
   *
   * @return true if the actor was pinned
   */
  def pin(actorRef: ActorRef) = actorRef.mailbox match {
    case x: HawtDispatcherMailbox =>
      x.queue.setTargetQueue( getRandomThreadQueue )
      true
    case _ => false
  }

  /**
   * <p>
   * Unpins the actor so that all threads in the hawt dispatch thread pool
   * compete to execute him.
   * </p>
   *
   * <p>
   * This method can only succeed if the actor it's dispatcher is set to a HawtDispatcher and it has been started
   * </p>
   * @return true if the actor was unpinned
   */
  def unpin(actorRef: ActorRef) = target(actorRef, globalQueue)

  /**
   * @return true if the actor was pinned to a thread.
   */
  def pinned(actorRef: ActorRef):Boolean = actorRef.mailbox match {
    case x: HawtDispatcherMailbox => x.queue.getTargetQueue.getQueueType == QueueType.THREAD_QUEUE
    case _ => false
  }

  /**
   * <p>
   * Updates the actor's target dispatch queue to the value specified.  This allows
   * you to do odd things like targeting another serial queue.
   * </p>
   *
   * <p>
   * This method can only succeed if the actor it's dispatcher is set to a HawtDispatcher and it has been started
   * </p>
   * @return true if the actor was unpinned
   */
  def target(actorRef: ActorRef, parent: DispatchQueue) = actorRef.mailbox match {
    case x: HawtDispatcherMailbox =>
      x.queue.setTargetQueue(parent)
      true
    case _ => false
  }
}

/**
 * <p>
 * A HawtDispatch based MessageDispatcher.  Actors with this dispatcher are executed
 * on the HawtDispatch fixed sized thread pool.  The number of of threads will match
 * the number of cores available on your system.
 *
 * </p>
 * <p>
 * Actors using this dispatcher are restricted to only executing non blocking
 * operations.  The actor cannot synchronously call another actor or call 3rd party
 * libraries that can block for a long time.  You should use non blocking IO APIs
 * instead of blocking IO apis to avoid blocking that actor for an extended amount
 * of time.
 * </p>
 *
 * <p>
 * This dispatcher delivers messages to the actors in the order that they
 * were producer at the sender.
 * </p>
 *
 * <p>
 * HawtDispatch supports processing Non blocking Socket IO in both the reactor
 * and proactor styles.  For more details, see the <code>HawtDispacherEchoServer.scala</code>
 * example.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class HawtDispatcher(val aggregate: Boolean = true, val parent: DispatchQueue = globalQueue) extends MessageDispatcher  {
  import HawtDispatcher._

  val mailboxType: Option[MailboxType] = None

  private[akka] def start { retainNonDaemon }

  private[akka] def shutdown { releaseNonDaemon }

  private[akka] def dispatch(invocation: MessageInvocation){
    mailbox(invocation.receiver).dispatch(invocation)
  }

  // hawtdispatch does not have a way to get queue sizes, getting an accurate
  // size can cause extra contention.. is this really needed?
  // TODO: figure out if this can be optional in akka
  override def mailboxSize(actorRef: ActorRef) = 0

  override def createMailbox(actorRef: ActorRef): AnyRef = {
    val queue = parent.createSerialQueue(actorRef.toString)
    if (aggregate) new AggregatingHawtDispatcherMailbox(queue)
    else new HawtDispatcherMailbox(queue)
  }

  def suspend(actorRef: ActorRef) = mailbox(actorRef).suspend
  def resume(actorRef:ActorRef)   = mailbox(actorRef).resume

  private[akka] def createTransientMailbox(actorRef: ActorRef, mailboxType: TransientMailbox): AnyRef = null.asInstanceOf[AnyRef]

  /**
   * Creates and returns a durable mailbox for the given actor.
   */
  private[akka] def createDurableMailbox(actorRef: ActorRef, mailboxType: DurableMailbox): AnyRef = null.asInstanceOf[AnyRef]

  override def toString = "HawtDispatcher"
}

class HawtDispatcherMailbox(val queue: DispatchQueue) {
  def dispatch(invocation: MessageInvocation) {
    queue {
      invocation.invoke
    }
  }

  def suspend = queue.suspend
  def resume  = queue.resume
}

class AggregatingHawtDispatcherMailbox(queue:DispatchQueue) extends HawtDispatcherMailbox(queue) {
  private val source = createSource(new ListEventAggregator[MessageInvocation](), queue)
  source.setEventHandler (^{drain_source} )
  source.resume

  private def drain_source = source.getData.foreach(_.invoke)

  override def suspend = source.suspend
  override def resume  = source.resume

  override def dispatch(invocation: MessageInvocation) {
    if (getCurrentQueue eq null) {
      // we are being call from a non hawtdispatch thread, can't aggregate
      // it's events
      super.dispatch(invocation)
    } else {
      // we are being call from a hawtdispatch thread, use the dispatch source
      // so that multiple invocations issues on this thread will aggregate and then once
      // the thread runs out of work, they get transferred as a batch to the other thread.
      source.merge(invocation)
    }
  }
}
