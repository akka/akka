/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.actor.InvalidActorNameException
import akka.util.Helpers
import scala.concurrent.duration.{ Duration, FiniteDuration }
import akka.dispatch.ExecutionContexts
import scala.concurrent.ExecutionContextExecutor
import akka.actor.Cancellable
import akka.util.Unsafe.{ instance ⇒ unsafe }
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.Queue
import scala.annotation.{ tailrec, switch }
import scala.util.control.NonFatal
import scala.util.control.Exception.Catcher
import akka.event.Logging.Error
import akka.event.Logging

/**
 * INTERNAL API
 */
object ActorCell {
  /*
   * Description of the _status field bit structure:
   *
   * bit 0-29: activation count (number of (system)messages)
   * bit 30: terminating (or terminated)
   * bit 31: terminated
   *
   * Activation count is a bit special:
   * 0 means inactive
   * 1 means active without normal messages (i.e. only system messages)
   * N means active with N-1 normal messages (plus possibly system messages)
   */
  final val terminatingShift = 30

  final val activationMask = (1 << terminatingShift) - 1
  // ensure that if all processors enqueue “the last message” concurrently, there is still no overflow
  val maxActivations = activationMask - Runtime.getRuntime.availableProcessors - 1

  final val terminatingBit = 1 << terminatingShift
  final val terminatedBit = 1 << 31

  def isTerminating(status: Int): Boolean = (status & terminatingBit) != 0
  def isTerminated(status: Int): Boolean = status < 0
  def isActive(status: Int): Boolean = (status & ~activationMask) == 0

  def activations(status: Int): Int = status & activationMask
  def messageCount(status: Int): Int = Math.max(0, activations(status) - 1)

  val statusOffset = unsafe.objectFieldOffset(classOf[ActorCell[_]].getDeclaredField("_status"))
  val systemQueueOffset = unsafe.objectFieldOffset(classOf[ActorCell[_]].getDeclaredField("_systemQueue"))

  final val DefaultState = 0
  final val SuspendedState = 1
  final val SuspendedWaitForChildrenState = 2

  final val Debug = false
}

/**
 * INTERNAL API
 */
