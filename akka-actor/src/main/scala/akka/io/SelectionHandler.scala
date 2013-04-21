/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.util.{ Set ⇒ JSet, Iterator ⇒ JIterator }
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.channels.{ Selector, SelectableChannel, SelectionKey, CancelledKeyException, ClosedSelectorException, ClosedChannelException }
import java.nio.channels.SelectionKey._
import java.nio.channels.spi.{ AbstractSelector, SelectorProvider }
import scala.annotation.{ tailrec, switch }
import scala.util.control.NonFatal
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor._
import com.typesafe.config.Config
import akka.io.IO.HasFailureMessage
import akka.util.Helpers.Requiring
import akka.event.LoggingAdapter
import akka.util.SerializedSuspendableExecutionContext

abstract class SelectionHandlerSettings(config: Config) {
  import config._

  val MaxChannels: Int = getString("max-channels") match {
    case "unlimited" ⇒ -1
    case _           ⇒ getInt("max-channels") requiring (_ > 0, "max-channels must be > 0 or 'unlimited'")
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

private[io] class SelectionHandler(settings: SelectionHandlerSettings) extends Actor with ActorLogging {
  import SelectionHandler._
  import settings._

  final val OP_READ_AND_WRITE = OP_READ | OP_WRITE // compile-time constant

  private val wakeUp = new AtomicBoolean(false)
  @volatile var childrenKeys = immutable.HashMap.empty[String, SelectionKey]
  var sequenceNumber = 0
  val selectorManagementEC = {
    val dispatcher = context.system.dispatchers.lookup(SelectorDispatcher)
    SerializedSuspendableExecutionContext(dispatcher.throughput)(dispatcher)
  }

  val selector = SelectorProvider.provider.openSelector

  def receive: Receive = {
    case WriteInterest                        ⇒ execute(enableInterest(OP_WRITE, sender))
    case ReadInterest                         ⇒ execute(enableInterest(OP_READ, sender))
    case AcceptInterest                       ⇒ execute(enableInterest(OP_ACCEPT, sender))

    case DisableReadInterest                  ⇒ execute(disableInterest(OP_READ, sender))

    case cmd: WorkerForCommand                ⇒ spawnChildWithCapacityProtection(cmd, SelectorAssociationRetries)

    case RegisterChannel(channel, initialOps) ⇒ execute(registerChannel(channel, sender, initialOps))

    case Retry(cmd, retriesLeft)              ⇒ spawnChildWithCapacityProtection(cmd, retriesLeft)

    case Terminated(child)                    ⇒ execute(unregister(child))
  }

  override def postStop(): Unit = execute(terminate())

  // we can never recover from failures of a connection or listener child
  override def supervisorStrategy = SupervisorStrategy.stoppingStrategy

  def spawnChildWithCapacityProtection(cmd: WorkerForCommand, retriesLeft: Int): Unit = {
    if (TraceLogging) log.debug("Executing [{}]", cmd)
    if (MaxChannelsPerSelector == -1 || childrenKeys.size < MaxChannelsPerSelector) {
      val newName = sequenceNumber.toString
      sequenceNumber += 1
      context watch context.actorOf(props = cmd.childProps.withDispatcher(WorkerDispatcher), name = newName)
    } else {
      if (retriesLeft >= 1) {
        log.warning("Rejecting [{}] with [{}] retries left, retrying...", cmd, retriesLeft)
        context.parent forward Retry(cmd, retriesLeft - 1)
      } else {
        log.warning("Rejecting [{}] with no retries left, aborting...", cmd)
        cmd.commander ! cmd.apiCommand.failureMessage // I can't do it, Captain!
      }
    }
  }

  //////////////// Management Tasks scheduled via the selectorManagementEC /////////////

  def execute(task: Task): Unit = {
    selectorManagementEC.execute(task)
    if (wakeUp.compareAndSet(false, true)) selector.wakeup() // Avoiding syscall and trade off with LOCK CMPXCHG
  }

  def registerChannel(channel: SelectableChannel, channelActor: ActorRef, initialOps: Int): Task =
    new Task {
      def tryRun() {
        childrenKeys = childrenKeys.updated(channelActor.path.name, channel.register(selector, initialOps, channelActor))
        channelActor ! ChannelRegistered
      }
    }

  // Always set the interest keys on the selector thread according to benchmark
  def enableInterest(ops: Int, connection: ActorRef) =
    new Task {
      def tryRun() {
        val key = childrenKeys(connection.path.name)
        val currentOps = key.interestOps
        val newOps = currentOps | ops
        if (newOps != currentOps) key.interestOps(newOps)
      }
    }

  def disableInterest(ops: Int, connection: ActorRef) =
    new Task {
      def tryRun() {
        val key = childrenKeys(connection.path.name)
        val currentOps = key.interestOps
        val newOps = currentOps & ~ops
        if (newOps != currentOps) key.interestOps(newOps)
      }
    }

  def unregister(child: ActorRef) =
    new Task { def tryRun() { childrenKeys = childrenKeys - child.path.name } }

  def terminate() = new Task {
    def tryRun() {
      // Thorough 'close' of the Selector
      @tailrec def closeNextChannel(it: JIterator[SelectionKey]): Unit = if (it.hasNext) {
        try it.next().channel.close() catch { case NonFatal(e) ⇒ log.error(e, "Error closing channel") }
        closeNextChannel(it)
      }
      try closeNextChannel(selector.keys.iterator) finally selector.close()
    }
  }

  val select = new Task {
    def tryRun(): Unit = {
      wakeUp.set(false) // Reset early, worst-case we do a double-wakeup, but it's supposed to be idempotent so it's just an extra syscall
      if (selector.select() > 0) { // This assumes select return value == selectedKeys.size
        val keys = selector.selectedKeys
        val iterator = keys.iterator()
        while (iterator.hasNext) {
          val key = iterator.next()
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

      // FIXME what is the appropriate error-handling here, shouldn't this task be resubmitted in case of exception?
      selectorManagementEC.execute(this) // re-schedules select behind all currently queued tasks
    }
  }

  selectorManagementEC.execute(select) // start selection "loop"

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