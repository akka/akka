package se.scalablesolutions.akka.remote

import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.{ManagerFactoryParameters,TrustManager,TrustManagerFactorySpi,X509TrustManager};

object DummyTrustManagerFactory extends TrustManagerFactorySpi {
  private val DUMMY_TRUST_MANAGER : TrustManager = new X509TrustManager {
    def getAcceptedIssuers() = Array[X509Certificate]()
    def checkClientTrusted(chain : Array[X509Certificate], authType : String) = println("UNKNOWN CLIENT CERTIFICATE: " + chain(0).getSubjectDN)
    def checkServerTrusted(chain : Array[X509Certificate], authType : String) = println("UNKNOWN SERVER CERTIFICATE: " + chain(0).getSubjectDN)
  }
  
  def getTrustManagers = Array(DUMMY_TRUST_MANAGER)
  
  protected override def engineGetTrustManagers = getTrustManagers 
  protected override def engineInit(keystore:KeyStore) = ()
  protected override def engineInit(managerFactoryParameters : ManagerFactoryParameters) = ()
}
