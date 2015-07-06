/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.io

import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.util.ByteString
import javax.net.ssl._
import scala.annotation.varargs
import scala.collection.immutable
import java.security.cert.Certificate
import akka.event.Logging.simpleName

/**
 * Stream cipher support based upon JSSE.
 *
 * The underlying SSLEngine has four ports: plaintext input/output and
 * ciphertext input/output. These are modeled as a [[akka.stream.BidiShape]]
 * element for use in stream topologies, where the plaintext ports are on the
 * left hand side of the shape and the ciphertext ports on the right hand side.
 *
 * Configuring JSSE is a rather complex topic, please refer to the JDK platform
 * documentation or the excellent user guide that is part of the Play Framework
 * documentation. The philosophy of this integration into Akka Streams is to
 * expose all knobs and dials to client code and therefore not limit the
 * configuration possibilities. In particular the client code will have to
 * provide the SSLContext from which the SSLEngine is then created. Handshake
 * parameters are set using [[NegotiateNewSession]] messages, the settings for
 * the initial handshake need to be provided up front using the same class;
 * please refer to the method documentation below.
 *
 * '''IMPORTANT NOTE'''
 *
 * The TLS specification does not permit half-closing of the user data session
 * that it transports—to be precise a half-close will always promptly lead to a
 * full close. This means that canceling the plaintext output or completing the
 * plaintext input of the SslTls stage will lead to full termination of the
 * secure connection without regard to whether bytes are remaining to be sent or
 * received, respectively. Especially for a client the common idiom of attaching
 * a finite Source to the plaintext input and transforming the plaintext response
 * bytes coming out will not work out of the box due to early termination of the
 * connection. For this reason there is a parameter that determines whether the
 * SslTls stage shall ignore completion and/or cancellation events, and the
 * default is to ignore completion (in view of the client–server scenario). In
 * order to terminate the connection the client will then need to cancel the
 * plaintext output as soon as all expected bytes have been received. When
 * ignoring both types of events the stage will shut down once both events have
 * been received. See also [[Closing]].
 */
object SslTls {

  type ScalaFlow = scaladsl.BidiFlow[SslTlsOutbound, ByteString, ByteString, SslTlsInbound, Unit]
  type JavaFlow = javadsl.BidiFlow[SslTlsOutbound, ByteString, ByteString, SslTlsInbound, Unit]

  /**
   * Scala API: create a StreamTls [[akka.stream.scaladsl.BidiFlow]]. The
   * SSLContext will be used to create an SSLEngine to which then the
   * `firstSession` parameters are applied before initiating the first
   * handshake. The `role` parameter determines the SSLEngine’s role; this is
   * often the same as the underlying transport’s server or client role, but
   * that is not a requirement and depends entirely on the application
   * protocol.
   *
   * For a description of the `closing` parameter please refer to [[Closing]].
   */
  def apply(sslContext: SSLContext, firstSession: NegotiateNewSession,
            role: Role, closing: Closing = IgnoreComplete): ScalaFlow =
    new scaladsl.BidiFlow(TlsModule(Attributes.none, sslContext, firstSession, role, closing))

  /**
   * Java API: create a StreamTls [[akka.stream.javadsl.BidiFlow]] in client mode. The
   * SSLContext will be used to create an SSLEngine to which then the
   * `firstSession` parameters are applied before initiating the first
   * handshake. The `role` parameter determines the SSLEngine’s role; this is
   * often the same as the underlying transport’s server or client role, but
   * that is not a requirement and depends entirely on the application
   * protocol.
   *
   * This method uses the default closing behavior or [[IgnoreComplete]].
   */
  def create(sslContext: SSLContext, firstSession: NegotiateNewSession, role: Role): JavaFlow =
    new javadsl.BidiFlow(apply(sslContext, firstSession, role))

