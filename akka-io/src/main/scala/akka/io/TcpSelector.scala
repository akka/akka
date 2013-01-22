/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.lang.Runnable
import java.nio.channels.spi.SelectorProvider
import java.nio.channels.{ ServerSocketChannel, SelectionKey, SocketChannel }
import java.nio.channels.SelectionKey._
import scala.util.control.NonFatal
import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import akka.actor._
import Tcp._

private[io] class TcpSelector(manager: ActorRef, tcp: TcpExt) extends Actor with ActorLogging {
  import TcpSelector._
  import tcp.Settings._

  @volatile var childrenKeys = HashMap.empty[String, SelectionKey]
  val sequenceNumber = Iterator.from(0)
  val selectorManagementDispatcher = context.system.dispatchers.lookup(SelectorDispatcher)
  val selector = SelectorProvider.provider.openSelector
  val OP_READ_AND_WRITE = OP_READ + OP_WRITE // compile-time constant

  def receive: Receive = {
    case WriteInterest  ⇒ execute(enableInterest(OP_WRITE, sender))
    case ReadInterest   ⇒ execute(enableInterest(OP_READ, sender))
    case AcceptInterest ⇒ execute(enableInterest(OP_ACCEPT, sender))

    case StopReading    ⇒ execute(disableInterest(OP_READ, sender))

    case cmd: RegisterIncomingConnection ⇒
      handleIncomingConnection(cmd, SelectorAssociationRetries)

    case cmd: Connect ⇒
      handleConnect(cmd, SelectorAssociationRetries)

    case cmd: Bind ⇒
      handleBind(cmd, SelectorAssociationRetries)

    case RegisterOutgoingConnection(channel) ⇒
      execute(registerOutgoingConnection(channel, sender))

    case RegisterServerSocketChannel(channel) ⇒
      execute(registerListener(channel, sender))

    case Retry(command, 0) ⇒
      log.warning("Command '{}' failed since all selectors are at capacity", command)
      sender ! CommandFailed(command)

    case Retry(cmd: RegisterIncomingConnection, retriesLeft) ⇒
      handleIncomingConnection(cmd, retriesLeft)

    case Retry(cmd: Connect, retriesLeft) ⇒
      handleConnect(cmd, retriesLeft)

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

  def handleIncomingConnection(cmd: RegisterIncomingConnection, retriesLeft: Int): Unit =
    withCapacityProtection(cmd, retriesLeft) {
      import cmd._
      val connection = spawnChild(() ⇒ new TcpIncomingConnection(self, channel, tcp, handler, options))
      execute(registerIncomingConnection(channel, connection))
    }

  def handleConnect(cmd: Connect, retriesLeft: Int): Unit =
    withCapacityProtection(cmd, retriesLeft) {
      import cmd._
      val commander = sender
      spawnChild(() ⇒ new TcpOutgoingConnection(self, tcp, commander, remoteAddress, localAddress, options))
    }

  def handleBind(cmd: Bind, retriesLeft: Int): Unit =
    withCapacityProtection(cmd, retriesLeft) {
      import cmd._
      val commander = sender
      spawnChild(() ⇒ new TcpListener(self, handler, endpoint, backlog, commander, tcp.Settings, options))
    }

  def withCapacityProtection(cmd: Command, retriesLeft: Int)(body: ⇒ Unit): Unit = {
    log.debug("Executing {}", cmd)
    if (MaxChannelsPerSelector == 0 || childrenKeys.size < MaxChannelsPerSelector) {
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

  def registerOutgoingConnection(channel: SocketChannel, connection: ActorRef) =
    new Task {
      def tryRun() {
        val key = channel.register(selector, OP_CONNECT, connection)
        updateKeyMap(connection, key)
      }
    }

  def registerListener(channel: ServerSocketChannel, listener: ActorRef) =
    new Task {
      def tryRun() {
        val key = channel.register(selector, OP_ACCEPT, listener)
        updateKeyMap(listener, key)
        listener ! Bound
      }
    }

  def registerIncomingConnection(channel: SocketChannel, connection: ActorRef) =
    new Task {
      def tryRun() {
        // we only enable reading after the user-level connection handler has registered
        val key = channel.register(selector, 0, connection)
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
              case OP_READ                   ⇒ connection ! ChannelReadable
              case OP_WRITE                  ⇒ connection ! ChannelWritable
              case OP_READ_AND_WRITE         ⇒ connection ! ChannelWritable; connection ! ChannelReadable
              case x if (x & OP_ACCEPT) > 0  ⇒ connection ! ChannelAcceptable
              case x if (x & OP_CONNECT) > 0 ⇒ connection ! ChannelConnectable
              case x                         ⇒ log.warning("Invalid readyOps: {}", x)
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

private[io] object TcpSelector {
  case class RegisterOutgoingConnection(channel: SocketChannel)
  case class RegisterServerSocketChannel(channel: ServerSocketChannel)
  case class RegisterIncomingConnection(channel: SocketChannel, handler: ActorRef,
                                        options: Traversable[SocketOption]) extends Tcp.Command
  case class Retry(command: Command, retriesLeft: Int) { require(retriesLeft >= 0) }

  case object ChannelConnectable
  case object ChannelAcceptable
  case object ChannelReadable
  case object ChannelWritable
  case object AcceptInterest
  case object ReadInterest
  case object WriteInterest
}