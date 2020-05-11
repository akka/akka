package akka.remote.artery.tcp.ssl

import java.security.cert.X509Certificate

import javax.naming.ldap.LdapName

import scala.collection.JavaConverters._

/**
 * 
 */
object X509Readers {

  def getAllSubjectNames(cert: X509Certificate): Set[String] = {
    val maybeCommonName =
      new LdapName(cert.getSubjectX500Principal.getName).getRdns.asScala
        .collectFirst {
          case attr if attr.getType.equalsIgnoreCase("CN") =>
            attr.getValue.toString
        }

    val alternates = Option(cert.getSubjectAlternativeNames)
      .map(_.asScala)
      .getOrElse(Nil)
      .collect {
        // See the javadocs for what this list contains, first element should be an integer,
        // if that integer is 2, then the second element is a String containing the DNS name.
        case list if list.get(0) == 2 =>
          list.get(1) match {
            case dnsName: String => dnsName
            case other =>
              throw new IllegalArgumentException(
                s"Expected a string, but got a ${other.getClass}"
              )
          }
      }

    maybeCommonName.toSet ++ alternates
  }

}
