/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

// adapted from
// https://github.com/spray/spray/blob/eef5c4f54a0cadaf9e98298faf5b337f9adc04bb/spray-io/src/main/scala/spray/io/SslTlsSupport.scala
// original copyright notice follows:

/*
 * Copyright (C) 2011-2013 spray.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.io

import java.nio.ByteBuffer
import javax.net.ssl.{ SSLContext, SSLException, SSLEngineResult, SSLEngine }
import javax.net.ssl.SSLEngineResult.HandshakeStatus._
import javax.net.ssl.SSLEngineResult.Status._
import scala.collection.immutable
import scala.annotation.tailrec
import akka.util.ByteString
import Tcp.{ Command, Event }

object SslTlsSupport {

  // we are using Nettys default values:
  // 16665 + 1024 (room for compressed data) + 1024 (for OpenJDK compatibility)
  private final val MaxPacketSize = 16665 + 2048

  private final val EmptyByteArray = new Array[Byte](0)

}

/**
 * This pipeline stage implements SSL / TLS support, using an externally
 * configured [[javax.net.ssl.SSLEngine]]. It operates on the level of [[Tcp.Event]] and
 * [[Tcp.Command]] messages, which means that it will typically be one of
 * the lowest stages in a protocol stack. Since SSLEngine relies on contiguous
 * transmission of a data stream you will need to handle backpressure from
 * the TCP connection actor, for example by using a [[BackpressureBuffer]]
 * underneath the SSL stage.
 *
 * Each instance of this stage has a scratch [[ByteBuffer]] of approx. 18kiB
 * allocated which is used by the SSLEngine.
 *
 * One thing to keep in mind is that there's no support for half-closed connections
 * in SSL (but SSL on the other side requires half-closed connections from its transport
 * layer).
 *
 * This means:
 * 1. keepOpenOnPeerClosed is not supported on top of SSL (once you receive PeerClosed
 *    the connection is closed, further CloseCommands are ignored)
 * 2. keepOpenOnPeerClosed should always be enabled on the transport layer beneath SSL so
 *    that one can wait for the other side's SSL level close_notify message without barfing
 *    RST to the peer because this socket is already gone
 *
 */
class SslTlsSupport(engine: SSLEngine) extends PipelineStage[HasLogging, Command, Command, Event, Event] {