private[typed] class ActorCell[T](
  override val system:           ActorSystem[Nothing],
  protected val initialBehavior: Behavior[T],
  override val executionContext: ExecutionContextExecutor,
  override val mailboxCapacity:  Int,
  val parent:                    ActorRefImpl[Nothing])
  extends ActorContext[T] with Runnable with SupervisionMechanics[T] with DeathWatch[T] {
  import ActorCell._

  /*
   * Implementation of the ActorContext trait.
   */

  protected var childrenMap = Map.empty[String, ActorRefImpl[Nothing]]
  protected var terminatingMap = Map.empty[String, ActorRefImpl[Nothing]]
  override def children: Iterable[ActorRef[Nothing]] = childrenMap.values
  override def child(name: String): Option[ActorRef[Nothing]] = childrenMap.get(name)
  protected def removeChild(actor: ActorRefImpl[Nothing]): Unit = {
    val n = actor.path.name
    childrenMap.get(n) match {
      case Some(`actor`) ⇒ childrenMap -= n
      case _ ⇒
        terminatingMap.get(n) match {
          case Some(`actor`) ⇒ terminatingMap -= n
          case _             ⇒
        }
    }
  }
  private[typed] def terminating: Iterable[ActorRef[Nothing]] = terminatingMap.values

  private var _self: ActorRefImpl[T] = _
  private[typed] def setSelf(ref: ActorRefImpl[T]): Unit = _self = ref
  override def self: ActorRefImpl[T] = _self

  protected def ctx: ActorContext[T] = this

  override def spawn[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig): ActorRef[U] = {
    if (childrenMap contains name) throw new InvalidActorNameException(s"actor name [$name] is not unique")
    if (terminatingMap contains name) throw new InvalidActorNameException(s"actor name [$name] is not yet free")
    val dispatcher = deployment.firstOrElse[DispatcherSelector](DispatcherFromExecutionContext(executionContext))
    val capacity = deployment.firstOrElse(MailboxCapacity(system.settings.DefaultMailboxCapacity))
    val cell = new ActorCell[U](system, Behavior.validateAsInitial(behavior), system.dispatchers.lookup(dispatcher), capacity.capacity, self)
    val ref = new LocalActorRef[U](self.path / name, cell)
    cell.setSelf(ref)
    childrenMap = childrenMap.updated(name, ref)
    ref.sendSystem(Create())
    ref
  }

  private var nextName = 0L
  override def spawnAnonymous[U](behavior: Behavior[U], deployment: DeploymentConfig): ActorRef[U] = {
    val name = Helpers.base64(nextName)
    nextName += 1
    spawn(behavior, name, deployment)
  }

  override def stop(child: ActorRef[Nothing]): Boolean = {
    val name = child.path.name
    childrenMap.get(name) match {
      case None                      ⇒ false
      case Some(ref) if ref != child ⇒ false
      case Some(ref) ⇒
        ref.sendSystem(Terminate())
        childrenMap -= name
        terminatingMap = terminatingMap.updated(name, ref)
        true
    }
  }

  protected def stopAll(): Unit = {
    childrenMap.valuesIterator.foreach { ref ⇒
      ref.sendSystem(Terminate())
      terminatingMap = terminatingMap.updated(ref.path.name, ref)
    }
    childrenMap = Map.empty
  }

  override def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): Cancellable =
    system.scheduler.scheduleOnce(delay)(target ! msg)(ExecutionContexts.sameThreadExecutionContext)

  override def spawnAdapter[U](f: U ⇒ T): ActorRef[U] = {
    val name = Helpers.base64(nextName, new java.lang.StringBuilder("$!"))
    nextName += 1
    val ref = new FunctionRef[U](
      self.path / name,
      (msg, _) ⇒ send(f(msg)),
      (self) ⇒ sendSystem(DeathWatchNotification(self, null)))
    childrenMap = childrenMap.updated(name, ref)
    ref
  }

  private[this] var receiveTimeout: (FiniteDuration, T) = null
  override def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = {
    if (Debug) println(s"$self setting receive timeout of $d, msg $msg")
    receiveTimeout = (d, msg)
  }
  override def cancelReceiveTimeout(): Unit = {
    if (Debug) println(s"$self canceling receive timeout")
    receiveTimeout = null
  }

  /*
   * Implementation of the invocation mechanics.
   */

  // see comment in companion object for details
  @volatile private[this] var _status: Int = 0
  protected[typed] def getStatus: Int = _status
  private[this] val queue: Queue[T] = new ConcurrentLinkedQueue[T]
  private[typed] def peekMessage: T = queue.peek()
  private[this] val maxQueue: Int = Math.min(mailboxCapacity, maxActivations)
  @volatile private[this] var _systemQueue: LatestFirstSystemMessageList = SystemMessageList.LNil

  protected def maySend: Boolean = !isTerminating
  protected def isTerminating: Boolean = ActorCell.isTerminating(_status)
  protected def setTerminating(): Unit = if (!ActorCell.isTerminating(_status)) unsafe.getAndAddInt(this, statusOffset, terminatingBit)
  protected def setClosed(): Unit = if (!isTerminated(_status)) unsafe.getAndAddInt(this, statusOffset, terminatedBit)

  private def handleException: Catcher[Unit] = {
    case e: InterruptedException ⇒
      publish(Error(e, self.path.toString, getClass, "interrupted during message send"))
      Thread.currentThread.interrupt()
    case NonFatal(e) ⇒
      publish(Error(e, self.path.toString, getClass, "swallowing exception during message send"))
  }

  def send(msg: T): Unit =
    try {
      val old = unsafe.getAndAddInt(this, statusOffset, 1)
      val oldActivations = activations(old)
      // this is not an off-by-one: #msgs is activations-1 if >0
      if (oldActivations > maxQueue) {
        if (Debug) println(s"[$thread] $self NOT enqueueing $msg at status $old ($oldActivations > $maxQueue)")
        // cannot enqueue, need to give back activation token
        unsafe.getAndAddInt(this, statusOffset, -1)
        system.eventStream.publish(Dropped(msg, self))
      } else if (ActorCell.isTerminating(old)) {
        if (Debug) println(s"[$thread] $self NOT enqueueing $msg at status $old (is terminating)")
        unsafe.getAndAddInt(this, statusOffset, -1)
        system.deadLetters ! msg
      } else {
        if (Debug) println(s"[$thread] $self enqueueing $msg at status $old")
        // need to enqueue; if the actor sees the token but not the message, it will reschedule
        queue.add(msg)
        if (oldActivations == 0) {
          if (Debug) println(s"[$thread] $self being woken up")
          unsafe.getAndAddInt(this, statusOffset, 1) // the first 1 was just the “active” bit, now add 1msg
          // if the actor was not yet running, set it in motion; spurious wakeups don’t hurt
          executionContext.execute(this)
        }
      }
    } catch handleException

  def sendSystem(signal: SystemMessage): Unit = {
    @tailrec def needToActivate(): Boolean = {
      val currentList = _systemQueue
      if (currentList.head == NoMessage) {
        system.deadLetters.sorry.sendSystem(signal)
        false
      } else {
        unsafe.compareAndSwapObject(this, systemQueueOffset, currentList.head, (signal :: currentList).head) || {
          signal.unlink()
          needToActivate()
        }
      }
    }
    try {
      if (needToActivate()) {
        val old = unsafe.getAndAddInt(this, statusOffset, 1)
        if (isTerminated(old)) {
          // nothing to do
          if (Debug) println(s"[$thread] $self NOT enqueueing $signal: terminating")
          unsafe.getAndAddInt(this, statusOffset, -1)
        } else if (activations(old) == 0) {
          // all is good: we signaled the transition to active
          if (Debug) println(s"[$thread] $self enqueueing $signal: activating")
          executionContext.execute(this)
        } else {
          // take back that token: we didn’t actually enqueue a normal message and the actor was already active
          if (Debug) println(s"[$thread] $self enqueueing $signal: already active")
          unsafe.getAndAddInt(this, statusOffset, -1)
        }
      } else if (Debug) println(s"[$thread] $self NOT enqueueing $signal: terminated")
    } catch handleException
  }

  /**
   * Main entry point into the actor: the ActorCell is a Runnable that is
   * enqueued in its Executor whenever it needs to run. The _status field is
   * used for coordination such that it is never enqueued more than once at
   * any given time, because that would break the Actor Model.
   *
   * The idea here is to process at most as many messages as were in queued
   * upon entry of this method, interleaving each normal message with the
   * processing of all system messages that may have accumulated in the
   * meantime. If at the end of the processing messages remain in the queue
   * then this cell is rescheduled.
   *
   * All coordination occurs via a single Int field that is only updated in
   * wait-free manner (LOCK XADD via unsafe.getAndAddInt), where conflicts are
   * resolved by compensating actions. For a description of the bit usage see
   * the companion object’s source code.
   */
  override final def run(): Unit = {
    if (Debug) println(s"[$thread] $self entering run(): interrupted=${Thread.currentThread.isInterrupted}")
    val status = _status
    val msgs = messageCount(status)
    var processed = 0
    try {
      unscheduleReceiveTimeout()
      if (!isTerminated(status)) {
        while (processAllSystemMessages() && !queue.isEmpty() && processed < msgs) {
          val msg = queue.poll()
          processed += 1
          processMessage(msg)
        }
      }
      scheduleReceiveTimeout()
    } catch {
      case NonFatal(ex) ⇒ fail(ex)
      case ie: InterruptedException ⇒
        fail(ie)
        if (Debug) println(s"[$thread] $self interrupting due to catching InterruptedException")
        Thread.currentThread.interrupt()
    } finally {
      // also remove the general activation token
      processed += 1
      val prev = unsafe.getAndAddInt(this, statusOffset, -processed)
      val now = prev - processed
      if (isTerminated(now)) {
        // we’re finished
      } else if (activations(now) > 0) {
        // normal messages pending: reverse the deactivation
        unsafe.getAndAddInt(this, statusOffset, 1)
        // ... and reschedule
        executionContext.execute(this)
      } else if (_systemQueue.head != null) {
        /*
         * System message was enqueued after our last processing, we now need to
         * race against the other party because the enqueue might have happened
         * before the deactivation (above) and hence not scheduled.
         *
         * If we win, we reschedule; if we lose, we must remove the attempted
         * activation token again.
         */
        val again = unsafe.getAndAddInt(this, statusOffset, 1)
        if (activations(again) == 0) executionContext.execute(this)
        else unsafe.getAndAddInt(this, statusOffset, -1)
      }
    }
    if (Debug) println(s"[$thread] $self exiting run(): interrupted=${Thread.currentThread.isInterrupted}")
  }

  protected[typed] var behavior: Behavior[T] = _

  protected def next(b: Behavior[T], msg: Any): Unit = {
    if (Behavior.isUnhandled(b)) unhandled(msg)
    behavior = Behavior.canonicalize(b, behavior)
    if (!Behavior.isAlive(behavior)) self.sendSystem(Terminate())
  }

  private def unhandled(msg: Any): Unit = msg match {
    case Terminated(ref) ⇒ fail(DeathPactException(ref))
    case _               ⇒ // nothing to do
  }

  private[this] var receiveTimeoutScheduled: Cancellable = null
  private def unscheduleReceiveTimeout(): Unit =
    if (receiveTimeoutScheduled ne null) {
      receiveTimeoutScheduled.cancel()
      receiveTimeoutScheduled = null
    }
  private def scheduleReceiveTimeout(): Unit =
    receiveTimeout match {
      case (d, msg) ⇒
        receiveTimeoutScheduled = schedule(d, self, msg)
      case other ⇒
      // nothing to do
    }

  /**
   * Process the messages in the mailbox
   */
  private def processMessage(msg: T): Unit = {
    if (Debug) println(s"[$thread] $self processing message $msg")
    next(behavior.message(this, msg), msg)
    if (Thread.interrupted())
      throw new InterruptedException("Interrupted while processing actor messages")
  }

  @tailrec
  private def systemDrain(next: LatestFirstSystemMessageList): EarliestFirstSystemMessageList = {
    val currentList = _systemQueue
    if (currentList.head == NoMessage) SystemMessageList.ENil
    else if (unsafe.compareAndSwapObject(this, systemQueueOffset, currentList.head, next.head)) currentList.reverse
    else systemDrain(next)
  }

  /**
   * Will at least try to process all queued system messages: in case of
   * failure simply drop and go on to the next, because there is nothing to
   * restart here (failure is in ActorCell somewhere …). In case the mailbox
   * becomes closed (because of processing a Terminate message), dump all
   * already dequeued message to deadLetters.
   */
  private def processAllSystemMessages(): Boolean = {
    var interruption: Throwable = null
    var messageList = systemDrain(SystemMessageList.LNil)
    var continue = true
    while (messageList.nonEmpty && continue) {
      val msg = messageList.head
      messageList = messageList.tail
      msg.unlink()
      continue =
        try processSignal(msg)
        catch {
          case ie: InterruptedException ⇒
            fail(ie)
            if (Debug) println(s"[$thread] $self interrupting due to catching InterruptedException during system message processing")
            Thread.currentThread.interrupt()
            true
          case ex @ (NonFatal(_) | _: AssertionError) ⇒
            fail(ex)
            true
        }
      /*
       * the second part of the condition is necessary to avoid logging an InterruptedException
       * from the systemGuardian during shutdown
       */
      if (Thread.interrupted() && system.whenTerminated.value.isEmpty)
        interruption = new InterruptedException("Interrupted while processing system messages")
      // don’t ever execute normal message when system message present!
      if (messageList.isEmpty && continue) messageList = systemDrain(SystemMessageList.LNil)
    }
    /*
     * if we closed the mailbox, we must dump the remaining system messages
     * to deadLetters (this is essential for DeathWatch)
     */
    val dlm = system.deadLetters
    if (isTerminated(_status) && messageList.isEmpty) messageList = systemDrain(new LatestFirstSystemMessageList(NoMessage))
    while (messageList.nonEmpty) {
      val msg = messageList.head
      messageList = messageList.tail
      if (Debug) println(s"[$thread] $self dropping dead system message $msg")
      msg.unlink()
      try dlm.sorry.sendSystem(msg)
      catch {
        case e: InterruptedException ⇒ interruption = e
        case NonFatal(e) ⇒ system.eventStream.publish(
          Error(e, self.path.toString, this.getClass, "error while enqueuing " + msg + " to deadLetters: " + e.getMessage))
      }
      if (isTerminated(_status) && messageList.isEmpty) messageList = systemDrain(new LatestFirstSystemMessageList(NoMessage))
    }
    // if we got an interrupted exception while handling system messages, then rethrow it
    if (interruption ne null) {
      if (Debug) println(s"[$thread] $self throwing interruption")
      Thread.interrupted() // clear interrupted flag before throwing according to java convention
      throw interruption
    }
    continue
  }

  // logging is not the main purpose, and if it fails there’s nothing we can do
  protected final def publish(e: Logging.LogEvent): Unit = try system.eventStream.publish(e) catch { case NonFatal(_) ⇒ }

  protected final def clazz(o: AnyRef): Class[_] = if (o eq null) this.getClass else o.getClass

  private def thread: String = Thread.currentThread.getName

  override def toString: String = f"ActorCell($self, status = ${_status}%08x, queue = $queue)"
}
