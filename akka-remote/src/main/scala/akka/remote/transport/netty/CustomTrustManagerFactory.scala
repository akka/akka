package akka.remote.transport.netty

import javax.net.ssl.TrustManager

trait CustomTrustManagerFactory {
  def create(trustStore: String, pw: String): Array[TrustManager]
}