/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.testkit

import language.postfixOps

import java.lang.ref.WeakReference
import java.util.concurrent.locks.ReentrantLock
import java.util.LinkedList
import scala.annotation.tailrec
import com.typesafe.config.Config
import akka.actor.{ ActorInitializationException, ExtensionIdProvider, ExtensionId, Extension, ExtendedActorSystem, ActorRef, ActorCell }
import akka.dispatch.{ MessageQueue, MailboxType, TaskInvocation, SystemMessage, Suspend, Resume, MessageDispatcherConfigurator, MessageDispatcher, Mailbox, Envelope, DispatcherPrerequisites, DefaultSystemMessageQueue }
import scala.concurrent.util.duration.intToDurationInt
import akka.util.Switch
import scala.concurrent.util.Duration
import scala.concurrent.Awaitable
import akka.actor.ActorContext
import scala.util.control.NonFatal

/*
 * Locking rules:
 *
 * While not suspendSwitch, messages are processed (!isActive) or queued
 * thread-locally (isActive). While suspendSwitch, messages are queued
 * thread-locally. When resuming, all messages are atomically scooped from all
 * non-active threads and queued on the resuming thread's queue, to be
 * processed immediately. Processing a queue checks suspend before each
 * invocation, leaving the active state if suspendSwitch. For this to work
 * reliably, the active flag needs to be set atomically with the initial check
 * for suspend. Scooping up messages means replacing the ThreadLocal's contents
 * with an empty new NestingQueue.
 *
 * All accesses to the queue must be done under the suspendSwitch-switch's lock, so
 * within one of its methods taking a closure argument.
 */

private[testkit] object CallingThreadDispatcherQueues extends ExtensionId[CallingThreadDispatcherQueues] with ExtensionIdProvider {
  override def lookup = CallingThreadDispatcherQueues
  override def createExtension(system: ExtendedActorSystem): CallingThreadDispatcherQueues = new CallingThreadDispatcherQueues
}

private[testkit] class CallingThreadDispatcherQueues extends Extension {

  // PRIVATE DATA

  private var queues = Map[CallingThreadMailbox, Set[WeakReference[NestingQueue]]]()
  private var lastGC = 0l

  // we have to forget about long-gone threads sometime
  private def gc {
    queues = queues mapValues (_ filter (_.get ne null)) filter (!_._2.isEmpty)
  }

  protected[akka] def registerQueue(mbox: CallingThreadMailbox, q: NestingQueue): Unit = synchronized {
    if (queues contains mbox) {
      val newSet = queues(mbox) + new WeakReference(q)
      queues += mbox -> newSet
    } else {
      queues += mbox -> Set(new WeakReference(q))
    }
    val now = System.nanoTime
    if (now - lastGC > 1000000000l) {
      lastGC = now
      gc
    }
  }

  /*
   * This method must be called with "own" being this thread's queue for the
   * given mailbox. When this method returns, the queue will be entered
   * (active).
   */
  protected[akka] def gatherFromAllOtherQueues(mbox: CallingThreadMailbox, own: NestingQueue): Unit = synchronized {
    if (!own.isActive) own.enter
    if (queues contains mbox) {
      for {
        ref ← queues(mbox)
        q = ref.get
        if (q ne null) && (q ne own)
      } {
        val owner = mbox.actor.self
        var msg = q.q.dequeue()
        while (msg ne null) {
          // this is safe because this method is only ever called while holding the suspendSwitch monitor
          own.q.enqueue(owner, msg)
          msg = q.q.dequeue()
        }
      }
    }
  }
}

object CallingThreadDispatcher {
  val Id = "akka.test.calling-thread-dispatcher"
}

