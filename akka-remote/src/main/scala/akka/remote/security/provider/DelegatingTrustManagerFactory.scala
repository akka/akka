package akka.remote.security.provider

import java.security._
import java.util.Collections.{emptyList, emptyMap}

import akka.annotation.InternalApi
import javax.net.ssl.{ManagerFactoryParameters, TrustManager, TrustManagerFactory, TrustManagerFactorySpi}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class DelegatingTrustManagerFactory extends TrustManagerFactorySpi {
  private var delegate: Option[TrustManagerFactory] = None
  private val parameterName = classOf[DelegatingTrustManagerFactoryParameters].getCanonicalName

  @throws[IllegalStateException]
  override def engineGetTrustManagers(): Array[TrustManager] = delegate match {
    case Some(del) ⇒ del.getTrustManagers
    case None ⇒
      throw new IllegalStateException(s"Not yet initialised with a $parameterName")
  }

  @throws[KeyStoreException]
  override def engineInit(ks: KeyStore): Unit = throw new KeyStoreException(s"Must be initialised with a $parameterName")

  @throws[InvalidAlgorithmParameterException]
  override def engineInit(spec: ManagerFactoryParameters): Unit = spec match {
    case DelegatingTrustManagerFactoryParameters(initDelegate) ⇒ this.delegate = Some(initDelegate)
    case _ ⇒ throw new InvalidAlgorithmParameterException(s"Supplied spec was a ${spec.getClass.getCanonicalName} but needed a $parameterName")
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] case class DelegatingTrustManagerFactoryParameters(delegate: TrustManagerFactory) extends ManagerFactoryParameters

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object DelegatingTrustManagerFactoryProvider
  extends Provider("DelegatingTrustManagerFactoryProvider", 1.0d, "Delegating TrustManagerFactory") { outer ⇒
  AccessController.doPrivileged(new PrivilegedAction[Unit] {
    override def run(): Unit = {
      putService(new Provider.Service(
        outer, "TrustManagerFactory", TrustManagerFactory.getDefaultAlgorithm, classOf[DelegatingTrustManagerFactory].getCanonicalName, emptyList(), emptyMap()
      ))
    }
  })
}