  /**
   * Java API: create a StreamTls [[akka.stream.javadsl.BidiFlow]] in client mode. The
   * SSLContext will be used to create an SSLEngine to which then the
   * `firstSession` parameters are applied before initiating the first
   * handshake. The `role` parameter determines the SSLEngine’s role; this is
   * often the same as the underlying transport’s server or client role, but
   * that is not a requirement and depends entirely on the application
   * protocol.
   *
   * For a description of the `closing` parameter please refer to [[Closing]].
   */
  def create(sslContext: SSLContext, firstSession: NegotiateNewSession, role: Role, closing: Closing): JavaFlow =
    new javadsl.BidiFlow(apply(sslContext, firstSession, role, closing))

  /**
   * INTERNAL API.
   */
  private[akka] case class TlsModule(plainIn: Inlet[SslTlsOutbound], plainOut: Outlet[SslTlsInbound],
                                     cipherIn: Inlet[ByteString], cipherOut: Outlet[ByteString],
                                     shape: Shape, attributes: Attributes,
                                     sslContext: SSLContext, firstSession: NegotiateNewSession,
                                     role: Role, closing: Closing) extends Module {
    override def subModules: Set[Module] = Set.empty

    override def withAttributes(att: Attributes): Module = copy(attributes = att)
    override def carbonCopy: Module = {
      val mod = TlsModule(attributes, sslContext, firstSession, role, closing)
      if (plainIn == shape.inlets(0)) mod
      else mod.replaceShape(mod.shape.asInstanceOf[BidiShape[_, _, _, _]].reversed)
    }

    override def replaceShape(s: Shape) =
      if (s == shape) this
      else if (shape.hasSamePortsAs(s)) copy(shape = s)
      else throw new IllegalArgumentException("trying to replace shape with different ports")
  }

  /**
   * INTERNAL API.
   */
  private[akka] object TlsModule {
    def apply(attributes: Attributes, sslContext: SSLContext, firstSession: NegotiateNewSession, role: Role, closing: Closing): TlsModule = {
      val name = attributes.nameOrDefault(s"StreamTls($role)")
      val cipherIn = Inlet[ByteString](s"$name.cipherIn")
      val cipherOut = Outlet[ByteString](s"$name.cipherOut")
      val plainIn = Inlet[SslTlsOutbound](s"$name.transportIn")
      val plainOut = Outlet[SslTlsInbound](s"$name.transportOut")
      val shape = new BidiShape(plainIn, cipherOut, cipherIn, plainOut)
      TlsModule(plainIn, plainOut, cipherIn, cipherOut, shape, attributes, sslContext, firstSession, role, closing)
    }
  }
}

/**
 * This object holds simple wrapping [[BidiFlow]] implementations that can
 * be used instead of [[SslTls]] when no encryption is desired. The flows will
 * just adapt the message protocol by wrapping into [[SessionBytes]] and
 * unwrapping [[SendBytes]].
 */
object SslTlsPlacebo {
  val forScala: scaladsl.BidiFlow[SslTlsOutbound, ByteString, ByteString, SessionBytes, Unit] =
    scaladsl.BidiFlow() { implicit b ⇒
      // this constructs a session for (invalid) protocol SSL_NULL_WITH_NULL_NULL
      val session = SSLContext.getDefault.createSSLEngine.getSession
      val top = b.add(scaladsl.Flow[SslTlsOutbound].collect { case SendBytes(b) ⇒ b })
      val bottom = b.add(scaladsl.Flow[ByteString].map(SessionBytes(session, _)))
      BidiShape(top, bottom)
    }
  val forJava: javadsl.BidiFlow[SslTlsOutbound, ByteString, ByteString, SessionBytes, Unit] =
    new javadsl.BidiFlow(forScala)
}

/**
 * Many protocols are asymmetric and distinguish between the client and the
 * server, where the latter listens passively for messages and the former
 * actively initiates the exchange.
 */
object Role {
  /**
   * Java API: obtain the [[Client]] singleton value.
   */
  def client: Role = Client
  /**
   * Java API: obtain the [[Server]] singleton value.
   */
  def server: Role = Server
}
sealed abstract class Role

/**
 * The client is usually the side that consumes the service provided by its
 * interlocutor. The precise interpretation of this role is protocol specific.
 */
sealed abstract class Client extends Role
case object Client extends Client

/**
 * The server is usually the side the provides the service to its interlocutor.
 * The precise interpretation of this role is protocol specific.
 */
