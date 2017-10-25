package akka.remote.transport.netty

import javax.net.ssl.TrustManager

trait CustomTrustManagerFactory {
  def create(filename: String, password: String): Array[TrustManager]
}