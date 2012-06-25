/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.util.Unsafe
import scala.annotation.tailrec
import akka.dispatch.SystemMessage
import akka.dispatch.Mailbox
import akka.dispatch.Terminate
import akka.dispatch.Envelope
import akka.dispatch.Supervise
import akka.dispatch.Create
import akka.dispatch.MessageDispatcher
import java.util.concurrent.locks.ReentrantLock
import akka.event.Logging.Warning
import scala.collection.mutable.Queue

/**
 * This actor ref starts out with some dummy cell (by default just enqueuing
 * messages into vectors protected by ReentrantLock), it must be initialize()’d
 * before it can be sent to, and it will be activate()’d by its supervisor in
 * response to the Supervise() message, which will replace the contained Cell
 * with a fully functional one, transfer all messages from dummy to real queue
 * and swap out the cell ref.
 */
private[akka] class RepointableActorRef(
  val system: ActorSystemImpl,
  val props: Props,
  val supervisor: InternalActorRef,
  val path: ActorPath)
  extends ActorRefWithCell with RepointableRef {

  import AbstractActorRef.cellOffset

  @volatile private var _cellDoNotCallMeDirectly: Cell = _

  def underlying: Cell = Unsafe.instance.getObjectVolatile(this, cellOffset).asInstanceOf[Cell]

  @tailrec final def swapCell(next: Cell): Cell = {
    val old = underlying
    if (Unsafe.instance.compareAndSwapObject(this, cellOffset, old, next)) old else swapCell(next)
  }

  /**
   * Initialize: make a dummy cell which holds just a mailbox, then tell our
   * supervisor that we exist so that he can create the real Cell in
   * handleSupervise().
   *
   * Call twice on your own peril!
   *
   * This is protected so that others can have different initialization.
   */
  def initialize(): this.type = {
    swapCell(new UnstartedCell(system, this, props, supervisor))
    supervisor.sendSystemMessage(Supervise(this))
    this
  }

  /**
   * This method is supposed to be called by the supervisor in handleSupervise()
   * to replace the UnstartedCell with the real one. It assumes no concurrent
   * modification of the `underlying` field, though it is safe to send messages
   * at any time.
   */
  def activate(): this.type = {
    underlying match {
      case u: UnstartedCell ⇒ u.replaceWith(newCell())
      case _                ⇒ // this happens routinely for things which were created async=false
    }
    this
  }

  /**
   * This is called by activate() to obtain the cell which is to replace the
   * unstarted cell. The cell must be fully functional.
   */
  def newCell(): Cell = new ActorCell(system, this, props, supervisor).start()

  def suspend(): Unit = underlying.suspend()

  def resume(): Unit = underlying.resume()

  def stop(): Unit = underlying.stop()

  def restart(cause: Throwable): Unit = underlying.restart(cause)

  def isStarted: Boolean = !underlying.isInstanceOf[UnstartedCell]

  def isTerminated: Boolean = underlying.isTerminated

  def provider: ActorRefProvider = system.provider

  def isLocal: Boolean = underlying.isLocal

  def getParent: InternalActorRef = underlying.parent

  def getChild(name: Iterator[String]): InternalActorRef =
    if (name.hasNext) {
      name.next match {
        case ".." ⇒ getParent.getChild(name)
        case ""   ⇒ getChild(name)
        case other ⇒
          underlying.childrenRefs.getByName(other) match {
            case Some(crs) ⇒ crs.child.asInstanceOf[InternalActorRef].getChild(name)
            case None      ⇒ Nobody
          }
      }
    } else this

  def !(message: Any)(implicit sender: ActorRef = null) = underlying.tell(message, sender)

  def sendSystemMessage(message: SystemMessage) = underlying.sendSystemMessage(message)

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(path)
}

private[akka] class UnstartedCell(val systemImpl: ActorSystemImpl, val self: RepointableActorRef, val props: Props, val supervisor: InternalActorRef)
  extends Cell {

  /*
   * This lock protects all accesses to this cell’s queues. It also ensures 
   * safe switching to the started ActorCell.
   */
  val lock = new ReentrantLock

  // use Envelope to keep on-send checks in the same place
  val queue: Queue[Envelope] = Queue()
  val systemQueue: Queue[SystemMessage] = Queue()

  def replaceWith(cell: Cell): Unit = {
    lock.lock()
    try {
      /*
       * The CallingThreadDispatcher nicely dives under the ReentrantLock and
       * breaks things by enqueueing into stale queues from within the message
       * processing which happens in-line for sendSystemMessage() and tell().
       * Since this is the only possible way to f*ck things up within this 
       * lock, double-tap (well, N-tap, really); concurrent modification is
       * still not possible because we’re the only thread accessing the queues.
       */
      var interrupted = false
      while (systemQueue.nonEmpty || queue.nonEmpty) {
        while (systemQueue.nonEmpty) {
          val msg = systemQueue.dequeue()
          try cell.sendSystemMessage(msg)
          catch {
            case _: InterruptedException ⇒ interrupted = true
          }
        }
        if (queue.nonEmpty) {
          val envelope = queue.dequeue()
          try cell.tell(envelope.message, envelope.sender)
          catch {
            case _: InterruptedException ⇒ interrupted = true
          }
        }
      }
      if (interrupted) throw new InterruptedException
    } finally try
      self.swapCell(cell)
    finally
      lock.unlock()
  }

  def system: ActorSystem = systemImpl
  def suspend(): Unit = {}
  def resume(): Unit = {}
  def restart(cause: Throwable): Unit = {}
  def stop(): Unit = sendSystemMessage(Terminate())
  def isTerminated: Boolean = false
  def parent: InternalActorRef = supervisor
  def childrenRefs: ActorCell.ChildrenContainer = ActorCell.EmptyChildrenContainer
  def tell(message: Any, sender: ActorRef): Unit = {
    lock.lock()
    try {
      if (self.underlying eq this) queue enqueue Envelope(message, sender, system)
      else self.underlying.tell(message, sender)
    } finally {
      lock.unlock()
    }
  }
  def sendSystemMessage(msg: SystemMessage): Unit = {
    lock.lock()
    try {
      if (self.underlying eq this) systemQueue enqueue msg
      else self.underlying.sendSystemMessage(msg)
    } finally {
      lock.unlock()
    }
  }
  def isLocal = true
  def hasMessages: Boolean = {
    lock.lock()
    try {
      if (self.underlying eq this) !queue.isEmpty
      else self.underlying.hasMessages
    } finally {
      lock.unlock()
    }
  }
  def numberOfMessages: Int = {
    lock.lock()
    try {
      if (self.underlying eq this) queue.size
      else self.underlying.numberOfMessages
    } finally {
      lock.unlock()
    }
  }

}