sealed abstract class Server extends Role
case object Server extends Server

/**
 * All streams in Akka are unidirectional: while in a complex flow graph data
 * may flow in multiple directions these individual flows are independent from
 * each other. The difference between two half-duplex connections in opposite
 * directions and a full-duplex connection is that the underlying transport
 * is shared in the latter and tearing it down will end the data transfer in
 * both directions.
 *
 * When integrating a full-duplex transport medium that does not support
 * half-closing (which means ending one direction of data transfer without
 * ending the other) into a stream topology, there can be unexpected effects.
 * Feeding a finite Source into this medium will close the connection after
 * all elements have been sent, which means that possible replies may not
 * be received in full. To support this type of usage, the sending and
 * receiving of data on the same side (e.g. on the [[Client]]) need to be
 * coordinated such that it is known when all replies have been received.
 * Only then should the transport be shut down.
 *
 * To support these scenarios it is recommended that the full-duplex
 * transport integration is configurable in terms of termination handling,
 * which means that the user can optionally suppress the normal (closing)
 * reaction to completion or cancellation events, as is expressed by the
 * possible values of this type:
 *
 *  - [[EagerClose]] means to not ignore signals
 *  - [[IgnoreCancel]] means to not react to cancellation of the receiving
 *    side unless the sending side has already completed
 *  - [[IgnoreComplete]] means to not react to the completion of the sending
 *    side unless the receiving side has already cancelled
 *  - [[IgnoreBoth]] means to ignore the first termination signal—be that
 *    cancellation or completion—and only act upon the second one
 */
sealed abstract class Closing {
  def ignoreCancel: Boolean
  def ignoreComplete: Boolean
}
object Closing {
  /**
   * Java API: obtain the [[EagerClose]] singleton value.
   */
  def eagerClose: Closing = EagerClose
  /**
   * Java API: obtain the [[IgnoreCancel]] singleton value.
   */
  def ignoreCancel: Closing = IgnoreCancel
  /**
   * Java API: obtain the [[IgnoreComplete]] singleton value.
   */
  def ignoreComplete: Closing = IgnoreComplete
  /**
   * Java API: obtain the [[IgnoreBoth]] singleton value.
   */
  def ignoreBoth: Closing = IgnoreBoth
}

/**
 * see [[Closing]]
 */
sealed abstract class EagerClose extends Closing {
  override def ignoreCancel = false
  override def ignoreComplete = false
}
case object EagerClose extends EagerClose

/**
 * see [[Closing]]
 */
sealed abstract class IgnoreCancel extends Closing {
  override def ignoreCancel = true
  override def ignoreComplete = false
}
case object IgnoreCancel extends IgnoreCancel

/**
 * see [[Closing]]
 */
sealed abstract class IgnoreComplete extends Closing {
  override def ignoreCancel = false
  override def ignoreComplete = true
}
case object IgnoreComplete extends IgnoreComplete

/**
 * see [[Closing]]
 */
sealed abstract class IgnoreBoth extends Closing {
  override def ignoreCancel = true
  override def ignoreComplete = true
}
case object IgnoreBoth extends IgnoreBoth

/**
 * This is the supertype of all messages that the SslTls stage emits on the
 * plaintext side.
 */
sealed trait SslTlsInbound

/**
 * If the underlying transport is closed before the final TLS closure command
 * is received from the peer then the SSLEngine will throw an SSLException that
 * warns about possible truncation attacks. This exception is caught and
 * translated into this message when encountered. Most of the time this occurs
 * not because of a malicious attacker but due to a connection abort or a
 * misbehaving communication peer.
 */
sealed abstract class SessionTruncated extends SslTlsInbound
case object SessionTruncated extends SessionTruncated

/**
 * Plaintext bytes emitted by the SSLEngine are received over one specific
 * encryption session and this class bundles the bytes with the SSLSession
 * object. When the session changes due to renegotiation (which can be
 * initiated by either party) the new session value will not compare equal to
 * the previous one.
 *
 * The Java API for getting session information is given by the SSLSession object,
 * the Scala API adapters are offered below.
 */
