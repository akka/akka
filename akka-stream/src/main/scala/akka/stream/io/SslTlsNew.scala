/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import java.nio.{ BufferOverflowException, ByteBuffer }
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import javax.net.ssl.SSLEngineResult.HandshakeStatus._
import javax.net.ssl.SSLEngineResult.Status._
import javax.net.ssl.{ SSLEngine, SSLContext }

import akka.event.LoggingAdapter
import akka.stream.{ Attributes, Outlet, Inlet, BidiShape }
import akka.stream.stage.{ OutHandler, InHandler, GraphStageLogic, GraphStage }
import akka.util.ByteString

import scala.annotation.tailrec

object SslTlsNew {

}

class SslTlsNew(
  sslContext: SSLContext,
  firstSession: NegotiateNewSession,
  role: Role,
  closing: Closing,
  hostInfo: Option[(String, Int)],
  tracing: Boolean)

  extends GraphStage[BidiShape[SslTlsOutbound, ByteString, ByteString, SslTlsInbound]] {
  private val name = s"StreamTls($role)"
  val cipherIn: Inlet[ByteString] = Inlet[ByteString](s"$name.cipherIn")
  val cipherOut: Outlet[ByteString] = Outlet[ByteString](s"$name.cipherOut")
  val plainIn: Inlet[SslTlsOutbound] = Inlet[SslTlsOutbound](s"$name.transportIn")
  val plainOut: Outlet[SslTlsInbound] = Outlet[SslTlsInbound](s"$name.transportOut")

  override val shape: BidiShape[SslTlsOutbound, ByteString, ByteString, SslTlsInbound] =
    new BidiShape(plainIn, cipherOut, cipherIn, plainOut)

  override def toString = name

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

    /**
     * Creates a ByteBuffer based interface over an otherwise ByteString based port.
     */
    // FIXME extract as a general thing usable for building other NIO based stages
    abstract class ByteBufferInput[T](private val in: Inlet[T],
                                      private val extractByteString: T ⇒ ByteString,
                                      private val capacity: Int)
      extends InHandler {

      val byteBuffer = ByteBuffer.allocate(capacity)
      private var buffer = ByteString.empty

      byteBuffer.clear()
      byteBuffer.limit(0)

      protected def onMoreBytes(): Unit
      protected def onInputFinished(): Unit
      protected def onInputFailed(ex: Throwable): Unit = failStage(ex)

      final override def onPush(): Unit = {
        buffer ++= extractByteString(grab(in))
        readMoreBytesIntoBuffer()
      }

      final override def onUpstreamFinish(): Unit = if (buffer.isEmpty && (byteBuffer.position == 0)) onInputFinished()
      final override def onUpstreamFailure(ex: Throwable): Unit = onInputFailed(ex)

      final def hasMoreBytes: Boolean =
        buffer.nonEmpty || !isClosed(in)

      final def readMoreBytesIntoBuffer(): Unit = {
        if (buffer.isEmpty && !hasBeenPulled(in)) pull(in)
        else if (byteBuffer.position < capacity) {
          println("reading to buffer")
          byteBuffer.compact()
          val copied = buffer.copyToBuffer(byteBuffer)
          buffer = buffer.drop(copied)
          byteBuffer.flip()
          onMoreBytes()
        } else throw new BufferOverflowException()
      }

    }

    abstract class ByteBufferOutput[T](private val out: Outlet[T],
                                       private val wrapByteString: ByteString ⇒ T,
                                       private val capacity: Int) extends OutHandler {

      val byteBuffer = ByteBuffer.allocate(capacity)
      byteBuffer.clear()
      private var closed = false

      protected def onMoreSpace(): Unit
      protected def onOutputFinished(): Unit

      final def flushAndComplete(): Unit = {
        closed = true
        flushBytes()
      }

      final def flushBytes(): Unit = {
        if (isAvailable(out)) {
          byteBuffer.flip()
          if (byteBuffer.hasRemaining) {
            push(out, wrapByteString(ByteString.fromByteBuffer(byteBuffer)))
            byteBuffer.clear()
            onMoreSpace()
          } else byteBuffer.clear()
        } else if (closed) complete(out)
      }

      final override def onPull(): Unit = flushBytes()
      final override def onDownstreamFinish(): Unit = onOutputFinished()
    }

    val engine: SSLEngine = {
      val e = hostInfo match {
        case Some((hostname, port)) ⇒ sslContext.createSSLEngine(hostname, port)
        case None                   ⇒ sslContext.createSSLEngine()
      }

      e.setUseClientMode(role == Client)
      e
    }

    var currentSession = engine.getSession

    /*
     * So here’s the big picture summary: the SSLEngine is the boss, and it can
     * be in several states. Depending on this state, we may want to react to
     * different input and output conditions.
     *
     *  - normal bidirectional operation (does both outbound and inbound)
     *  - outbound close initiated, inbound still open
     *  - inbound close initiated, outbound still open
     *  - fully closed
     *
     * Upon reaching the last state we obviously just shut down. In addition to
     * these user-data states, the engine may at any point in time also be
     * handshaking. This is mostly transparent, but it has an influence on the
     * outbound direction:
     *
     *  - if the local user triggered a re-negotiation, cork all user data until
     *    that is finished
     *  - if the outbound direction has been closed, trigger outbound readiness
     *    based upon HandshakeStatus.NEED_WRAP
     *
     * These conditions lead to the introduction of a synthetic TransferState
     * representing the Engine.
     */

    var lastHandshakeStatus: HandshakeStatus = _
    var corkUser = true

    def applySessionParameters(params: NegotiateNewSession): Unit = {
      import params._
      enabledCipherSuites foreach (cs ⇒ engine.setEnabledCipherSuites(cs.toArray))
      enabledProtocols foreach (p ⇒ engine.setEnabledProtocols(p.toArray))
      clientAuth match {
        case Some(ClientAuth.None) ⇒ engine.setNeedClientAuth(false)
        case Some(ClientAuth.Want) ⇒ engine.setWantClientAuth(true)
        case Some(ClientAuth.Need) ⇒ engine.setNeedClientAuth(true)
        case None                  ⇒ // do nothing
      }
      sslParameters foreach (p ⇒ engine.setSSLParameters(p))
      engine.beginHandshake()
      lastHandshakeStatus = engine.getHandshakeStatus
    }

    def setNewSessionParameters(params: NegotiateNewSession): Unit = {
      currentSession.invalidate()
      applySessionParameters(params)
      corkUser = true
    }

    // These are Nettys default values
    // 16665 + 1024 (room for compressed data) + 1024 (for OpenJDK compatibility)
    val bufPlainIn: ByteBufferInput[SslTlsOutbound] =
      new ByteBufferInput[SslTlsOutbound](plainIn, _.bytes, 16665 + 2048) {
        override protected def onMoreBytes(): Unit = {
          if (!corkUser) {
            doWrap()
            bufCipherOut.flushBytes()
          }
        }

        override protected def onInputFinished(): Unit = engine.closeOutbound()
      }

    val bufCipherOut: ByteBufferOutput[ByteString] =
      new ByteBufferOutput[ByteString](cipherOut, identity, 16665 + 2048) {
        override protected def onMoreSpace(): Unit = bufPlainIn.readMoreBytesIntoBuffer()
        override protected def onOutputFinished(): Unit = completeStage()
      }

    val bufCipherIn: ByteBufferInput[ByteString] =
      new ByteBufferInput[ByteString](cipherIn, identity, 16665 + 2048) {
        override protected def onMoreBytes(): Unit = {
          doUnwrap()
          bufPlainOut.flushBytes()
        }

        override protected def onInputFinished(): Unit = engine.closeInbound()
      }

    val bufPlainOut: ByteBufferOutput[SslTlsInbound] =
      new ByteBufferOutput[SslTlsInbound](plainOut, SessionBytes(currentSession, _), 16665 * 2 + 2048) {
        override protected def onMoreSpace(): Unit = bufCipherIn.readMoreBytesIntoBuffer()
        override protected def onOutputFinished(): Unit = completeStage()
      }

    setHandler(plainIn, bufPlainIn)
    setHandler(plainOut, bufPlainOut)
    setHandler(cipherIn, bufCipherIn)
    setHandler(cipherOut, bufCipherOut)

    /**
     * Invoked before any external events are processed, at the startup of the stage.
     */
    override def preStart(): Unit = {
      bufCipherIn.readMoreBytesIntoBuffer()
      applySessionParameters(firstSession)
      doWrap()
    }

    private def log: LoggingAdapter = interpreter.log

    private def doWrap(): Unit = {
      val result = engine.wrap(bufPlainIn.byteBuffer, bufCipherOut.byteBuffer)
      lastHandshakeStatus = result.getHandshakeStatus
      if (tracing) log.debug(s"wrap: status=${result.getStatus} " +
        s"handshake=$lastHandshakeStatus " +
        s"remaining=${bufPlainIn.byteBuffer.remaining} " +
        s"out=${bufCipherOut.byteBuffer.position}")

      runDelegatedTasks()
      if (lastHandshakeStatus == FINISHED) handshakeFinished()
      result.getStatus match {
        case OK     ⇒ bufCipherOut.flushBytes()
        case CLOSED ⇒ bufCipherOut.flushAndComplete()
        case s      ⇒ failStage(new IllegalStateException(s"unexpected status $s in doWrap()"))
      }
    }

    @tailrec
    private def doUnwrap(ignoreOutput: Boolean = false): Unit = {
      val result = engine.unwrap(bufCipherIn.byteBuffer, bufPlainOut.byteBuffer)
      if (ignoreOutput) bufPlainOut.byteBuffer.clear()
      lastHandshakeStatus = result.getHandshakeStatus
      if (tracing) log.debug(s"unwrap: status=${result.getStatus} " +
        s"handshake=$lastHandshakeStatus " +
        s"remaining=${bufCipherIn.byteBuffer.remaining} " +
        s"out=${bufPlainOut.byteBuffer.position}")

      runDelegatedTasks()
      result.getStatus match {
        case OK ⇒
          lastHandshakeStatus match {
            case NEED_WRAP ⇒
              doWrap()
              bufCipherOut.flushBytes()
            case FINISHED ⇒
              handshakeFinished()
            case NEED_UNWRAP ⇒ doUnwrap()
          }
        case CLOSED           ⇒
        case BUFFER_UNDERFLOW ⇒ bufCipherIn.readMoreBytesIntoBuffer()
        case s                ⇒ failStage(new IllegalStateException(s"unexpected status $s in doUnwrap()"))
      }
    }

    private def handshakeFinished(): Unit = {
      if (tracing) log.debug("handshake finished")
      currentSession = engine.getSession
      corkUser = false
    }

    @tailrec
    private def runDelegatedTasks(): Unit = {
      val task = engine.getDelegatedTask
      if (task != null) {
        if (tracing) log.debug("running task")
        task.run()
        runDelegatedTasks()
      } else {
        val st = lastHandshakeStatus
        lastHandshakeStatus = engine.getHandshakeStatus
        if (tracing && st != lastHandshakeStatus) log.debug(s"handshake status after tasks: $lastHandshakeStatus")
      }
    }

  }
}
