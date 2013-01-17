/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.IOException
import java.nio.channels.SocketChannel
import scala.util.control.NonFatal
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor._
import akka.util.ByteString
import Tcp._
import annotation.tailrec

/**
 * Base class for TcpIncomingConnection and TcpOutgoingConnection.
 */
abstract class TcpConnection(val selector: ActorRef,
                             val channel: SocketChannel) extends Actor with ThreadLocalDirectBuffer with ActorLogging {
  val tcp = Tcp(context.system)

  channel.configureBlocking(false)

  var pendingWrite: Write = Write.Empty // a write "queue" of size 1 for holding one unfinished write command
  var pendingWriteCommander: ActorRef = null

  // Needed to send the ConnectionClosed message in the postStop handler.
  // First element is the handler, second the particular close message.
  var closedMessage: (ActorRef, ConnectionClosed) = null

  def writePending = pendingWrite ne Write.Empty

  def registerTimeout = tcp.Settings.RegisterTimeout
  def traceLoggingEnabled = tcp.Settings.TraceLogging

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(commander: ActorRef): Receive = {
    case Register(handler) ⇒
      if (traceLoggingEnabled) log.debug("{} registered as connection handler", handler)
      selector ! ReadInterest

      context.setReceiveTimeout(Duration.Undefined)
      context.watch(handler) // sign death pact

      context.become(connected(handler))

    case cmd: CloseCommand ⇒
      handleClose(commander, closeResponse(cmd))

    case ReceiveTimeout ⇒
      // after sending `Register` user should watch this actor to make sure
      // it didn't die because of the timeout
      log.warning("Configured registration timeout of {} expired, stopping", registerTimeout)
      context.stop(self)
  }

  /** normal connected state */
  def connected(handler: ActorRef): Receive = {
    case StopReading     ⇒ selector ! StopReading
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler)

    case write: Write if writePending ⇒
      if (traceLoggingEnabled) log.debug("Dropping write because queue is full")
      sender ! CommandFailed(write)

    case write: Write if write.data.isEmpty ⇒
      if (write.wantsAck)
        sender ! write.ack

    case write: Write ⇒
      pendingWriteCommander = sender
      pendingWrite = write
      doWrite(handler)
    case ChannelWritable   ⇒ doWrite(handler)

    case cmd: CloseCommand ⇒ handleClose(handler, closeResponse(cmd))
  }

  /** connection is closing but a write has to be finished first */
  def closingWithPendingWrite(handler: ActorRef, closedEvent: ConnectionClosed): Receive = {
    case StopReading     ⇒ selector ! StopReading
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler)

    case ChannelWritable ⇒
      doWrite(handler)
      if (!writePending) // writing is now finished
        handleClose(handler, closedEvent)

    case Abort ⇒ handleClose(handler, Aborted)
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(handler: ActorRef): Receive = {
    case StopReading     ⇒ selector ! StopReading
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler)
    case Abort           ⇒ handleClose(handler, Aborted)
  }

  // AUXILIARIES and IMPLEMENTATION

  /** use in subclasses to start the common machinery above once a channel is connected */
  def completeConnect(commander: ActorRef, options: immutable.Seq[SocketOption]): Unit = {
    options.foreach(_.afterConnect(channel.socket))

    commander ! Connected(
      channel.socket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress],
      channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])

    context.setReceiveTimeout(registerTimeout)
    context.become(waitingForRegistration(commander))
  }

  def doRead(handler: ActorRef): Unit = {
    val buffer = directBuffer()

    try {
      log.debug("Trying to read from channel")
      val readBytes = channel.read(buffer)
      buffer.flip()

      if (readBytes > 0) {
        if (traceLoggingEnabled) log.debug("Read {} bytes", readBytes)
        handler ! Received(ByteString(buffer))
        if (readBytes == buffer.capacity())
          // directly try reading more because we exhausted our buffer
          self ! ChannelReadable
        else selector ! ReadInterest
      } else if (readBytes == 0) {
        if (traceLoggingEnabled) log.debug("Read nothing. Registering read interest with selector")
        selector ! ReadInterest
      } else if (readBytes == -1) {
        if (traceLoggingEnabled) log.debug("Read returned end-of-stream")
        doCloseConnection(handler, closeReason)
      } else throw new IllegalStateException("Unexpected value returned from read: " + readBytes)

    } catch {
      case e: IOException ⇒ handleError(handler, e)
    }
  }

  def doWrite(handler: ActorRef): Unit = {
    val write = pendingWrite
    val data = write.data

    val buffer = directBuffer()
    data.copyToBuffer(buffer)
    buffer.flip()

    try {
      val writtenBytes = channel.write(buffer)
      if (traceLoggingEnabled) log.debug("Wrote {} bytes", writtenBytes)
      pendingWrite = consume(write, writtenBytes)

      if (writePending) selector ! WriteInterest // still data to write
      else if (write.wantsAck) {
        pendingWriteCommander ! write.ack
        pendingWriteCommander = null
      } // everything written
    } catch {
      case e: IOException ⇒ handleError(handler, e)
    }
  }

  def closeReason =
    if (channel.socket.isOutputShutdown) ConfirmedClosed
    else PeerClosed

  def handleClose(handler: ActorRef, closedEvent: ConnectionClosed): Unit =
    if (closedEvent == Aborted) { // close instantly
      if (traceLoggingEnabled) log.debug("Got Abort command. RESETing connection.")
      doCloseConnection(handler, closedEvent)

    } else if (writePending) { // finish writing first
      if (traceLoggingEnabled) log.debug("Got Close command but write is still pending.")
      context.become(closingWithPendingWrite(handler, closedEvent))

    } else if (closedEvent == ConfirmedClosed) { // shutdown output and wait for confirmation
      if (traceLoggingEnabled) log.debug("Got ConfirmedClose command, sending FIN.")
      channel.socket.shutdownOutput()
      context.become(closing(handler))

    } else { // close now
      if (traceLoggingEnabled) log.debug("Got Close command, closing connection.")
      doCloseConnection(handler, closedEvent)
    }

  def doCloseConnection(handler: ActorRef, closedEvent: ConnectionClosed): Unit = {
    if (closedEvent == Aborted) abort()
    else channel.close()

    closedMessage = (handler, closedEvent)

    context.stop(self)
  }

  def closeResponse(closeCommand: CloseCommand): ConnectionClosed =
    closeCommand match {
      case Close          ⇒ Closed
      case Abort          ⇒ Aborted
      case ConfirmedClose ⇒ ConfirmedClosed
    }

  def handleError(handler: ActorRef, exception: IOException): Unit = {
    closedMessage = (handler, ErrorClose(extractMsg(exception)))

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
        if (traceLoggingEnabled) log.debug("setSoLinger(true, 0) failed with {}", e)
    }
    channel.close()
  }

  override def postStop(): Unit = {
    if (closedMessage != null) {
      val msg = closedMessage._2
      closedMessage._1 ! msg

      if (writePending)
        pendingWriteCommander ! msg
    }

    if (channel.isOpen)
      abort()
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

  /** Returns a new write with `numBytes` removed from the front */
  def consume(write: Write, numBytes: Int): Write =
    numBytes match {
      case 0                           ⇒ write
      case x if x == write.data.length ⇒ Write.Empty
      case _ ⇒
        require(numBytes > 0 && numBytes < write.data.length)
        write.copy(data = write.data.drop(numBytes))
    }
}
