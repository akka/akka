/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.IOException
import java.nio.channels.SocketChannel
import java.nio.ByteBuffer
import scala.annotation.tailrec
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor._
import akka.util.ByteString
import akka.io.Inet.SocketOption
import akka.io.Tcp._
import akka.io.SelectionHandler._

/**
 * Base class for TcpIncomingConnection and TcpOutgoingConnection.
 *
 * INTERNAL API
 */
private[io] abstract class TcpConnection(val channel: SocketChannel,
                                         val tcp: TcpExt) extends Actor with ActorLogging {
  import tcp.Settings._
  import tcp.bufferPool
  import TcpConnection._
  var pendingWrite: PendingWrite = null

  // Needed to send the ConnectionClosed message in the postStop handler.
  var closedMessage: CloseInformation = null

  def writePending = pendingWrite ne null

  def selector = context.parent

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(commander: ActorRef): Receive = {
    case Register(handler) ⇒
      if (TraceLogging) log.debug("[{}] registered as connection handler", handler)
      doRead(handler, None) // immediately try reading

      context.setReceiveTimeout(Duration.Undefined)
      context.watch(handler) // sign death pact

      context.become(connected(handler))

    case cmd: CloseCommand ⇒
      handleClose(commander, Some(sender), closeResponse(cmd))

    case ReceiveTimeout ⇒
      // after sending `Register` user should watch this actor to make sure
      // it didn't die because of the timeout
      log.warning("Configured registration timeout of [{}] expired, stopping", RegisterTimeout)
      context.stop(self)
  }

  /** normal connected state */
  def connected(handler: ActorRef): Receive = {
    case StopReading     ⇒ selector ! DisableReadInterest
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler, None)

    case write: Write if writePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender ! CommandFailed(write)

    case write: Write if write.data.isEmpty ⇒
      if (write.wantsAck)
        sender ! write.ack

    case write: Write ⇒
      pendingWrite = createWrite(write)
      doWrite(handler)

    case ChannelWritable   ⇒ if (writePending) doWrite(handler)

    case cmd: CloseCommand ⇒ handleClose(handler, Some(sender), closeResponse(cmd))
  }

  /** connection is closing but a write has to be finished first */
  def closingWithPendingWrite(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Receive = {
    case StopReading     ⇒ selector ! DisableReadInterest
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler, closeCommander)

    case ChannelWritable ⇒
      doWrite(handler)
      if (!writePending) // writing is now finished
        handleClose(handler, closeCommander, closedEvent)

    case Abort ⇒ handleClose(handler, Some(sender), Aborted)
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(handler: ActorRef, closeCommander: Option[ActorRef]): Receive = {
    case StopReading     ⇒ selector ! DisableReadInterest
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler, closeCommander)
    case Abort           ⇒ handleClose(handler, Some(sender), Aborted)
  }

  // AUXILIARIES and IMPLEMENTATION

  /** used in subclasses to start the common machinery above once a channel is connected */
  def completeConnect(commander: ActorRef, options: immutable.Traversable[SocketOption]): Unit = {
    // Turn off Nagle's algorithm by default
    channel.socket.setTcpNoDelay(true)
    options.foreach(_.afterConnect(channel.socket))

    commander ! Connected(
      channel.socket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress],
      channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])

    context.setReceiveTimeout(RegisterTimeout)
    context.become(waitingForRegistration(commander))
  }

  def doRead(handler: ActorRef, closeCommander: Option[ActorRef]): Unit = {
    @tailrec def innerRead(buffer: ByteBuffer, remainingLimit: Int): ReadResult =
      if (remainingLimit > 0) {
        // never read more than the configured limit
        buffer.clear()
        val maxBufferSpace = math.min(DirectBufferSize, remainingLimit)
        buffer.limit(maxBufferSpace)
        val readBytes = channel.read(buffer)
        buffer.flip()

        if (TraceLogging) log.debug("Read [{}] bytes.", readBytes)
        if (readBytes > 0) handler ! Received(ByteString(buffer))

        readBytes match {
          case `maxBufferSpace` ⇒ innerRead(buffer, remainingLimit - maxBufferSpace)
          case x if x >= 0      ⇒ AllRead
          case -1               ⇒ EndOfStream
          case _ ⇒
            throw new IllegalStateException("Unexpected value returned from read: " + readBytes)
        }
      } else MoreDataWaiting

    val buffer = bufferPool.acquire()
    try innerRead(buffer, ReceivedMessageSizeLimit) match {
      case AllRead         ⇒ selector ! ReadInterest
      case MoreDataWaiting ⇒ self ! ChannelReadable
      case EndOfStream ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream")
        doCloseConnection(handler, closeCommander, closeReason)
    } catch {
      case e: IOException ⇒ handleError(handler, e)
    } finally bufferPool.release(buffer)
  }

  final def doWrite(handler: ActorRef): Unit = {
    @tailrec def innerWrite(): Unit = {
      val toWrite = pendingWrite.buffer.remaining()
      require(toWrite != 0)
      val writtenBytes = channel.write(pendingWrite.buffer)
      if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)

      pendingWrite = pendingWrite.consume(writtenBytes)

      if (pendingWrite.hasData)
        if (writtenBytes == toWrite) innerWrite() // wrote complete buffer, try again now
        else selector ! WriteInterest // try again later
      else { // everything written
        if (pendingWrite.wantsAck)
          pendingWrite.commander ! pendingWrite.ack

        val buffer = pendingWrite.buffer
        pendingWrite = null

        bufferPool.release(buffer)
      }
    }

    try innerWrite()
    catch { case e: IOException ⇒ handleError(handler, e) }
  }

  def closeReason =
    if (channel.socket.isOutputShutdown) ConfirmedClosed
    else PeerClosed

  def handleClose(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit =
    if (closedEvent == Aborted) { // close instantly
      if (TraceLogging) log.debug("Got Abort command. RESETing connection.")
      doCloseConnection(handler, closeCommander, closedEvent)

    } else if (writePending) { // finish writing first
      if (TraceLogging) log.debug("Got Close command but write is still pending.")
      context.become(closingWithPendingWrite(handler, closeCommander, closedEvent))

    } else if (closedEvent == ConfirmedClosed) { // shutdown output and wait for confirmation
      if (TraceLogging) log.debug("Got ConfirmedClose command, sending FIN.")
      channel.socket.shutdownOutput()
      context.become(closing(handler, closeCommander))

    } else { // close now
      if (TraceLogging) log.debug("Got Close command, closing connection.")
      doCloseConnection(handler, closeCommander, closedEvent)
    }

  def doCloseConnection(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit = {
    if (closedEvent == Aborted) abort()
    else channel.close()

    closedMessage = CloseInformation(Set(handler) ++ closeCommander, closedEvent)

    context.stop(self)
  }

  def closeResponse(closeCommand: CloseCommand): ConnectionClosed =
    closeCommand match {
      case Close          ⇒ Closed
      case Abort          ⇒ Aborted
      case ConfirmedClose ⇒ ConfirmedClosed
    }

  def handleError(handler: ActorRef, exception: IOException): Unit = {
    closedMessage = CloseInformation(Set(handler), ErrorClosed(extractMsg(exception)))

    throw exception
  }
  @tailrec private[this] def extractMsg(t: Throwable): String =
    if (t == null) "unknown"
    else {
      t.getMessage match {
        case null | "" ⇒ extractMsg(t.getCause)
        case msg       ⇒ msg
      }
    }

  def abort(): Unit = {
    try channel.socket.setSoLinger(true, 0) // causes the following close() to send TCP RST
    catch {
      case NonFatal(e) ⇒
        // setSoLinger can fail due to http://bugs.sun.com/view_bug.do?bug_id=6799574
        // (also affected: OS/X Java 1.6.0_37)
        if (TraceLogging) log.debug("setSoLinger(true, 0) failed with [{}]", e)
    }
    channel.close()
  }

  override def postStop(): Unit = {
    if (channel.isOpen)
      abort()

    if (writePending)
      bufferPool.release(pendingWrite.buffer)

    if (closedMessage != null) {
      val interestedInClose =
        if (writePending) closedMessage.notificationsTo + pendingWrite.commander
        else closedMessage.notificationsTo

      interestedInClose.foreach(_ ! closedMessage.closedEvent)
    }
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

  private[TcpConnection] case class PendingWrite(
    commander: ActorRef,
    ack: Any,
    remainingData: ByteString,
    buffer: ByteBuffer) {

    def consume(writtenBytes: Int): PendingWrite =
      if (buffer.remaining() == 0) {
        buffer.clear()
        val copied = remainingData.copyToBuffer(buffer)
        buffer.flip()
        copy(remainingData = remainingData.drop(copied))
      } else this

    def hasData = buffer.remaining() > 0 || remainingData.size > 0
    def wantsAck = !ack.isInstanceOf[NoAck]
  }
  def createWrite(write: Write): PendingWrite = {
    val buffer = bufferPool.acquire()
    val copied = write.data.copyToBuffer(buffer)
    buffer.flip()

    PendingWrite(sender, write.ack, write.data.drop(copied), buffer)
  }
}

/**
 * INTERNAL API
 */
private[io] object TcpConnection {
  sealed trait ReadResult
  object EndOfStream extends ReadResult
  object AllRead extends ReadResult
  object MoreDataWaiting extends ReadResult

  /**
   * Used to transport information to the postStop method to notify
   * interested party about a connection close.
   */
  case class CloseInformation(
    notificationsTo: Set[ActorRef],
    closedEvent: ConnectionClosed)
}
