/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.stream.ssl

import java.nio.ByteBuffer
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.SSLEngineResult.HandshakeStatus._
import javax.net.ssl.SSLEngineResult.Status._
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession

import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.impl._
import akka.util.ByteString
import akka.util.ByteStringBuilder
import org.reactivestreams.Publisher
import org.reactivestreams.Subscriber

import scala.annotation.tailrec

object SslTlsCipher {

  /**
   * An established SSL session.
   */
  final case class InboundSession(
    sessionInfo: SessionInfo,
    data: Publisher[ByteString])

  /**
   * A request to establish an SSL session.
   * FIXME Not used right now since there is only one session established
   */
  final case class OutboundSession(
    negotiation: SessionNegotiation,
    data: Subscriber[ByteString])

  /**
   * Information about the established SSL session.
   */
  final case class SessionInfo(
    cipherSuite: String,
    localCertificates: List[Certificate],
    localPrincipal: Option[Principal],
    peerCertificates: List[Certificate],
    peerPrincipal: Option[Principal])

  object SessionInfo {

    def apply(engine: SSLEngine): SessionInfo =
      apply(engine.getSession)

    def apply(session: SSLSession): SessionInfo = {
      val localCertificates = Option(session.getLocalCertificates).map { _.toList } getOrElse Nil
      val localPrincipal = Option(session.getLocalPrincipal)
      val peerCertificates =
        try session.getPeerCertificates.toList
        catch { case e: SSLPeerUnverifiedException ⇒ Nil }
      val peerPrincipal =
        try Option(session.getPeerPrincipal)
        catch { case e: SSLPeerUnverifiedException ⇒ None }
      SessionInfo(session.getCipherSuite, localCertificates, localPrincipal, peerCertificates, peerPrincipal)
    }
  }

  /**
   * Information needed to establish an SSL session.
   */
  final case class SessionNegotiation(engine: SSLEngine)
}

final case class SslTlsCipher(
  sessionInbound: Publisher[SslTlsCipher.InboundSession],
  // FIXME We only have one session, and the SessionNegotiation is passed in via the constructor.
  // This should really be a Subscriber[SslTlsCipher.OutboundSession]
  plainTextOutbound: Subscriber[ByteString],
  cipherTextInbound: Subscriber[ByteString],
  cipherTextOutbound: Publisher[ByteString])

object SslTlsCipherActor {
  val EmptyByteArray = Array.empty[Byte]
  val EmptyByteBuffer = ByteBuffer.wrap(EmptyByteArray)
}

