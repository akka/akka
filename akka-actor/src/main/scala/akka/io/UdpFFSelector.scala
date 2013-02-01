/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.io.UdpFF._
import akka.actor._
import java.lang.Runnable
import java.nio.channels.{ DatagramChannel, SelectionKey }
import java.nio.channels.SelectionKey._
import java.nio.channels.spi.SelectorProvider
import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

private[io] object UdpFFSelector {
  case class Retry(command: Command, retriesLeft: Int) { require(retriesLeft >= 0) }
  case class RegisterDatagramChannel(channel: DatagramChannel, initialOps: Int) extends Command

  case object ChannelReadable
  case object ChannelWritable
  case object ReadInterest
  case object WriteInterest
}

private[io] class UdpFFSelector(manager: ActorRef, udp: UdpFFExt) extends Actor with ActorLogging {

  import UdpFFSelector._
  import udp.settings._

  @volatile var childrenKeys = immutable.HashMap.empty[String, SelectionKey]
  val sequenceNumber = Iterator from 0
  val selectorManagementDispatcher = context.system.dispatchers.lookup(SelectorDispatcher)
  val selector = SelectorProvider.provider.openSelector
  val OP_READ_AND_WRITE = OP_READ + OP_WRITE // compile-time constant

  def receive: Receive = {
    case WriteInterest ⇒ execute(enableInterest(OP_WRITE, sender))
    case ReadInterest  ⇒ execute(enableInterest(OP_READ, sender))
    case StopReading   ⇒ execute(disableInterest(OP_READ, sender))

    case cmd: Bind ⇒
      handleBind(cmd, SelectorAssociationRetries)

    case RegisterDatagramChannel(channel, initialOps) ⇒
      execute(registerDatagramChannel(channel, sender, initialOps))

    case Retry(command, 0) ⇒
      log.warning("Command '{}' failed since all selectors are at capacity", command)
      sender ! CommandFailed(command)

    case Retry(cmd: Bind, retriesLeft) ⇒
      handleBind(cmd, retriesLeft)

    case Terminated(child) ⇒
      execute(unregister(child))
  }

  override def postStop() {
    try {
      try {
        val iterator = selector.keys.iterator
        while (iterator.hasNext) iterator.next().channel.close()
      } finally selector.close()
    } catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing selector or key")
    }
  }

  // we can never recover from failures of a connection or listener child
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def handleBind(cmd: Bind, retriesLeft: Int): Unit =
    withCapacityProtection(cmd, retriesLeft) {
      import cmd._
      val commander = sender
      spawnChild(() ⇒ new UdpFFListener(context.parent, handler, endpoint, commander, udp, options))
    }

  def withCapacityProtection(cmd: Command, retriesLeft: Int)(body: ⇒ Unit): Unit = {
    log.debug("Executing {}", cmd)
    if (MaxChannelsPerSelector == -1 || childrenKeys.size < MaxChannelsPerSelector) {
      body
    } else {
      log.warning("Rejecting '{}' with {} retries left, retrying...", cmd, retriesLeft)
      context.parent forward Retry(cmd, retriesLeft - 1)
    }
  }

  def spawnChild(creator: () ⇒ Actor) =
    context.watch {
      context.actorOf(
        props = Props(creator, dispatcher = WorkerDispatcher),
        name = sequenceNumber.next().toString)
    }

  //////////////// Management Tasks scheduled via the selectorManagementDispatcher /////////////

  def execute(task: Task): Unit = {
    selectorManagementDispatcher.execute(task)
    selector.wakeup()
  }

  def updateKeyMap(child: ActorRef, key: SelectionKey): Unit =
    childrenKeys = childrenKeys.updated(child.path.name, key)

  def registerDatagramChannel(channel: DatagramChannel, connection: ActorRef, initialOps: Int) =
    new Task {
      def tryRun() {
        val key = channel.register(selector, initialOps, connection)
        updateKeyMap(connection, key)
      }
    }

  // TODO: evaluate whether we could run the following two tasks directly on the TcpSelector actor itself rather than
  // on the selector-management-dispatcher. The trade-off would be using a ConcurrentHashMap
  // rather than an unsynchronized one, but since switching interest ops is so frequent
  // the change might be beneficial, provided the underlying implementation really is thread-safe
  // and behaves consistently on all platforms.
  def enableInterest(op: Int, connection: ActorRef) =
    new Task {
      def tryRun() {
        val key = childrenKeys(connection.path.name)
        key.interestOps(key.interestOps | op)
      }
    }

  def disableInterest(op: Int, connection: ActorRef) =
    new Task {
      def tryRun() {
        val key = childrenKeys(connection.path.name)
        key.interestOps(key.interestOps & ~op)
      }
    }

  def unregister(child: ActorRef) =
    new Task {
      def tryRun() {
        childrenKeys = childrenKeys - child.path.name
      }
    }

  val select = new Task {
    val doSelect: () ⇒ Int =
      SelectTimeout match {
        case Duration.Zero ⇒ () ⇒ selector.selectNow()
        case Duration.Inf  ⇒ () ⇒ selector.select()
        case x             ⇒ val millis = x.toMillis; () ⇒ selector.select(millis)
      }
    def tryRun() {
      if (doSelect() > 0) {
        val keys = selector.selectedKeys
        val iterator = keys.iterator()
        while (iterator.hasNext) {
          val key = iterator.next
          if (key.isValid) {
            key.interestOps(0) // prevent immediate reselection by always clearing
            val connection = key.attachment.asInstanceOf[ActorRef]
            key.readyOps match {
              case OP_READ           ⇒ connection ! ChannelReadable
              case OP_WRITE          ⇒ connection ! ChannelWritable
              case OP_READ_AND_WRITE ⇒ connection ! ChannelWritable; connection ! ChannelReadable
              case x                 ⇒ log.warning("Invalid readyOps: {}", x)
            }
          } else log.warning("Invalid selection key: {}", key)
        }
        keys.clear() // we need to remove the selected keys from the set, otherwise they remain selected
      }
      selectorManagementDispatcher.execute(this) // re-schedules select behind all currently queued tasks
    }
  }

  selectorManagementDispatcher.execute(select) // start selection "loop"

  abstract class Task extends Runnable {
    def tryRun()
    def run() {
      try tryRun()
      catch {
        case _: java.nio.channels.ClosedSelectorException ⇒ // ok, expected during shutdown
        case NonFatal(e)                                  ⇒ log.error(e, "Error during selector management task: {}", e)
      }
    }
  }
}

