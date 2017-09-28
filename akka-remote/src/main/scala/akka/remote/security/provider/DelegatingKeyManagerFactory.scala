package akka.remote.security.provider

import java.security._
import java.util.Collections.{ emptyList, emptyMap }
import javax.net.ssl.{ KeyManager, KeyManagerFactory, KeyManagerFactorySpi, ManagerFactoryParameters }

class DelegatingKeyManagerFactory extends KeyManagerFactorySpi {
  private var delegate: Option[KeyManagerFactory] = None
  private val parameterName = classOf[DelegatingKeyManagerFactoryParameters].getCanonicalName

  @throws[IllegalStateException]
  override def engineGetKeyManagers(): Array[KeyManager] = delegate match {
    case Some(del) ⇒ del.getKeyManagers
    case None ⇒
      throw new IllegalStateException(s"Not yet initialised with a $parameterName")
  }

  @throws[KeyStoreException]
  override def engineInit(ks: KeyStore, password: Array[Char]): Unit = throw new KeyStoreException(s"Must be initialised with a $parameterName")

  @throws[InvalidAlgorithmParameterException]
  override def engineInit(spec: ManagerFactoryParameters): Unit = spec match {
    case DelegatingKeyManagerFactoryParameters(initDelegate) ⇒ this.delegate = Some(initDelegate)
    case _ ⇒ throw new InvalidAlgorithmParameterException(s"Supplied spec was a ${spec.getClass.getCanonicalName} but needed a $parameterName")
  }
}

case class DelegatingKeyManagerFactoryParameters(delegate: KeyManagerFactory) extends ManagerFactoryParameters

object DelegatingKeyManagerFactoryParameters {
  /**
   * Java API
   */
  def create(delegate: KeyManagerFactory): DelegatingKeyManagerFactoryParameters =
    DelegatingKeyManagerFactoryParameters(delegate)
}

object DelegatingKeyManagerFactoryProvider
  extends Provider("DelegatingKeyManagerFactoryProvider", 1.0d, "Delegating TrustManagerFactory") { outer ⇒
  AccessController.doPrivileged(new PrivilegedAction[Unit] {
    override def run(): Unit = {
      putService(new Provider.Service(
        outer, "KeyManagerFactory", KeyManagerFactory.getDefaultAlgorithm, classOf[DelegatingKeyManagerFactory].getCanonicalName, emptyList(), emptyMap()
      ))
    }
  })
}