/**
 * Dispatcher which runs invocations on the current thread only. This
 * dispatcher does not create any new threads, but it can be used from
 * different threads concurrently for the same actor. The dispatch strategy is
 * to run on the current thread unless the target actor is either suspendSwitch or
 * already running on the current thread (if it is running on a different
 * thread, then this thread will block until that other invocation is
 * finished); if the invocation is not run, it is queued in a thread-local
 * queue to be executed once the active invocation further up the call stack
 * finishes. This leads to completely deterministic execution order if only one
 * thread is used.
 *
 * Suspending and resuming are global actions for one actor, meaning they can
 * affect different threads, which leads to complications. If messages are
 * queued (thread-locally) during the suspendSwitch period, the only thread to run
 * them upon resume is the thread actually calling the resume method. Hence,
 * all thread-local queues which are not currently being drained (possible,
 * since suspend-queue-resume might happen entirely during an invocation on a
 * different thread) are scooped up into the current thread-local queue which
 * is then executed. It is possible to suspend an actor from within its call
 * stack.
 *
 * @author Roland Kuhn
 * @since 1.1
 */
class CallingThreadDispatcher(
  _prerequisites: DispatcherPrerequisites,
  val mailboxType: MailboxType,
  val name: String = "calling-thread") extends MessageDispatcher(_prerequisites) {
  import CallingThreadDispatcher._

  val log = akka.event.Logging(prerequisites.eventStream, "CallingThreadDispatcher")

  override def id: String = Id

  protected[akka] override def createMailbox(actor: akka.actor.Cell) = new CallingThreadMailbox(actor, mailboxType)

  protected[akka] override def shutdown() {}

  protected[akka] override def throughput = 0
  protected[akka] override def throughputDeadlineTime = Duration.Zero
  protected[akka] override def registerForExecution(mbox: Mailbox, hasMessageHint: Boolean, hasSystemMessageHint: Boolean): Boolean = false

  protected[akka] override def shutdownTimeout = 1 second

  protected[akka] override def register(actor: ActorCell): Unit = {
    super.register(actor)
    actor.mailbox match {
      case mbox: CallingThreadMailbox ⇒
        val queue = mbox.queue
        queue.enter
        runQueue(mbox, queue)
      case x ⇒ throw new ActorInitializationException("expected CallingThreadMailbox, got " + x.getClass)
    }
  }

  override def suspend(actor: ActorCell) {
    actor.mailbox match {
      case m: CallingThreadMailbox ⇒ m.suspendSwitch.switchOn; m.suspend()
      case m                       ⇒ m.systemEnqueue(actor.self, Suspend())
    }
  }

  override def resume(actor: ActorCell) {
    actor.mailbox match {
      case mbox: CallingThreadMailbox ⇒
        val queue = mbox.queue
        val wasActive = queue.isActive
        val switched = mbox.suspendSwitch.switchOff {
          CallingThreadDispatcherQueues(actor.system).gatherFromAllOtherQueues(mbox, queue)
          mbox.resume()
        }
        if (switched && !wasActive) {
          runQueue(mbox, queue)
        }
      case m ⇒ m.systemEnqueue(actor.self, Resume(false))
    }
  }

  protected[akka] override def systemDispatch(receiver: ActorCell, message: SystemMessage) {
    receiver.mailbox match {
      case mbox: CallingThreadMailbox ⇒
        mbox.systemEnqueue(receiver.self, message)
        val queue = mbox.queue
        if (!queue.isActive) {
          queue.enter
          runQueue(mbox, queue)
        }
      case m ⇒ m.systemEnqueue(receiver.self, message)
    }
  }

  protected[akka] override def dispatch(receiver: ActorCell, handle: Envelope) {
    receiver.mailbox match {
      case mbox: CallingThreadMailbox ⇒
        val queue = mbox.queue
        val execute = mbox.suspendSwitch.fold {
          queue.q.enqueue(receiver.self, handle)
          false
        } {
          queue.q.enqueue(receiver.self, handle)
          if (!queue.isActive) {
            queue.enter
            true
          } else false
        }
        if (execute) runQueue(mbox, queue)
      case m ⇒ m.enqueue(receiver.self, handle)
    }
  }

  protected[akka] override def executeTask(invocation: TaskInvocation) { invocation.run }

  /*
   * This method must be called with this thread's queue, which must already
   * have been entered (active). When this method returns, the queue will be
   * inactive.
   *
   * If the catch block is executed, then a non-empty mailbox may be stalled as
   * there is no-one who cares to execute it before the next message is sent or
   * it is suspendSwitch and resumed.
   */
  @tailrec
  private def runQueue(mbox: CallingThreadMailbox, queue: NestingQueue, interruptedex: InterruptedException = null) {
    var intex = interruptedex;
    assert(queue.isActive)
    mbox.ctdLock.lock
    val recurse = try {
      mbox.processAllSystemMessages()
      val handle = mbox.suspendSwitch.fold[Envelope] {
        queue.leave
        null
      } {
        val ret = if (mbox.isClosed) null else queue.q.dequeue()
        if (ret eq null) queue.leave
        ret
      }
      if (handle ne null) {
        try {
          if (Mailbox.debug) println(mbox.actor.self + " processing message " + handle)
          mbox.actor.invoke(handle)
          true
        } catch {
          case ie: InterruptedException ⇒
            log.error(ie, "Interrupted during message processing")
            Thread.currentThread().interrupt()
            intex = ie
            true
          case NonFatal(e) ⇒
            log.error(e, "Error during message processing")
            queue.leave
            false
        }
      } else if (queue.isActive) {
        queue.leave
        false
      } else false
    } catch {
      case NonFatal(e) ⇒ queue.leave; throw e
    } finally {
      mbox.ctdLock.unlock
    }
    if (recurse) {
      runQueue(mbox, queue, intex)
    } else {
      if (intex ne null) {
        Thread.interrupted // clear flag
        throw intex
      }
    }
  }
}