case class SessionBytes(session: SSLSession, bytes: ByteString) extends SslTlsInbound {
  /**
   * Scala API: Extract the certificates that were actually used by this
   * engine during this session’s negotiation. The list is empty if no
   * certificates were used.
   */
  def localCertificates: List[Certificate] = Option(session.getLocalCertificates).map(_.toList).getOrElse(Nil)
  /**
   * Scala API: Extract the Principal that was actually used by this engine
   * during this session’s negotiation.
   */
  def localPrincipal = Option(session.getLocalPrincipal)
  /**
   * Scala API: Extract the certificates that were used by the peer engine
   * during this session’s negotiation. The list is empty if no certificates
   * were used.
   */
  def peerCertificates =
    try Option(session.getPeerCertificates).map(_.toList).getOrElse(Nil)
    catch { case e: SSLPeerUnverifiedException ⇒ Nil }
  /**
   * Scala API: Extract the Principal that the peer engine presented during
   * this session’s negotiation.
   */
  def peerPrincipal =
    try Option(session.getPeerPrincipal)
    catch { case e: SSLPeerUnverifiedException ⇒ None }
}

/**
 * This is the supertype of all messages that the SslTls stage accepts on its
 * plaintext side.
 */
sealed trait SslTlsOutbound

/**
 * Initiate a new session negotiation. Any [[SendBytes]] commands following
 * this one will be held back (i.e. back-pressured) until the new handshake is
 * completed, meaning that the bytes following this message will be encrypted
 * according to the requirements outlined here.
 *
 * Each of the values in this message is optional and will have the following
 * effect if provided:
 *
 *  - `enabledCipherSuites` will be passed to `SSLEngine::setEnabledCipherSuites()`
 *  - `enabledProtocols` will be passed to `SSLEngine::setEnabledProtocols()`
 *  - `clientAuth` will be passed to `SSLEngine::setWantClientAuth()` or `SSLEngine.setNeedClientAuth()`, respectively
 *  - `sslParameters` will be passed to `SSLEngine::setSSLParameters()`
 */
case class NegotiateNewSession(
  enabledCipherSuites: Option[immutable.Seq[String]],
  enabledProtocols: Option[immutable.Seq[String]],
  clientAuth: Option[ClientAuth],
  sslParameters: Option[SSLParameters]) extends SslTlsOutbound {

  /**
   * Java API: Make a copy of this message with the given `enabledCipherSuites`.
   */
  @varargs
  def withCipherSuites(s: String*) = copy(enabledCipherSuites = Some(s.toList))

  /**
   * Java API: Make a copy of this message with the given `enabledProtocols`.
   */
  @varargs
  def withProtocols(p: String*) = copy(enabledProtocols = Some(p.toList))

  /**
   * Java API: Make a copy of this message with the given [[ClientAuth]] setting.
   */
  def withClientAuth(ca: ClientAuth) = copy(clientAuth = Some(ca))

  /**
   * Java API: Make a copy of this message with the given [[SSLParameters]].
   */
  def withParameters(p: SSLParameters) = copy(sslParameters = Some(p))
}

object NegotiateNewSession extends NegotiateNewSession(None, None, None, None) {
  /**
   * Java API: obtain the default value (which will leave the SSLEngine’s
   * settings unchanged).
   */
  def withDefaults = this
}

/**
 * Send the given [[akka.util.ByteString]] across the encrypted session to the
 * peer.
 */
case class SendBytes(bytes: ByteString) extends SslTlsOutbound

/**
 * An SSLEngine can either demand, allow or ignore its peer’s authentication
 * (via certificates), where `Need` will fail the handshake if the peer does
 * not provide valid credentials, `Want` allows the peer to send credentials
 * and verifies them if provided, and `None` disables peer certificate
 * verification.
 *
 * See the documentation for `SSLEngine::setWantClientAuth` for more
 * information.
 */
sealed abstract class ClientAuth
object ClientAuth {
  case object None extends ClientAuth
  case object Want extends ClientAuth
  case object Need extends ClientAuth

  def none: ClientAuth = None
  def want: ClientAuth = Want
  def need: ClientAuth = Need
}