class SslTlsCipherActor(val requester: ActorRef, val sessionNegotioation: SslTlsCipher.SessionNegotiation, tracing: Boolean)
  extends Actor
  with ActorLogging
  with Pump
  with MultiStreamOutputProcessorLike
  with MultiStreamInputProcessorLike {

  override val subscriptionTimeoutSettings = ActorFlowMaterializerSettings(context.system).subscriptionTimeoutSettings

  def this(requester: ActorRef, sessionNegotioation: SslTlsCipher.SessionNegotiation) =
    this(requester, sessionNegotioation, false)

  import MultiStreamInputProcessor.SubstreamSubscriber
  import SslTlsCipherActor._

  private var _nextId = 0L
  protected def nextId(): Long = { _nextId += 1; _nextId }
  override protected val inputBufferSize = 1

  // The cipherTextInput (Subscriber[ByteString])
  val inboundCipherTextInput = createSubstreamInput()

  // The cipherTextOutput (Publisher[ByteString])
  val outboundCipherTextOutput = createSubstreamOutput()

  // The Publisher[SslTlsCipher.InboundSession]
  // FIXME For now there is only one session ever exposed
  val inboundSessionOutput = createSubstreamOutput()

  // The read side for the user (Publisher[ByteString])
  // FIXME For now there is only one session ever exposed
  val inboundPlaintextOutput = createSubstreamOutput()

  // The write side for the user (Subscriber[ByteString])
  // FIXME For now there is only one session ever exposed
  val outboundPlaintextInput = createSubstreamInput()

  // Plaintext bytes to be encrypted
  var plaintextOutboundBytes = EmptyByteBuffer
  val plaintextOutboundBytesPending = new TransferState {
    override def isReady = plaintextOutboundBytes.hasRemaining
    override def isCompleted = false
  }

  // Encrypted bytes to be sent
  val cipherTextOutboundBytes = new ByteStringBuilder

  // Encrypted bytes to be decrypted
  var cipherTextInboundBytes = EmptyByteBuffer
  val cipherTextInboundBytesPending = new TransferState {
    override def isReady = cipherTextInboundBytes.hasRemaining
    override def isCompleted = false
  }

  // Plaintext bytes to be received
  val plaintextInboundBytes = new ByteStringBuilder

  // FIXME: Change this into a pool of ByteBuffer later
  // These are Nettys default values
  // 16665 + 1024 (room for compressed data) + 1024 (for OpenJDK compatibility)
  val temporaryBuffer = ByteBuffer.allocate(16665 + 2048)

  val engine: SSLEngine = sessionNegotioation.engine

  def doWrap(tempBuf: ByteBuffer): SSLEngineResult = {
    tempBuf.clear()
    if (tracing) log.debug("before wrap {}", plaintextOutboundBytes.remaining)
    val result = engine.wrap(plaintextOutboundBytes, tempBuf)
    if (tracing) log.debug("after wrap {}", plaintextOutboundBytes.remaining)
    tempBuf.flip()
    if (tempBuf.hasRemaining) {
      val bs = ByteString(tempBuf)
      if (tracing) log.debug("wrap Enqueue cipher bytes {}", bs)
      cipherTextOutboundBytes ++= bs
    }
    result
  }

  def doUnwrap(tempBuf: ByteBuffer): SSLEngineResult = {
    tempBuf.clear()
    if (tracing) log.debug("before unwrap {}", cipherTextInboundBytes.remaining)
    val result = engine.unwrap(cipherTextInboundBytes, tempBuf)
    if (tracing) log.debug("after unwrap {}", cipherTextInboundBytes.remaining)
    tempBuf.flip()
    if (tempBuf.hasRemaining) {
      val bs = ByteString(tempBuf)
      if (tracing) log.debug("unwrap Enqueue cipher bytes {}", bs)
      plaintextInboundBytes ++= bs
    }
    result
  }

  def enqueueCipherInputBytes(data: ByteString): Unit = {
    cipherTextInboundBytes =
      if (cipherTextInboundBytes.hasRemaining) {
        val buffer = ByteBuffer.allocate(cipherTextInboundBytes.remaining + data.size)
        buffer.put(cipherTextInboundBytes)
        data.copyToBuffer(buffer)
        buffer.flip()
        buffer
      } else data.toByteBuffer
  }

  def writeCipherTextOutboundBytes() = {
    if (cipherTextOutboundBytes.length > 0) {
      val bs = cipherTextOutboundBytes.result()
      cipherTextOutboundBytes.clear()
      outboundCipherTextOutput.enqueueOutputElement(bs)
    }
  }

  def writePlaintextInboundBytes() = {
    if (plaintextInboundBytes.length > 0) {
      val bs = plaintextInboundBytes.result()
      plaintextInboundBytes.clear()
      inboundPlaintextOutput.enqueueOutputElement(bs)
    }
  }

  @tailrec
  private def runDelegatedTasks(): Unit = {
    val task = engine.getDelegatedTask
    if (task != null) {
      if (tracing) log.debug("Running delegated task {}", task)
      task.run()
      runDelegatedTasks()
    }
  }

  def publishSSLSessionEstablished(): Unit = {
    import SslTlsCipher._
    val info = SessionInfo(engine)
    val is = InboundSession(info, inboundPlaintextOutput.asInstanceOf[Publisher[ByteString]])
    if (tracing) log.debug("#### Handshake done!")
    inboundSessionOutput.enqueueOutputElement(is)
  }

  val unwrapPhase: TransferPhase = TransferPhase(inboundCipherTextInput.NeedsInput || cipherTextInboundBytesPending) { () ⇒
    if (tracing) log.debug("### UNWRAP")
    if (inboundCipherTextInput.NeedsInput.isReady)
      enqueueCipherInputBytes(inboundCipherTextInput.dequeueInputElement().asInstanceOf[ByteString])
    val result = doUnwrap(temporaryBuffer)
    val rs = result.getStatus
    if (tracing) log.debug("## UNWRAP {}", rs)
    val hs = result.getHandshakeStatus
    val next = rs match {
      case OK ⇒
        handshakePhase(hs)
      case CLOSED           ⇒ if (!engine.isInboundDone) encryptionPhase else completedPhase
      case BUFFER_OVERFLOW  ⇒ throw new IllegalStateException // the SslBufferPool should make sure that buffers are never too small
      case BUFFER_UNDERFLOW ⇒ throw new IllegalStateException // should never appear as a result of a wrap
    }
    nextPhase(next)
  }

  val wrapPhase: TransferPhase = TransferPhase(outboundCipherTextOutput.NeedsDemand) { () ⇒
    if (tracing) log.debug("### WRAP")
    val result = doWrap(temporaryBuffer)
    val rs = result.getStatus
    if (tracing) log.debug("## WRAP {}", rs)
    val hs = result.getHandshakeStatus
    val next = rs match {
      case OK ⇒
        writeCipherTextOutboundBytes()
        handshakePhase(hs)
      case CLOSED           ⇒ if (!engine.isInboundDone) decryptionPhase else completedPhase
      case BUFFER_OVERFLOW  ⇒ throw new IllegalStateException // the SslBufferPool should make sure that buffers are never too small
      case BUFFER_UNDERFLOW ⇒ throw new IllegalStateException // should never appear as a result of a wrap
    }
    nextPhase(next)
  }

  def handshakePhase(hs: SSLEngineResult.HandshakeStatus): TransferPhase = {
    if (tracing) log.debug("### HS {}", hs)
    hs match {
      case status @ (NOT_HANDSHAKING | FINISHED) ⇒
        if (status == FINISHED) publishSSLSessionEstablished()
        engineRunningPhase
      case NEED_WRAP   ⇒ wrapPhase
      case NEED_UNWRAP ⇒ unwrapPhase
      case NEED_TASK ⇒
        runDelegatedTasks()
        engine.getHandshakeStatus match {
          case NEED_WRAP   ⇒ wrapPhase
          case NEED_UNWRAP ⇒ unwrapPhase
          case x           ⇒ throw new IllegalStateException(s"Bad Handshake status $x")
        }
    }
  }

  val waitForHandshakeStartPhase: TransferPhase = TransferPhase((outboundPlaintextInput.NeedsInput || inboundCipherTextInput.NeedsInput) && inboundSessionOutput.NeedsDemand) { () ⇒
    if (tracing) log.debug("#### Starting Handshake")
    engine.beginHandshake()
    nextPhase(handshakePhase(engine.getHandshakeStatus))
  }

  val encryptionInputAvailable = outboundPlaintextInput.NeedsInput || plaintextOutboundBytesPending
  val decryptionInputAvailable = inboundCipherTextInput.NeedsInput || cipherTextInboundBytesPending

  val canEncrypt = encryptionInputAvailable && outboundCipherTextOutput.NeedsDemand
  val canDecrypt = decryptionInputAvailable && inboundPlaintextOutput.NeedsDemand

  val engineRunningPhase: TransferPhase = TransferPhase(canEncrypt || canDecrypt) { () ⇒
    if (tracing) log.debug("#### Engine running")
    if (canEncrypt.isExecutable) {
      nextPhase(encryptionPhase)
    } else {
      nextPhase(decryptionPhase)
    }
  }

  val encryptionPhase: TransferPhase = TransferPhase(canEncrypt) { () ⇒
    if (tracing) log.debug("### Encrypting")
    if (!plaintextOutboundBytesPending.isReady && outboundPlaintextInput.inputsAvailable) {
      val elem = outboundPlaintextInput.dequeueInputElement().asInstanceOf[ByteString]
      plaintextOutboundBytes = elem.asByteBuffer
    }
    val result = doWrap(temporaryBuffer)
    val rs = result.getStatus
    if (tracing) log.debug("## Encrypting {}", rs)
    val hs = result.getHandshakeStatus
    val next = rs match {
      case OK ⇒
        if (hs == NOT_HANDSHAKING) {
          writeCipherTextOutboundBytes()
          engineRunningPhase
        } else handshakePhase(hs)
      case CLOSED           ⇒ if (!engine.isInboundDone) decryptionPhase else completedPhase
      case BUFFER_OVERFLOW  ⇒ throw new IllegalStateException // the SslBufferPool should make sure that buffers are never too small
      case BUFFER_UNDERFLOW ⇒ throw new IllegalStateException // should never appear as a result of a wrap
    }
    nextPhase(next)
  }

  val decryptionPhase: TransferPhase = TransferPhase(canDecrypt) { () ⇒
    if (tracing) log.debug("### Decrypting")
    if (inboundCipherTextInput.NeedsInput.isReady) {
      val elem = inboundCipherTextInput.dequeueInputElement().asInstanceOf[ByteString]
      enqueueCipherInputBytes(elem)
    }
    val result = doUnwrap(temporaryBuffer)
    val rs = result.getStatus
    if (tracing) log.debug("## Decrypting {}", rs)
    val hs = result.getHandshakeStatus
    val next = rs match {
      case OK ⇒
        if (hs == NOT_HANDSHAKING) {
          writePlaintextInboundBytes()
          engineRunningPhase
        } else handshakePhase(hs)
      case CLOSED           ⇒ if (!engine.isOutboundDone) encryptionPhase else completedPhase
      case BUFFER_OVERFLOW  ⇒ throw new IllegalStateException // the SslBufferPool should make sure that buffers are never too small
      case BUFFER_UNDERFLOW ⇒ throw new IllegalStateException // should never appear as a result of a wrap
    }
    nextPhase(next)
  }

  nextPhase(waitForHandshakeStartPhase)

  override def preStart() {
    val plainTextInput = inboundSessionOutput.asInstanceOf[Publisher[SslTlsCipher.InboundSession]]
    val plainTextOutput = new SubstreamSubscriber[ByteString](self, outboundPlaintextInput.key)
    val cipherTextInput = new SubstreamSubscriber[ByteString](self, inboundCipherTextInput.key)
    val cipherTextOutput = outboundCipherTextOutput.asInstanceOf[Publisher[ByteString]]
    requester ! SslTlsCipher(plainTextInput, plainTextOutput, cipherTextInput, cipherTextOutput)
  }

  override def receive = inputSubstreamManagement orElse outputSubstreamManagement

  protected def fail(e: Throwable): Unit = {
    // FIXME: escalate to supervisor
    if (tracing) log.debug("fail {} due to: {}", self, e.getMessage)
    failInputs(e)
    failOutputs(e)
    context.stop(self)
  }

  override protected def pumpFailed(e: Throwable): Unit = fail(e)

  override protected def pumpFinished(): Unit = {
    finishInputs()
    finishOutputs()
  }
}