class CallingThreadDispatcherConfigurator(config: Config, prerequisites: DispatcherPrerequisites)
  extends MessageDispatcherConfigurator(config, prerequisites) {

  private val instance = new CallingThreadDispatcher(prerequisites, mailboxType())

  override def dispatcher(): MessageDispatcher = instance
}

class NestingQueue(val q: MessageQueue) {
  @volatile
  private var active = false
  def enter { if (active) sys.error("already active") else active = true }
  def leave { if (!active) sys.error("not active") else active = false }
  def isActive = active
}

class CallingThreadMailbox(_receiver: akka.actor.Cell, val mailboxType: MailboxType)
  extends Mailbox(null) with DefaultSystemMessageQueue {

  val system = _receiver.system
  val self = _receiver.self

  private val q = new ThreadLocal[NestingQueue]() {
    override def initialValue = {
      val queue = new NestingQueue(mailboxType.create(Some(self), Some(system)))
      CallingThreadDispatcherQueues(system).registerQueue(CallingThreadMailbox.this, queue)
      queue
    }
  }

  override def enqueue(receiver: ActorRef, msg: Envelope): Unit = q.get.q.enqueue(receiver, msg)
  override def dequeue(): Envelope = throw new UnsupportedOperationException("CallingThreadMailbox cannot dequeue normally")
  override def hasMessages: Boolean = q.get.q.hasMessages
  override def numberOfMessages: Int = 0

  def queue = q.get

  val ctdLock = new ReentrantLock
  val suspendSwitch = new Switch

  override def cleanUp(): Unit = {
    /*
     * This is called from dispatcher.unregister, i.e. under this.lock. If
     * another thread obtained a reference to this mailbox and enqueues after
     * the gather operation, tough luck: no guaranteed delivery to deadLetters.
     */
    suspendSwitch.locked {
      val q = queue
      CallingThreadDispatcherQueues(actor.system).gatherFromAllOtherQueues(this, q)
      super.cleanUp()
      q.q.cleanUp(actor.self, actor.systemImpl.deadLetterQueue)
    }
  }
}
