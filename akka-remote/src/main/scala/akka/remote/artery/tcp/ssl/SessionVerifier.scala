package akka.remote.artery.tcp.ssl

import java.security.cert.X509Certificate

import javax.net.ssl.SSLSession

/**
 * 
 */
trait SessionVerifier {

  def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable]
  def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable]

}

object NoopSessionVerifier extends SessionVerifier{
  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] = None

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] = None
}

class PeerSubjectVerifier(peerCertificate:X509Certificate) extends SessionVerifier {

  override def verifyClientSession(hostname: String, session: SSLSession): Option[Throwable] =
    verifyPeerCertificates(session)

  override def verifyServerSession(hostname: String, session: SSLSession): Option[Throwable] =
    verifyPeerCertificates(session)

  private def verifyPeerCertificates(session: SSLSession) = {
    val mySubjectNames = X509Readers.getAllSubjectNames(peerCertificate)
    if (session.getPeerCertificates.length == 0) {
      Some(new IllegalArgumentException("No peer certificates"))
    }
    session.getPeerCertificates()(0) match {
      case x509: X509Certificate =>
        val peerSubjectNames =
          X509Readers.getAllSubjectNames(x509)
        if (mySubjectNames.exists(peerSubjectNames)) None
        else
          Some(
            new IllegalArgumentException(
              s"None of the peer subject names $peerSubjectNames were in local subject names $mySubjectNames"))
      case other =>
        Some(new IllegalArgumentException(s"Unknown certificate type: ${other.getClass}"))
    }
  }

}
