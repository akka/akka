/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.channels.SelectionKey._
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
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }

/**
 * Base class for TcpIncomingConnection and TcpOutgoingConnection.
 *
 * INTERNAL API
 */
private[io] abstract class TcpConnection(val tcp: TcpExt, val channel: SocketChannel)
  extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import tcp.Settings._
  import tcp.bufferPool
  import TcpConnection._

  private[this] var pendingWrite: PendingWrite = _
  private[this] var peerClosed = false
  private[this] var writingSuspended = false
  private[this] var interestedInResume: Option[ActorRef] = None
  var closedMessage: CloseInformation = _ // for ConnectionClosed message in postStop

  def writePending = pendingWrite ne null

  // STATES

  /** connection established, waiting for registration from user handler */
  def waitingForRegistration(registration: ChannelRegistration, commander: ActorRef): Receive = {
    case Register(handler, keepOpenOnPeerClosed, useResumeWriting) ⇒
      // up to this point we've been watching the commander,
      // but since registration is now complete we only need to watch the handler from here on
      if (handler != commander) {
        context.unwatch(commander)
        context.watch(handler)
      }
      if (TraceLogging) log.debug("[{}] registered as connection handler", handler)

      val info = ConnectionInfo(registration, handler, keepOpenOnPeerClosed, useResumeWriting)
      doRead(info, None) // immediately try reading
      context.setReceiveTimeout(Duration.Undefined)
      context.become(connected(info))

    case cmd: CloseCommand ⇒
      val info = ConnectionInfo(registration, commander, keepOpenOnPeerClosed = false, useResumeWriting = false)
      handleClose(info, Some(sender), cmd.event)

    case ReceiveTimeout ⇒
      // after sending `Register` user should watch this actor to make sure
      // it didn't die because of the timeout
      log.debug("Configured registration timeout of [{}] expired, stopping", RegisterTimeout)
      context.stop(self)
  }

  /** normal connected state */
  def connected(info: ConnectionInfo): Receive =
    handleWriteMessages(info) orElse {
      case SuspendReading    ⇒ info.registration.disableInterest(OP_READ)
      case ResumeReading     ⇒ info.registration.enableInterest(OP_READ)
      case ChannelReadable   ⇒ doRead(info, None)
      case cmd: CloseCommand ⇒ handleClose(info, Some(sender), cmd.event)
    }

  /** the peer sent EOF first, but we may still want to send */
  def peerSentEOF(info: ConnectionInfo): Receive =
    handleWriteMessages(info) orElse {
      case cmd: CloseCommand ⇒ handleClose(info, Some(sender), cmd.event)
    }

  /** connection is closing but a write has to be finished first */
  def closingWithPendingWrite(info: ConnectionInfo, closeCommander: Option[ActorRef],
                              closedEvent: ConnectionClosed): Receive = {
    case SuspendReading  ⇒ info.registration.disableInterest(OP_READ)
    case ResumeReading   ⇒ info.registration.enableInterest(OP_READ)
    case ChannelReadable ⇒ doRead(info, closeCommander)

    case ChannelWritable ⇒
      doWrite(info)
      if (!writePending) // writing is now finished
        handleClose(info, closeCommander, closedEvent)
    case SendBufferFull(remaining) ⇒ { pendingWrite = remaining; info.registration.enableInterest(OP_WRITE) }
    case WriteFileFinished         ⇒ { pendingWrite = null; handleClose(info, closeCommander, closedEvent) }
    case WriteFileFailed(e)        ⇒ handleError(info.handler, e) // rethrow exception from dispatcher task

    case Abort                     ⇒ handleClose(info, Some(sender), Aborted)
  }

  /** connection is closed on our side and we're waiting from confirmation from the other side */
  def closing(info: ConnectionInfo, closeCommander: Option[ActorRef]): Receive = {
    case SuspendReading  ⇒ info.registration.disableInterest(OP_READ)
    case ResumeReading   ⇒ info.registration.enableInterest(OP_READ)
    case ChannelReadable ⇒ doRead(info, closeCommander)
    case Abort           ⇒ handleClose(info, Some(sender), Aborted)
  }

  def handleWriteMessages(info: ConnectionInfo): Receive = {
    case ChannelWritable ⇒
      if (writePending) {
        doWrite(info)
        if (!writePending && interestedInResume.nonEmpty) {
          interestedInResume.get ! WritingResumed
          interestedInResume = None
        }
      }

    case write: WriteCommand ⇒
      if (writingSuspended) {
        if (TraceLogging) log.debug("Dropping write because writing is suspended")
        sender ! write.failureMessage

      } else if (writePending) {
        if (TraceLogging) log.debug("Dropping write because queue is full")
        sender ! write.failureMessage
        if (info.useResumeWriting) writingSuspended = true

      } else write match {
        case Write(data, ack) if data.isEmpty ⇒
          if (write.wantsAck) sender ! ack

        case _ ⇒
          pendingWrite = createWrite(write)
          doWrite(info)
      }

    case ResumeWriting ⇒
      /*
       * If more than one actor sends Writes then the first to send this
       * message might resume too early for the second, leading to a Write of
       * the second to go through although it has not been resumed yet; there
       * is nothing we can do about this apart from all actors needing to
       * register themselves and us keeping track of them, which sounds bad.
       *
       * Thus it is documented that useResumeWriting is incompatible with
       * multiple writers. But we fail as gracefully as we can.
       */
      writingSuspended = false
      if (writePending) {
        if (interestedInResume.isEmpty) interestedInResume = Some(sender)
        else sender ! CommandFailed(ResumeWriting)
      } else sender ! WritingResumed

    case SendBufferFull(remaining) ⇒ { pendingWrite = remaining; info.registration.enableInterest(OP_WRITE) }
    case WriteFileFinished         ⇒ pendingWrite = null
    case WriteFileFailed(e)        ⇒ handleError(info.handler, e) // rethrow exception from dispatcher task
  }

  // AUXILIARIES and IMPLEMENTATION

  /** used in subclasses to start the common machinery above once a channel is connected */
  def completeConnect(registration: ChannelRegistration, commander: ActorRef,
                      options: immutable.Traversable[SocketOption]): Unit = {
    // Turn off Nagle's algorithm by default
    channel.socket.setTcpNoDelay(true)
    options.foreach(_.afterConnect(channel.socket))

    commander ! Connected(
      channel.socket.getRemoteSocketAddress.asInstanceOf[InetSocketAddress],
      channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])

    context.setReceiveTimeout(RegisterTimeout)
    context.become(waitingForRegistration(registration, commander))
  }

  def doRead(info: ConnectionInfo, closeCommander: Option[ActorRef]): Unit = {
    @tailrec def innerRead(buffer: ByteBuffer, remainingLimit: Int): ReadResult =
      if (remainingLimit > 0) {
        // never read more than the configured limit
        buffer.clear()
        val maxBufferSpace = math.min(DirectBufferSize, remainingLimit)
        buffer.limit(maxBufferSpace)
        val readBytes = channel.read(buffer)
        buffer.flip()

        if (TraceLogging) log.debug("Read [{}] bytes.", readBytes)
        if (readBytes > 0) info.handler ! Received(ByteString(buffer))

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
      case AllRead         ⇒ info.registration.enableInterest(OP_READ)
      case MoreDataWaiting ⇒ self ! ChannelReadable
      case EndOfStream if channel.socket.isOutputShutdown ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side already closed")
        doCloseConnection(info.handler, closeCommander, ConfirmedClosed)
      case EndOfStream ⇒
        if (TraceLogging) log.debug("Read returned end-of-stream, our side not yet closed")
        handleClose(info, closeCommander, PeerClosed)
    } catch {
      case e: IOException ⇒ handleError(info.handler, e)
    } finally bufferPool.release(buffer)
  }

  def doWrite(info: ConnectionInfo): Unit = pendingWrite = pendingWrite.doWrite(info)

  def closeReason =
    if (channel.socket.isOutputShutdown) ConfirmedClosed
    else PeerClosed

  def handleClose(info: ConnectionInfo, closeCommander: Option[ActorRef],
                  closedEvent: ConnectionClosed): Unit = closedEvent match {
    case Aborted ⇒
      if (TraceLogging) log.debug("Got Abort command. RESETing connection.")
      doCloseConnection(info.handler, closeCommander, closedEvent)
    case PeerClosed if info.keepOpenOnPeerClosed ⇒
      // report that peer closed the connection
      info.handler ! PeerClosed
      // used to check if peer already closed its side later
      peerClosed = true
      context.become(peerSentEOF(info))
    case _ if writePending ⇒ // finish writing first
      if (TraceLogging) log.debug("Got Close command but write is still pending.")
      context.become(closingWithPendingWrite(info, closeCommander, closedEvent))
    case ConfirmedClosed ⇒ // shutdown output and wait for confirmation
      if (TraceLogging) log.debug("Got ConfirmedClose command, sending FIN.")
      channel.socket.shutdownOutput()

      if (peerClosed) // if peer closed first, the socket is now fully closed
        doCloseConnection(info.handler, closeCommander, closedEvent)
      else context.become(closing(info, closeCommander))
    case _ ⇒ // close now
      if (TraceLogging) log.debug("Got Close command, closing connection.")
      doCloseConnection(info.handler, closeCommander, closedEvent)
  }

  def doCloseConnection(handler: ActorRef, closeCommander: Option[ActorRef], closedEvent: ConnectionClosed): Unit = {
    if (closedEvent == Aborted) abort()
    else channel.close()
    stopWith(CloseInformation(Set(handler) ++ closeCommander, closedEvent))
  }

  def handleError(handler: ActorRef, exception: IOException): Unit = {
    log.debug("Closing connection due to IO error {}", exception)
    stopWith(CloseInformation(Set(handler), ErrorClosed(extractMsg(exception))))
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

  def stopWith(closeInfo: CloseInformation): Unit = {
    closedMessage = closeInfo
    context.stop(self)
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

    def doWrite(info: ConnectionInfo): PendingWrite = {
      @tailrec def innerWrite(pendingWrite: PendingBufferWrite): PendingWrite = {
        val toWrite = pendingWrite.buffer.remaining()
        require(toWrite != 0)
        val writtenBytes = channel.write(pendingWrite.buffer)
        if (TraceLogging) log.debug("Wrote [{}] bytes to channel", writtenBytes)

        val nextWrite = pendingWrite.consume(writtenBytes)

        if (pendingWrite.hasData)
          if (writtenBytes == toWrite) innerWrite(nextWrite) // wrote complete buffer, try again now
          else {
            info.registration.enableInterest(OP_WRITE)
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
      catch { case e: IOException ⇒ handleError(info.handler, e); this }
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

    def doWrite(info: ConnectionInfo): PendingWrite = {
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
  case class CloseInformation(notificationsTo: Set[ActorRef], closedEvent: Event)

  /**
   * Groups required connection-related data that are only available once the connection has been fully established.
   */
  case class ConnectionInfo(registration: ChannelRegistration,
                            handler: ActorRef,
                            keepOpenOnPeerClosed: Boolean,
                            useResumeWriting: Boolean)

  // INTERNAL MESSAGES

  /** Informs actor that no writing was possible but there is still work remaining */
  case class SendBufferFull(remainingWrite: PendingWrite) extends NoSerializationVerificationNeeded
  /** Informs actor that a pending file write has finished */
  case object WriteFileFinished
  /** Informs actor that a pending WriteFile failed */
  case class WriteFileFailed(e: IOException)

  /** Abstraction over pending writes */
  trait PendingWrite {
    def commander: ActorRef
    def ack: Any

    def wantsAck = !ack.isInstanceOf[NoAck]
    def doWrite(info: ConnectionInfo): PendingWrite

    /** Release any open resources */
    def release(): Unit
  }
}
