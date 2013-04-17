/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.io.{ FileInputStream, IOException }
import java.nio.channels.{ FileChannel, SocketChannel }
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

  private[this] var peerClosed = false
  private[this] var keepOpenOnPeerClosed = false

  def writePending = pendingWrite ne null

  def selector = context.parent

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(commander: ActorRef): Receive = {
    case Register(handler, keepOpenOnPeerClosed) ⇒
      // up to this point we've been watching the commander,
      // but since registration is now complete we only need to watch the handler from here on
      if (handler != commander) {
        context.unwatch(commander)
        context.watch(handler)
      }
      if (TraceLogging) log.debug("[{}] registered as connection handler", handler)
      this.keepOpenOnPeerClosed = keepOpenOnPeerClosed

      doRead(handler, None) // immediately try reading
      context.setReceiveTimeout(Duration.Undefined)
      context.become(connected(handler))

    case cmd: CloseCommand ⇒
      handleClose(commander, Some(sender), cmd.event)

    case ReceiveTimeout ⇒
      // after sending `Register` user should watch this actor to make sure
      // it didn't die because of the timeout
      log.warning("Configured registration timeout of [{}] expired, stopping", RegisterTimeout)
      context.stop(self)
  }

  /** normal connected state */
  def connected(handler: ActorRef): Receive = handleWriteMessages(handler) orElse {
    case StopReading       ⇒ selector ! DisableReadInterest
    case ResumeReading     ⇒ selector ! ReadInterest
    case ChannelReadable   ⇒ doRead(handler, None)

    case cmd: CloseCommand ⇒ handleClose(handler, Some(sender), cmd.event)
  }

  /** the peer sent EOF first, but we may still want to send */
  def peerSentEOF(handler: ActorRef): Receive = handleWriteMessages(handler) orElse {
    case cmd: CloseCommand ⇒ handleClose(handler, Some(sender), cmd.event)
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
    case SendBufferFull(remaining) ⇒ { pendingWrite = remaining; selector ! WriteInterest }
    case WriteFileFinished         ⇒ { pendingWrite = null; handleClose(handler, closeCommander, closedEvent) }
    case WriteFileFailed(e)        ⇒ handleError(handler, e) // rethrow exception from dispatcher task

    case Abort                     ⇒ handleClose(handler, Some(sender), Aborted)
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(handler: ActorRef, closeCommander: Option[ActorRef]): Receive = {
    case StopReading     ⇒ selector ! DisableReadInterest
    case ResumeReading   ⇒ selector ! ReadInterest
    case ChannelReadable ⇒ doRead(handler, closeCommander)
    case Abort           ⇒ handleClose(handler, Some(sender), Aborted)
  }

  def handleWriteMessages(handler: ActorRef): Receive = {
    case ChannelWritable ⇒ if (writePending) doWrite(handler)

    case write: WriteCommand if writePending ⇒
      if (TraceLogging) log.debug("Dropping write because queue is full")
      sender ! write.failureMessage

    case write: Write if write.data.isEmpty ⇒
      if (write.wantsAck)
        sender ! write.ack

    case write: WriteCommand ⇒
      pendingWrite = createWrite(write)
      doWrite(handler)

    case SendBufferFull(remaining) ⇒ { pendingWrite = remaining; selector ! WriteInterest }
    case WriteFileFinished         ⇒ pendingWrite = null
    case WriteFileFailed(e)        ⇒ handleError(handler, e) // rethrow exception from dispatcher task
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
      case EndOfStream if channel.socket.isOutputShutdown ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side already closed")
        doCloseConnection(handler, closeCommander, ConfirmedClosed)
      case EndOfStream ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side not yet closed")
        handleClose(handler, closeCommander, PeerClosed)
    } catch {
      case e: IOException ⇒ handleError(handler, e)
    } finally bufferPool.release(buffer)
  }

  def doWrite(handler: ActorRef): Unit =
    pendingWrite = pendingWrite.doWrite(handler)

  def closeReason =
    if (channel.socket.isOutputShutdown) ConfirmedClosed
    else PeerClosed

  def handleClose(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit = closedEvent match {
    case Aborted ⇒
      if (TraceLogging) log.debug("Got Abort command. RESETing connection.")
      doCloseConnection(handler, closeCommander, closedEvent)
    case PeerClosed if keepOpenOnPeerClosed ⇒
      // report that peer closed the connection
      handler ! PeerClosed
      // used to check if peer already closed its side later
      peerClosed = true
      context.become(peerSentEOF(handler))
    case _ if writePending ⇒ // finish writing first
      if (TraceLogging) log.debug("Got Close command but write is still pending.")
      context.become(closingWithPendingWrite(handler, closeCommander, closedEvent))
    case ConfirmedClosed ⇒ // shutdown output and wait for confirmation
      if (TraceLogging) log.debug("Got ConfirmedClose command, sending FIN.")
      channel.socket.shutdownOutput()

      if (peerClosed) // if peer closed first, the socket is now fully closed
        doCloseConnection(handler, closeCommander, closedEvent)
      else context.become(closing(handler, closeCommander))
    case _ ⇒ // close now
      if (TraceLogging) log.debug("Got Close command, closing connection.")
      doCloseConnection(handler, closeCommander, closedEvent)
  }

  def doCloseConnection(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit = {
    if (closedEvent == Aborted) abort()
    else channel.close()

    closedMessage = CloseInformation(Set(handler) ++ closeCommander, closedEvent)

    context.stop(self)
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
        if (TraceLogging) log.debug("setSoLinger(true, 0) failed with [{}]", e)
    }
    channel.close()
  }

  override def postStop(): Unit = {
    if (channel.isOpen)
      abort()

    if (writePending) pendingWrite.release()

    if (closedMessage != null) {
      val interestedInClose =
        if (writePending) closedMessage.notificationsTo + pendingWrite.commander
        else closedMessage.notificationsTo

      interestedInClose.foreach(_ ! closedMessage.closedEvent)
    }
  }

  override def postRestart(reason: Throwable): Unit =
    throw new IllegalStateException("Restarting not supported for connection actors.")

  /** Create a pending write from a WriteCommand */
  private[io] def createWrite(write: WriteCommand): PendingWrite = write match {
    case write: Write ⇒
      val buffer = bufferPool.acquire()

      try {
        val copied = write.data.copyToBuffer(buffer)
        buffer.flip()

        PendingBufferWrite(sender, write.ack, write.data.drop(copied), buffer)
      } catch {
        case NonFatal(e) ⇒
          bufferPool.release(buffer)
          throw e
      }
    case write: WriteFile ⇒
      PendingWriteFile(sender, write, new FileInputStream(write.filePath).getChannel, 0L)
  }

  private[io] case class PendingBufferWrite(
    commander: ActorRef,
    ack: Any,
    remainingData: ByteString,
    buffer: ByteBuffer) extends PendingWrite {

    def release(): Unit = bufferPool.release(buffer)

    def doWrite(handler: ActorRef): PendingWrite = {
      @tailrec def innerWrite(pendingWrite: PendingBufferWrite): PendingWrite = {
        val toWrite = pendingWrite.buffer.remaining()
        require(toWrite != 0)
        val writtenBytes = channel.write(pendingWrite.buffer)
        if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)

        val nextWrite = pendingWrite.consume(writtenBytes)

        if (pendingWrite.hasData)
          if (writtenBytes == toWrite) innerWrite(nextWrite) // wrote complete buffer, try again now
          else {
            selector ! WriteInterest
            nextWrite
          } // try again later
        else { // everything written
          if (pendingWrite.wantsAck)
            pendingWrite.commander ! pendingWrite.ack

          pendingWrite.release()
          null
        }
      }

      try innerWrite(this)
      catch { case e: IOException ⇒ handleError(handler, e) }
    }
    def hasData = buffer.hasRemaining || remainingData.nonEmpty
    def consume(writtenBytes: Int): PendingBufferWrite =
      if (buffer.hasRemaining) this
      else {
        buffer.clear()
        val copied = remainingData.copyToBuffer(buffer)
        buffer.flip()
        copy(remainingData = remainingData.drop(copied))
      }
  }

  private[io] case class PendingWriteFile(
    commander: ActorRef,
    write: WriteFile,
    fileChannel: FileChannel,
    alreadyWritten: Long) extends PendingWrite {

    def doWrite(handler: ActorRef): PendingWrite = {
      tcp.fileIoDispatcher.execute(writeFileRunnable(this))
      this
    }

    def ack: Any = write.ack

    /** Release any open resources */
    def release() { fileChannel.close() }

    def updatedWrite(nowWritten: Long): PendingWriteFile = {
      require(nowWritten < write.count)
      copy(alreadyWritten = nowWritten)
    }

    def remainingBytes = write.count - alreadyWritten
    def currentPosition = write.position + alreadyWritten
  }
  private[io] def writeFileRunnable(pendingWrite: PendingWriteFile): Runnable =
    new Runnable {
      def run(): Unit = try {
        import pendingWrite._
        val toWrite = math.min(remainingBytes, tcp.Settings.TransferToLimit)
        val writtenBytes = fileChannel.transferTo(currentPosition, toWrite, channel)

        if (writtenBytes < remainingBytes) self ! SendBufferFull(pendingWrite.updatedWrite(alreadyWritten + writtenBytes))
        else { // finished
          if (wantsAck) commander ! write.ack
          self ! WriteFileFinished

          pendingWrite.release()
        }
      } catch {
        case e: IOException ⇒ self ! WriteFileFailed(e)
      }
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
    closedEvent: Event)

  // INTERNAL MESSAGES

  /** Informs actor that no writing was possible but there is still work remaining */
  case class SendBufferFull(remainingWrite: PendingWrite)
  /** Informs actor that a pending file write has finished */
  case object WriteFileFinished
  /** Informs actor that a pending WriteFile failed */
  case class WriteFileFailed(e: IOException)

  /** Abstraction over pending writes */
  trait PendingWrite {
    def commander: ActorRef
    def ack: Any

    def wantsAck = !ack.isInstanceOf[NoAck]
    def doWrite(handler: ActorRef): PendingWrite

    /** Release any open resources */
    def release(): Unit
  }
}
