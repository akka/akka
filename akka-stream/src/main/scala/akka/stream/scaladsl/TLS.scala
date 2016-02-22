package akka.stream.scaladsl

import javax.net.ssl.{ SSLContext }

import akka.stream.impl.io.TlsModule
import akka.NotUsed
import akka.stream._
import akka.stream.TLSProtocol._
import akka.util.ByteString

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
 * been received. See also [[TLSClosing]].
 */
object TLS {

  /**
   * Create a StreamTls [[akka.stream.scaladsl.BidiFlow]]. The
   * SSLContext will be used to create an SSLEngine to which then the
   * `firstSession` parameters are applied before initiating the first
   * handshake. The `role` parameter determines the SSLEngine’s role; this is
   * often the same as the underlying transport’s server or client role, but
   * that is not a requirement and depends entirely on the application
   * protocol.
   *
   * For a description of the `closing` parameter please refer to [[TLSClosing]].
   *
   * The `hostInfo` parameter allows to optionally specify a pair of hostname and port
   * that will be used when creating the SSLEngine with `sslContext.createSslEngine`.
   * The SSLEngine may use this information e.g. when an endpoint identification algorithm was
   * configured using [[SSLParameters.setEndpointIdentificationAlgorithm]].
   */
  def apply(sslContext: SSLContext, firstSession: NegotiateNewSession, role: TLSRole,
            closing: TLSClosing = IgnoreComplete, hostInfo: Option[(String, Int)] = None): scaladsl.BidiFlow[SslTlsOutbound, ByteString, ByteString, SslTlsInbound, NotUsed] =
    new scaladsl.BidiFlow(TlsModule(Attributes.none, sslContext, firstSession, role, closing, hostInfo))

}

/**
 * This object holds simple wrapping [[akka.stream.scaladsl.BidiFlow]] implementations that can
 * be used instead of [[TLS]] when no encryption is desired. The flows will
 * just adapt the message protocol by wrapping into [[SessionBytes]] and
 * unwrapping [[SendBytes]].
 */
object TLSPlacebo {
  // this constructs a session for (invalid) protocol SSL_NULL_WITH_NULL_NULL
  private[akka] val dummySession = SSLContext.getDefault.createSSLEngine.getSession

  def apply(): scaladsl.BidiFlow[SslTlsOutbound, ByteString, ByteString, SessionBytes, NotUsed] = instance

  private val instance: scaladsl.BidiFlow[SslTlsOutbound, ByteString, ByteString, SessionBytes, NotUsed] =
    scaladsl.BidiFlow.fromGraph(scaladsl.GraphDSL.create() { implicit b ⇒
      val top = b.add(scaladsl.Flow[SslTlsOutbound].collect { case SendBytes(bytes) ⇒ bytes })
      val bottom = b.add(scaladsl.Flow[ByteString].map(SessionBytes(dummySession, _)))
      BidiShape.fromFlows(top, bottom)
    })
}

import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.{ SSLPeerUnverifiedException, SSLSession }

/** Allows access to an SSLSession with Scala types */
trait ScalaSessionAPI {

  /**
   * The underlying [[javax.net.ssl.SSLSession]].
   */
  def session: SSLSession

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
  def localPrincipal: Option[Principal] = Option(session.getLocalPrincipal)
  /**
   * Scala API: Extract the certificates that were used by the peer engine
   * during this session’s negotiation. The list is empty if no certificates
   * were used.
   */
  def peerCertificates: List[Certificate] =
    try Option(session.getPeerCertificates).map(_.toList).getOrElse(Nil)
    catch { case e: SSLPeerUnverifiedException ⇒ Nil }
  /**
   * Scala API: Extract the Principal that the peer engine presented during
   * this session’s negotiation.
   */
  def peerPrincipal: Option[Principal] =
    try Option(session.getPeerPrincipal)
    catch { case e: SSLPeerUnverifiedException ⇒ None }
}

object ScalaSessionAPI {
  /** Constructs a ScalaSessionAPI instance from an SSLSession */
  def apply(_session: SSLSession): ScalaSessionAPI =
    new ScalaSessionAPI {
      def session: SSLSession = _session
    }
}
