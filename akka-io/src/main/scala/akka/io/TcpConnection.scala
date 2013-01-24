/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.{ FileInputStream, IOException }
import java.nio.ByteBuffer
import scala.annotation.tailrec
import java.nio.channels.{ FileChannel, SocketChannel }
import scala.collection.immutable
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor._
import akka.util.ByteString
import Tcp._
import TcpSelector._

/**
 * Base class for TcpIncomingConnection and TcpOutgoingConnection.
 */
private[io] abstract class TcpConnection(val channel: SocketChannel,
                                         val tcp: TcpExt) extends Actor with ActorLogging with WithBufferPool {
  import TcpConnection._
  import tcp.Settings._
  var pendingWrite: PendingWrite = null

  // Needed to send the ConnectionClosed message in the postStop handler.
  var closedMessage: CloseInformation = null

  def writePending = pendingWrite ne null

  def selector = context.parent

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(commander: ActorRef): Receive = {
    case Register(handler) ⇒
      if (TraceLogging) log.debug("{} registered as connection handler", handler)
      doRead(handler, None) // immediately try reading

      context.setReceiveTimeout(Duration.Undefined)
      context.watch(handler) // sign death pact

      context.become(connected(handler))

    case cmd: CloseCommand ⇒
      handleClose(commander, Some(sender), closeResponse(cmd))

    case ReceiveTimeout ⇒
      // after sending `Register` user should watch this actor to make sure
      // it didn't die because of the timeout
      log.warning("Configured registration timeout of {} expired, stopping", RegisterTimeout)
      context.stop(self)
  }

  /** normal connected state */
  def connected(handler: ActorRef): Receive = {
    case StopReading     ⇒ selector ! StopReading
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler, None)

    case write: WriteCommand if writePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender ! CommandFailed(write)

    case write: Write if write.data.isEmpty ⇒
      if (write.wantsAck)
        sender ! write.ack

    case write: WriteCommand ⇒
      pendingWrite = createWrite(write)
      pendingWrite.doWrite(handler)

    case ChannelWritable           ⇒ pendingWrite.doWrite(handler)
    case SendBufferFull(remaining) ⇒ pendingWrite = remaining; selector ! WriteInterest
    case WriteFileFinished         ⇒ pendingWrite = null

    case cmd: CloseCommand         ⇒ handleClose(handler, Some(sender), closeResponse(cmd))
  }

  /** connection is closing but a write has to be finished first */
  def closingWithPendingWrite(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Receive = {
    case StopReading     ⇒ selector ! StopReading
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler, closeCommander)

    case ChannelWritable ⇒
      pendingWrite.doWrite(handler)
      if (!writePending) // writing is now finished
        handleClose(handler, closeCommander, closedEvent)

    case SendBufferFull(remaining) ⇒ pendingWrite = remaining; selector ! WriteInterest
    case WriteFileFinished ⇒
      pendingWrite = null
      handleClose(handler, closeCommander, closedEvent)

    case Abort ⇒ handleClose(handler, Some(sender), Aborted)
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(handler: ActorRef, closeCommander: Option[ActorRef]): Receive = {
    case StopReading     ⇒ selector ! StopReading
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler, closeCommander)
    case Abort           ⇒ handleClose(handler, Some(sender), Aborted)
  }

  // AUXILIARIES and IMPLEMENTATION

  /** used in subclasses to start the common machinery above once a channel is connected */
  def completeConnect(commander: ActorRef, options: immutable.Traversable[SocketOption]): Unit = {
    options.foreach(_.afterConnect(channel.socket))

    commander ! Connected(
      channel.socket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress],
      channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])

    context.setReceiveTimeout(RegisterTimeout)
    context.become(waitingForRegistration(commander))
  }

  def doRead(handler: ActorRef, closeCommander: Option[ActorRef]): Unit = {
    val buffer = acquireBuffer()

    try {
      val readBytes = channel.read(buffer)
      buffer.flip()

      if (readBytes > 0) {
        if (TraceLogging) log.debug("Read {} bytes", readBytes)
        handler ! Received(ByteString(buffer))
        releaseBuffer(buffer)

        if (readBytes == buffer.capacity())
          // directly try reading more because we exhausted our buffer
          self ! ChannelReadable
        else selector ! ReadInterest
      } else if (readBytes == 0) {
        if (TraceLogging) log.debug("Read nothing. Registering read interest with selector")
        selector ! ReadInterest
      } else if (readBytes == -1) {
        if (TraceLogging) log.debug("Read returned end-of-stream")
        doCloseConnection(handler, closeCommander, closeReason)
      } else throw new IllegalStateException("Unexpected value returned from read: " + readBytes)

    } catch {
      case e: IOException ⇒ handleError(handler, e)
    }
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

  def handleError(handler: ActorRef, exception: IOException): Nothing = {
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
        if (TraceLogging) log.debug("setSoLinger(true, 0) failed with {}", e)
    }
    channel.close()
  }

  override def postStop(): Unit = {
    if (channel.isOpen)
      abort()

    if (writePending)
      pendingWrite.release()

    if (closedMessage != null) {
      val interestedInClose =
        if (writePending) closedMessage.notificationsTo + pendingWrite.commander
        else closedMessage.notificationsTo

      interestedInClose.foreach(_ ! closedMessage.closedEvent)
    }
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

  /**
   * Used to transport information to the postStop method to notify
   * interested party about a connection close.
   */
  private[TcpConnection] case class CloseInformation(
    notificationsTo: Set[ActorRef],
    closedEvent: ConnectionClosed)

  private[TcpConnection] case class PendingBufferWrite(
    commander: ActorRef,
    ack: Any,
    remainingData: ByteString,
    buffer: ByteBuffer) extends PendingWrite {

    def consume(writtenBytes: Int): PendingBufferWrite =
      if (buffer.remaining() == 0) {
        buffer.clear()
        val copied = remainingData.copyToBuffer(buffer)
        buffer.flip()
        copy(remainingData = remainingData.drop(copied))
      } else this

    def hasData = buffer.remaining() > 0 || remainingData.size > 0

    final def doWrite(handler: ActorRef): Unit =
      pendingWrite = doWriteBuffer(handler, this)

    /** Release any open resources */
    def release() {
      releaseBuffer(buffer)
    }
  }
  def createWrite(write: WriteCommand): PendingWrite = write match {
    case write: Write ⇒
      val buffer = acquireBuffer()
      val copied = write.data.copyToBuffer(buffer)
      buffer.flip()

      PendingBufferWrite(sender, write.ack, write.data.drop(copied), buffer)
    case write: WriteFile ⇒
      PendingWriteFile(sender, write, new FileInputStream(write.filePath).getChannel, 0L)
  }
  private[TcpConnection] case class PendingWriteFile(
    commander: ActorRef,
    write: WriteFile,
    channel: FileChannel,
    alreadyWritten: Long) extends PendingWrite {
    def doWrite(handler: ActorRef): Unit =
      tcp.fileIoDispatcher.execute(writeFile(this))

    def ack: Any = write.ack

    /** Release any open resources */
    def release() {
      channel.close()
    }

    def updatedWrite(nowWritten: Long): PendingWriteFile = {
      require(nowWritten < write.count)
      copy(alreadyWritten = nowWritten)
    }
  }

  def writeFile(pendingWrite: PendingWriteFile): Runnable =
    new Runnable {
      def run() {
        import pendingWrite.{ channel ⇒ _, _ }
        val remaining = write.count - alreadyWritten

        val writtenBytes = pendingWrite.channel.transferTo(write.position + alreadyWritten, remaining, channel)

        if (writtenBytes < remaining) self ! SendBufferFull(pendingWrite.updatedWrite(alreadyWritten + writtenBytes))
        else { // finished
          if (wantsAck) commander ! write.ack
          self ! WriteFileFinished

          pendingWrite.release()
        }
      }
    }

  final def doWriteBuffer(handler: ActorRef, currentWrite: PendingBufferWrite): PendingBufferWrite = {
    @tailrec def innerWrite(currentWrite: PendingBufferWrite): PendingBufferWrite = {
      val toWrite = currentWrite.buffer.remaining()
      require(toWrite != 0)
      val writtenBytes = channel.write(currentWrite.buffer)
      if (TraceLogging) log.debug("Wrote {} bytes to channel", writtenBytes)

      val nextWrite = currentWrite.consume(writtenBytes)

      if (nextWrite.hasData)
        if (writtenBytes == toWrite) innerWrite(nextWrite) // wrote complete buffer, try again now
        else {
          selector ! WriteInterest // try again later
          nextWrite
        }
      else { // everything written
        if (nextWrite.wantsAck)
          nextWrite.commander ! nextWrite.ack

        releaseBuffer(nextWrite.buffer)

        null
      }
    }

    try innerWrite(currentWrite)
    catch { case e: IOException ⇒ handleError(handler, e) }
  }
}

private[io] object TcpConnection {
  trait PendingWrite {
    def commander: ActorRef
    def ack: Any

    def wantsAck = ack != NoAck
    def doWrite(handler: ActorRef): Unit

    /** Release any open resources */
    def release(): Unit
  }

  // INTERNAL MESSAGES

  /** Informs actor that no writing was possible but there is still work remaining */
  case class SendBufferFull(remainingWrite: PendingWrite)
  /** Informs actor that a pending file write has finished */
  case object WriteFileFinished
}
