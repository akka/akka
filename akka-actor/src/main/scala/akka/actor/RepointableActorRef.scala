/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import java.io.ObjectStreamException
import java.util.{ LinkedList ⇒ JLinkedList, ListIterator ⇒ JListIterator }
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import scala.annotation.tailrec

import akka.actor.dungeon.ChildrenContainer
import akka.event.Logging.Warning
import akka.util.Unsafe
import akka.dispatch._
import akka.dispatch.sysmsg._
import util.Try

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

  import AbstractActorRef.{ cellOffset, lookupOffset }

  /*
   * H E R E   B E   D R A G O N S !
   *
   * There are two main functions of a Cell: message queueing and child lookup.
   * When switching out the UnstartedCell for its real replacement, the former
   * must be switched after all messages have been drained from the temporary
   * queue into the real mailbox, while the latter must be switched before
   * processing the very first message (i.e. before Cell.start()). Hence there
   * are two refs here, one for each function, and they are switched just so.
   */
  @volatile private var _cellDoNotCallMeDirectly: Cell = _
  @volatile private var _lookupDoNotCallMeDirectly: Cell = _

  def underlying: Cell = Unsafe.instance.getObjectVolatile(this, cellOffset).asInstanceOf[Cell]
  def lookup = Unsafe.instance.getObjectVolatile(this, lookupOffset).asInstanceOf[Cell]

  @tailrec final def swapCell(next: Cell): Cell = {
    val old = underlying
    if (Unsafe.instance.compareAndSwapObject(this, cellOffset, old, next)) old else swapCell(next)
  }

  @tailrec final def swapLookup(next: Cell): Cell = {
    val old = lookup
    if (Unsafe.instance.compareAndSwapObject(this, lookupOffset, old, next)) old else swapLookup(next)
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
  def initialize(async: Boolean): this.type =
    underlying match {
      case null ⇒
        swapCell(new UnstartedCell(system, this, props, supervisor))
        swapLookup(underlying)
        supervisor.sendSystemMessage(Supervise(this, async))
        if (!async) point()
        this
      case other ⇒ throw new IllegalStateException("initialize called more than once!")
    }

  /**
   * This method is supposed to be called by the supervisor in handleSupervise()
   * to replace the UnstartedCell with the real one. It assumes no concurrent
   * modification of the `underlying` field, though it is safe to send messages
   * at any time.
   */
  def point(): this.type =
    underlying match {
      case u: UnstartedCell ⇒
        /*
         * The problem here was that if the real actor (which will start running
         * at cell.start()) creates children in its constructor, then this may
         * happen before the swapCell in u.replaceWith, meaning that those
         * children cannot be looked up immediately, e.g. if they shall become
         * routees.
         */
        val cell = newCell(u)
        swapLookup(cell)
        cell.start()
        u.replaceWith(cell)
        this
      case null ⇒ throw new IllegalStateException("underlying cell is null")
      case _    ⇒ this // this happens routinely for things which were created async=false
    }

  /**
   * This is called by activate() to obtain the cell which is to replace the
   * unstarted cell. The cell must be fully functional.
   */
  def newCell(old: UnstartedCell): Cell =
    new ActorCell(system, this, props, supervisor).init(sendSupervise = false)

  def start(): Unit = ()

  def suspend(): Unit = underlying.suspend()

  def resume(causedByFailure: Throwable): Unit = underlying.resume(causedByFailure)

  def stop(): Unit = underlying.stop()

  def restart(cause: Throwable): Unit = underlying.restart(cause)

  def isStarted: Boolean = underlying match {
    case _: UnstartedCell ⇒ false
    case null             ⇒ throw new IllegalStateException("isStarted called before initialized")
    case _                ⇒ true
  }

  @deprecated("Use context.watch(actor) and receive Terminated(actor)", "2.2") def isTerminated: Boolean = underlying.isTerminated

  def provider: ActorRefProvider = system.provider

  def isLocal: Boolean = underlying.isLocal

  def getParent: InternalActorRef = underlying.parent

  def getChild(name: Iterator[String]): InternalActorRef =
    if (name.hasNext) {
      name.next match {
        case ".." ⇒ getParent.getChild(name)
        case ""   ⇒ getChild(name)
        case other ⇒
          val (childName, uid) = ActorCell.splitNameAndUid(other)
          lookup.getChildByName(childName) match {
            case Some(crs: ChildRestartStats) if uid == ActorCell.undefinedUid || uid == crs.uid ⇒
              crs.child.asInstanceOf[InternalActorRef].getChild(name)
            case _ ⇒ Nobody
          }
      }
    } else this

  def !(message: Any)(implicit sender: ActorRef = Actor.noSender) = underlying.sendMessage(message, sender)

  def sendSystemMessage(message: SystemMessage) = underlying.sendSystemMessage(message)

  @throws(classOf[java.io.ObjectStreamException])
  protected def writeReplace(): AnyRef = SerializedActorRef(this)
}

