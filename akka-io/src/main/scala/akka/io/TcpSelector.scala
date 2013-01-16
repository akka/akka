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

class TcpSelector(manager: ActorRef) extends Actor with ActorLogging {
  @volatile var childrenKeys = HashMap.empty[String, SelectionKey]
  var channelsOpened = 0L
  var channelsClosed = 0L
  val sequenceNumber = Iterator.from(0)
  val settings = Tcp(context.system).Settings
  val selectorManagementDispatcher = context.system.dispatchers.lookup(settings.SelectorDispatcher)
  val selector = SelectorProvider.provider.openSelector
  val doSelect: () ⇒ Int =
    settings.SelectTimeout match {
      case Duration.Zero ⇒ () ⇒ selector.selectNow()
      case Duration.Inf  ⇒ () ⇒ selector.select()
      case x             ⇒ val millis = x.toMillis; () ⇒ selector.select(millis)
    }

  selectorManagementDispatcher.execute(select) // start selection "loop"

  def receive: Receive = {
    case WriteInterest  ⇒ execute(enableInterest(OP_WRITE, sender))
    case ReadInterest   ⇒ execute(enableInterest(OP_READ, sender))
    case AcceptInterest ⇒ execute(enableInterest(OP_ACCEPT, sender))

    case CreateConnection(channel, handler, options) ⇒
      val connection = context.actorOf(
        props = Props(
          creator = () ⇒ new TcpIncomingConnection(self, channel, handler, options),
          dispatcher = settings.WorkerDispatcher),
        name = nextName)
      execute(registerIncomingConnection(channel, handler))
      context.watch(connection)
      channelsOpened += 1

    case cmd: Connect ⇒
      handleConnect(cmd, settings.SelectorAssociationRetries, sender)

    case Retry(cmd: Connect, retriesLeft, commander) ⇒
      handleConnect(cmd, retriesLeft, commander)

    case RegisterOutgoingConnection(channel) ⇒
      execute(registerOutgoingConnection(channel, sender))

    case cmd: Bind ⇒
      handleBind(cmd, settings.SelectorAssociationRetries, sender)

    case Retry(cmd: Bind, retriesLeft, commander) ⇒
      handleBind(cmd, retriesLeft, commander)

    case RegisterServerSocketChannel(channel) ⇒
      execute(registerListener(channel, sender))

    case Terminated(child) ⇒
      execute(unregister(child))
      channelsClosed += 1

    case GetStats ⇒
      sender ! SelectorStats(channelsOpened, channelsClosed)
  }

  override def postStop() {
    try {
      import scala.collection.JavaConverters._
      selector.keys.asScala.foreach(_.channel.close())
      selector.close()
    } catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing selector or key")
    }
  }

  // we can never recover from failures of a connection or listener child
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def handleConnect(cmd: Connect, retriesLeft: Int, commander: ActorRef): Unit = {
    log.debug("Executing {}", cmd)
    if (canHandleMoreChannels) {
      val connection = context.actorOf(
        props = Props(
          creator = () ⇒ new TcpOutgoingConnection(self, commander, cmd.remoteAddress, cmd.localAddress, cmd.options),
          dispatcher = settings.WorkerDispatcher),
        name = nextName)
      context.watch(connection)
      channelsOpened += 1
    } else sender ! Reject(cmd, retriesLeft, commander)
  }

  def handleBind(cmd: Bind, retriesLeft: Int, commander: ActorRef): Unit = {
    log.debug("Executing {}", cmd)
    if (canHandleMoreChannels) {
      val listener = context.actorOf(
        props = Props(
          creator = () ⇒ new TcpListener(manager, self, cmd.handler, cmd.endpoint, cmd.backlog, commander, cmd.options),
          dispatcher = settings.WorkerDispatcher),
        name = nextName)
      context.watch(listener)
      channelsOpened += 1
    } else sender ! Reject(cmd, retriesLeft, commander)
  }

  def nextName = sequenceNumber.next().toString

  def canHandleMoreChannels = childrenKeys.size < settings.MaxChannelsPerSelector

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

  // TODO: evaluate whether we could run this on the TcpSelector actor itself rather than
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

  def unregister(child: ActorRef) =
    new Task {
      def tryRun() {
        childrenKeys = childrenKeys - child.path.name
      }
    }

  val select = new Task {
    def tryRun() {
      if (doSelect() > 0) {
        val keys = selector.selectedKeys
        val iterator = keys.iterator()
        while (iterator.hasNext) {
          val key = iterator.next
          val connection = key.attachment.asInstanceOf[ActorRef]
          if (key.isValid) {
            if (key.isReadable) connection ! ChannelReadable
            if (key.isWritable) connection ! ChannelWritable
            else if (key.isAcceptable) connection ! ChannelAcceptable
            else if (key.isConnectable) connection ! ChannelConnectable
            key.interestOps(0) // prevent immediate reselection by always clearing
          } else log.warning("Invalid selection key: {}", key)
        }
        keys.clear() // we need to remove the selected keys from the set, otherwise they remain selected
      }
      selectorManagementDispatcher.execute(this) // re-schedules select behind all currently queued tasks
    }
  }

  abstract class Task extends Runnable {
    def tryRun()
    def run() {
      try tryRun()
      catch {
        case NonFatal(e) ⇒ log.error(e, "Error during selector management task: {}", e)
      }
    }
  }
}