  override def apply(ctx: HasLogging) =
    new PipePair[Command, Command, Event, Event] {
      var pendingSends = immutable.Queue.empty[Send]
      var inboundReceptacle: ByteBuffer = _ // holds incoming data that are too small to be decrypted yet
      val log = ctx.getLogger
      // TODO: should this be a ThreadLocal?
      val tempBuf = ByteBuffer.allocate(SslTlsSupport.MaxPacketSize)
      var originalCloseCommand: Tcp.CloseCommand = _

      override val commandPipeline = (cmd: Command) ⇒ cmd match {
        case x: Tcp.Write ⇒
          if (pendingSends.isEmpty) encrypt(Send(x))
          else {
            pendingSends = pendingSends enqueue Send(x)
            Nil
          }

        case x @ (Tcp.Close | Tcp.ConfirmedClose) ⇒
          originalCloseCommand = x.asInstanceOf[Tcp.CloseCommand]
          log.debug("Closing SSLEngine due to reception of [{}]", x)
          engine.closeOutbound()
          // don't send close command to network here, it's the job of the SSL engine
          // to shutdown the connection when getting CLOSED in encrypt
          closeEngine()

        case x: Tcp.WriteCommand ⇒
          throw new IllegalArgumentException(
            "SslTlsSupport doesn't support Tcp.WriteCommands of type " + x.getClass.getSimpleName)

        case cmd ⇒ ctx.singleCommand(cmd)
      }

      val eventPipeline = (evt: Event) ⇒ evt match {
        case Tcp.Received(data) ⇒
          val buf = if (inboundReceptacle != null) {
            try ByteBuffer.allocate(inboundReceptacle.remaining + data.length).put(inboundReceptacle)
            finally inboundReceptacle = null
          } else ByteBuffer allocate data.length
          data copyToBuffer buf
          buf.flip()
          decrypt(buf)

        case x: Tcp.ConnectionClosed ⇒
          // After we have closed the connection we ignore FIN from the other side.
          // That's to avoid a strange condition where we know that no truncation attack
          // can happen any more (because we actively closed the connection) but the peer
          // isn't behaving properly and didn't send close_notify. Why is this condition strange?
          // Because if we had closed the connection directly after we sent close_notify (which
          // is allowed by the spec) we wouldn't even have noticed.
          if (!engine.isOutboundDone)
            try engine.closeInbound()
            catch { case e: SSLException ⇒ } // ignore warning about possible truncation attacks

          if (x.isAborted || (originalCloseCommand eq null)) ctx.singleEvent(x)
          else if (!engine.isInboundDone) ctx.singleEvent(originalCloseCommand.event)
          // else the close message was sent by decrypt case CLOSED
          else ctx.singleEvent(x)

        case ev ⇒ ctx.singleEvent(ev)
      }

      /**
       * Encrypts the given buffers and dispatches the results as Tcp.Write commands.
       */
      @tailrec
      def encrypt(send: Send, fromQueue: Boolean = false, commands: Vector[Result] = Vector.empty): Vector[Result] = {
        import send.{ ack, buffer }

        tempBuf.clear()
        val ackDefinedAndPreContentLeft = ack != Tcp.NoAck && buffer.remaining > 0
        val result = engine.wrap(buffer, tempBuf)
        val postContentLeft = buffer.remaining > 0
        tempBuf.flip()

        val nextCmds =
          if (tempBuf.remaining > 0) {
            val writeAck = if (ackDefinedAndPreContentLeft && !postContentLeft) ack else Tcp.NoAck
            commands :+ Right(Tcp.Write(ByteString(tempBuf), writeAck))
          } else commands

        result.getStatus match {
          case OK ⇒ result.getHandshakeStatus match {
            case NOT_HANDSHAKING | FINISHED ⇒
              if (postContentLeft) encrypt(send, fromQueue, nextCmds)
              else nextCmds
            case NEED_WRAP ⇒
              encrypt(send, fromQueue, nextCmds)
            case NEED_UNWRAP ⇒
              pendingSends =
                if (fromQueue) send +: pendingSends // output coming from the queue needs to go to the front
                else pendingSends enqueue send // "new" output to the back of the queue
              nextCmds
            case NEED_TASK ⇒
              runDelegatedTasks()
              encrypt(send, fromQueue, nextCmds)
          }
          case CLOSED ⇒
            if (postContentLeft) {
              log.warning("SSLEngine closed prematurely while sending")
              nextCmds :+ Right(Tcp.Abort)
            } else nextCmds :+ Right(Tcp.ConfirmedClose)
          case BUFFER_OVERFLOW ⇒
            throw new IllegalStateException("BUFFER_OVERFLOW: the SslBufferPool should make sure that buffers are never too small")
          case BUFFER_UNDERFLOW ⇒
            throw new IllegalStateException("BUFFER_UNDERFLOW should never appear as a result of a wrap")
        }
      }

      /**
       * Decrypts the given buffer and dispatches the results as Tcp.Received events.
       */
      @tailrec
      def decrypt(buffer: ByteBuffer, output: Vector[Result] = Vector.empty): Vector[Result] = {
        tempBuf.clear()
        val result = engine.unwrap(buffer, tempBuf)
        tempBuf.flip()

        val nextOutput =
          if (tempBuf.remaining > 0) output :+ Left(Tcp.Received(ByteString(tempBuf)))
          else output

        result.getStatus match {
          case OK ⇒ result.getHandshakeStatus match {
            case NOT_HANDSHAKING | FINISHED ⇒
              if (buffer.remaining > 0) decrypt(buffer, nextOutput)
              else nextOutput ++ processPendingSends(tempBuf)
            case NEED_UNWRAP ⇒
              decrypt(buffer, nextOutput)
            case NEED_WRAP ⇒
              val n = nextOutput ++ (
                if (pendingSends.isEmpty) encrypt(Send.Empty)
                else processPendingSends(tempBuf))
              if (buffer.remaining > 0) decrypt(buffer, n)
              else n
            case NEED_TASK ⇒
              runDelegatedTasks()
              decrypt(buffer, nextOutput)
          }
          case CLOSED ⇒
            if (!engine.isOutboundDone) {
              closeEngine(nextOutput :+ Left(Tcp.PeerClosed))
            } else { // now both sides are closed on the SSL level
              // close the underlying connection, we don't need it any more
              nextOutput :+ Left(originalCloseCommand.event) :+ Right(Tcp.Close)
            }
          case BUFFER_UNDERFLOW ⇒
            inboundReceptacle = buffer // save buffer so we can append the next one to it
            nextOutput
          case BUFFER_OVERFLOW ⇒
            throw new IllegalStateException("BUFFER_OVERFLOW: the SslBufferPool should make sure that buffers are never too small")
        }
      }

      @tailrec
      def runDelegatedTasks() {
        val task = engine.getDelegatedTask
        if (task != null) {
          task.run()
          runDelegatedTasks()
        }
      }

      @tailrec
      def processPendingSends(tempBuf: ByteBuffer, commands: Vector[Result] = Vector.empty): Vector[Result] = {
        if (pendingSends.nonEmpty) {
          val next = pendingSends.head
          pendingSends = pendingSends.tail
          val nextCmds = commands ++ encrypt(next, fromQueue = true)
          // it may be that the send we just passed to `encrypt` was put back into the queue because
          // the SSLEngine demands a `NEED_UNWRAP`, in this case we want to stop looping
          if (pendingSends.nonEmpty && pendingSends.head != next)
            processPendingSends(tempBuf)
          else nextCmds
        } else commands
      }

      @tailrec
      def closeEngine(commands: Vector[Result] = Vector.empty): Vector[Result] = {
        if (!engine.isOutboundDone) {
          closeEngine(commands ++ encrypt(Send.Empty))
        } else commands
      }
    }

  private final class Send(val buffer: ByteBuffer, val ack: Event)

  private object Send {
    val Empty = new Send(ByteBuffer wrap SslTlsSupport.EmptyByteArray, Tcp.NoAck)
    def apply(write: Tcp.Write) = {
      val buffer = ByteBuffer allocate write.data.length
      write.data copyToBuffer buffer
      buffer.flip()
      new Send(buffer, write.ack)
    }
  }
}
