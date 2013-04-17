/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.lang.Runnable
import java.nio.channels.spi.SelectorProvider
import java.nio.channels.{ SelectableChannel, SelectionKey, CancelledKeyException, ClosedSelectorException }
import java.nio.channels.SelectionKey._
import scala.util.control.NonFatal
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor._
import com.typesafe.config.Config
import akka.actor.Terminated
import akka.io.IO.HasFailureMessage
import akka.util.Helpers.Requiring

abstract class SelectionHandlerSettings(config: Config) {
  import config._

  val MaxChannels: Int = getString("max-channels") match {
    case "unlimited" ⇒ -1
    case _           ⇒ getInt("max-channels") requiring (_ > 0, "max-channels must be > 0 or 'unlimited'")
  }
  val SelectTimeout: Duration = getString("select-timeout") match {
    case "infinite" ⇒ Duration.Inf
    case _ ⇒ Duration(getMilliseconds("select-timeout"), MILLISECONDS) requiring (
      _ >= Duration.Zero, "select-timeout must not be negative")
  }
  val SelectorAssociationRetries: Int = getInt("selector-association-retries") requiring (
    _ >= 0, "selector-association-retries must be >= 0")

  val SelectorDispatcher: String = getString("selector-dispatcher")
  val WorkerDispatcher: String = getString("worker-dispatcher")
  val TraceLogging: Boolean = getBoolean("trace-logging")

  def MaxChannelsPerSelector: Int

}

private[io] object SelectionHandler {

  case class WorkerForCommand(apiCommand: HasFailureMessage, commander: ActorRef, childProps: Props)

  case class RegisterChannel(channel: SelectableChannel, initialOps: Int)
  case object ChannelRegistered
  case class Retry(command: WorkerForCommand, retriesLeft: Int) { require(retriesLeft >= 0) }

  case object ChannelConnectable
  case object ChannelAcceptable
  case object ChannelReadable
  case object ChannelWritable
  case object AcceptInterest
  case object ReadInterest
  case object DisableReadInterest
  case object WriteInterest
}

private[io] class SelectionHandler(manager: ActorRef, settings: SelectionHandlerSettings) extends Actor with ActorLogging {
  import SelectionHandler._
  import settings._

  @volatile var childrenKeys = immutable.HashMap.empty[String, SelectionKey]
  val sequenceNumber = Iterator.from(0)
  val selectorManagementDispatcher = context.system.dispatchers.lookup(SelectorDispatcher)
  val selector = SelectorProvider.provider.openSelector
  final val OP_READ_AND_WRITE = OP_READ | OP_WRITE // compile-time constant

  def receive: Receive = {
    case WriteInterest       ⇒ execute(enableInterest(OP_WRITE, sender))
    case ReadInterest        ⇒ execute(enableInterest(OP_READ, sender))
    case AcceptInterest      ⇒ execute(enableInterest(OP_ACCEPT, sender))

    case DisableReadInterest ⇒ execute(disableInterest(OP_READ, sender))

    case cmd: WorkerForCommand ⇒
      withCapacityProtection(cmd, SelectorAssociationRetries) { spawnChild(cmd.childProps) }

    case RegisterChannel(channel, initialOps) ⇒
      execute(registerChannel(channel, sender, initialOps))

    case Retry(WorkerForCommand(cmd, commander, _), 0) ⇒
      commander ! cmd.failureMessage

    case Retry(cmd, retriesLeft) ⇒
      withCapacityProtection(cmd, retriesLeft) { spawnChild(cmd.childProps) }

    case Terminated(child) ⇒
      execute(unregister(child))
  }

  override def postStop() {
    try {
      try {
        val iterator = selector.keys.iterator
        while (iterator.hasNext) {
          val key = iterator.next()
          try key.channel.close()
          catch {
            case NonFatal(e) ⇒ log.error(e, "Error closing channel")
          }
        }
      } finally selector.close()
    } catch {
      case NonFatal(e) ⇒ log.error(e, "Error closing selector")
    }
  }

  // we can never recover from failures of a connection or listener child
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def withCapacityProtection(cmd: WorkerForCommand, retriesLeft: Int)(body: ⇒ Unit): Unit = {
    if (TraceLogging) log.debug("Executing [{}]", cmd)
    if (MaxChannelsPerSelector == -1 || childrenKeys.size < MaxChannelsPerSelector) {
      body
    } else {
      log.warning("Rejecting [{}] with [{}] retries left, retrying...", cmd, retriesLeft)
      context.parent forward Retry(cmd, retriesLeft - 1)
    }
  }

  def spawnChild(props: Props): ActorRef =
    context.watch {
      context.actorOf(
        props = props.withDispatcher(WorkerDispatcher),
        name = sequenceNumber.next().toString)
    }

  //////////////// Management Tasks scheduled via the selectorManagementDispatcher /////////////

  def execute(task: Task): Unit = {
    selectorManagementDispatcher.execute(task)
    selector.wakeup()
  }

  def updateKeyMap(child: ActorRef, key: SelectionKey): Unit =
    childrenKeys = childrenKeys.updated(child.path.name, key)

  def registerChannel(channel: SelectableChannel, channelActor: ActorRef, initialOps: Int): Task =
    new Task {
      def tryRun() {
        updateKeyMap(channelActor, channel.register(selector, initialOps, channelActor))
        channelActor ! ChannelRegistered
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
        case x             ⇒ { val millis = x.toMillis; () ⇒ selector.select(millis) }
      }
    def tryRun() {
      if (doSelect() > 0) {
        val keys = selector.selectedKeys
        val iterator = keys.iterator()
        while (iterator.hasNext) {
          val key = iterator.next
          if (key.isValid) {
            try {
              // Cache because the performance implications of calling this on different platforms are not clear
              val readyOps = key.readyOps()
              key.interestOps(key.interestOps & ~readyOps) // prevent immediate reselection by always clearing
              val connection = key.attachment.asInstanceOf[ActorRef]
              readyOps match {
                case OP_READ                   ⇒ connection ! ChannelReadable
                case OP_WRITE                  ⇒ connection ! ChannelWritable
                case OP_READ_AND_WRITE         ⇒ { connection ! ChannelWritable; connection ! ChannelReadable }
                case x if (x & OP_ACCEPT) > 0  ⇒ connection ! ChannelAcceptable
                case x if (x & OP_CONNECT) > 0 ⇒ connection ! ChannelConnectable
                case x                         ⇒ log.warning("Invalid readyOps: [{}]", x)
              }
            } catch {
              case _: CancelledKeyException ⇒
              // can be ignored because this exception is triggered when the key becomes invalid
              // because `channel.close()` in `TcpConnection.postStop` is called from another thread
            }
          }
        }
        keys.clear() // we need to remove the selected keys from the set, otherwise they remain selected
      }
      selectorManagementDispatcher.execute(this) // re-schedules select behind all currently queued tasks
    }
  }

  selectorManagementDispatcher.execute(select) // start selection "loop"

  // FIXME: Add possibility to signal failure of task to someone
  abstract class Task extends Runnable {
    def tryRun()
    def run() {
      try tryRun()
      catch {
        case _: CancelledKeyException   ⇒ // ok, can be triggered in `enableInterest` or `disableInterest`
        case _: ClosedSelectorException ⇒ // ok, expected during shutdown
        case NonFatal(e)                ⇒ log.error(e, "Error during selector management task: [{}]", e)
      }
    }
  }
}