private[akka] class UnstartedCell(val systemImpl: ActorSystemImpl,
                                  val self: RepointableActorRef,
                                  val props: Props,
                                  val supervisor: InternalActorRef) extends Cell {

  /*
   * This lock protects all accesses to this cell’s queues. It also ensures
   * safe switching to the started ActorCell.
   */
  private[this] final val lock = new ReentrantLock

  // use Envelope to keep on-send checks in the same place ACCESS MUST BE PROTECTED BY THE LOCK
  private[this] final val queue = new JLinkedList[Any]()

  import systemImpl.settings.UnstartedPushTimeout.{ duration ⇒ timeout }

  def replaceWith(cell: Cell): Unit = locked {
    try {
      while (!queue.isEmpty) {
        queue.poll() match {
          case s: SystemMessage ⇒ cell.sendSystemMessage(s)
          case e: Envelope      ⇒ cell.sendMessage(e)
        }
      }
    } finally {
      self.swapCell(cell)
    }
  }

  def system: ActorSystem = systemImpl
  def start(): this.type = this
  def suspend(): Unit = sendSystemMessage(Suspend())
  def resume(causedByFailure: Throwable): Unit = sendSystemMessage(Resume(causedByFailure))
  def restart(cause: Throwable): Unit = sendSystemMessage(Recreate(cause))
  def stop(): Unit = sendSystemMessage(Terminate())
  def isTerminated: Boolean = locked {
    val cell = self.underlying
    if (cellIsReady(cell)) cell.isTerminated else false
  }
  def parent: InternalActorRef = supervisor
  def childrenRefs: ChildrenContainer = ChildrenContainer.EmptyChildrenContainer
  def getChildByName(name: String): Option[ChildRestartStats] = None

  def sendMessage(msg: Envelope): Unit = {
    if (lock.tryLock(timeout.length, timeout.unit)) {
      try {
        val cell = self.underlying
        if (cellIsReady(cell)) {
          cell.sendMessage(msg)
        } else if (!queue.offer(msg)) {
          system.eventStream.publish(Warning(self.path.toString, getClass, "dropping message of type " + msg.message.getClass + " due to enqueue failure"))
          system.deadLetters.tell(DeadLetter(msg.message, msg.sender, self), msg.sender)
        } else if (Mailbox.debug) println(s"$self temp queueing ${msg.message} from ${msg.sender}")
      } finally lock.unlock()
    } else {
      system.eventStream.publish(Warning(self.path.toString, getClass, "dropping message of type" + msg.message.getClass + " due to lock timeout"))
      system.deadLetters.tell(DeadLetter(msg.message, msg.sender, self), msg.sender)
    }
  }

  def sendSystemMessage(msg: SystemMessage): Unit = {
    lock.lock // we cannot lose system messages, ever, and we cannot throw an Error from here as well
    try {
      val cell = self.underlying
      if (cellIsReady(cell)) {
        cell.sendSystemMessage(msg)
      } else {
        // systemMessages that are sent during replace need to jump to just after the last system message in the queue, so it's processed before other messages
        val wasEnqueued = if ((self.lookup ne this) && (self.underlying eq this) && !queue.isEmpty()) {
          @tailrec def tryEnqueue(i: JListIterator[Any] = queue.listIterator(), insertIntoIndex: Int = -1): Boolean =
            if (i.hasNext())
              tryEnqueue(i,
                if (i.next().isInstanceOf[SystemMessage]) i.nextIndex() // update last sysmsg seen so far
                else insertIntoIndex) // or just keep the last seen one
            else if (insertIntoIndex == -1) queue.offer(msg)
            else Try(queue.add(insertIntoIndex, msg)).isSuccess
          tryEnqueue()
        } else queue.offer(msg)

        if (!wasEnqueued) {
          system.eventStream.publish(Warning(self.path.toString, getClass, "dropping system message " + msg + " due to enqueue failure"))
          system.deadLetters ! DeadLetter(msg, self, self)
        } else if (Mailbox.debug) println(s"$self temp queueing system $msg")
      }
    } finally lock.unlock()
  }

  def isLocal = true

  private[this] final def cellIsReady(cell: Cell): Boolean = (cell ne this) && (cell ne null)

  def hasMessages: Boolean = locked {
    val cell = self.underlying
    if (cellIsReady(cell)) cell.hasMessages else !queue.isEmpty
  }

  def numberOfMessages: Int = locked {
    val cell = self.underlying
    if (cellIsReady(cell)) cell.numberOfMessages else queue.size
  }

  private[this] final def locked[T](body: ⇒ T): T = {
    lock.lock()
    try body finally lock.unlock()
  }

}
