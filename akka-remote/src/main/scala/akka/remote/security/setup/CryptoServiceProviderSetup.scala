package akka.remote.security.setup

import java.lang.reflect.Modifier
import java.security.{ AccessController, PrivilegedAction, Provider }
import java.util.Collections.{ emptyList, emptyMap }
import javax.net.ssl._

import akka.actor.setup.Setup
import akka.remote.security.provider._

import scala.reflect.ClassTag

abstract class CryptoServiceProviderSetup extends Setup {
  def provider: Provider
}

private[setup] object CryptoServiceProviderSetup {
  def validate[T](tag: ClassTag[T]): Unit = {
    val clazz = tag.runtimeClass
    require(Modifier.isPublic(clazz.getModifiers), s"$tag must be a public class")
    require(!clazz.isLocalClass, s"$tag must not be a local class")
    require(!clazz.isMemberClass, s"$tag must must not be a member class")
  }
}

case class KeyManagerFactorySetup(provider: Provider, keyManagerFactoryParameters: Option[ManagerFactoryParameters])
  extends CryptoServiceProviderSetup

object KeyManagerFactorySetup {
  /**
   * Java API
   */
  def create(provider: Provider): KeyManagerFactorySetup = KeyManagerFactorySetup(provider, None)

  /**
   * Java API
   */
  def create(keyManagerFactoryProvider: Provider, keyManagerFactoryParameters: ManagerFactoryParameters): KeyManagerFactorySetup =
    KeyManagerFactorySetup(keyManagerFactoryProvider, Some(keyManagerFactoryParameters))

  def providing[T <: KeyManagerFactorySpi](managerFactoryParameters: Option[ManagerFactoryParameters] = None)(implicit tag: ClassTag[T]): KeyManagerFactorySetup = {
    CryptoServiceProviderSetup.validate(tag)

    val provider = new Provider(s"$tag-provider", 1.0d, s"KeyManagerFactory providing $tag") { outer ⇒
      AccessController.doPrivileged(new PrivilegedAction[Unit] {
        override def run(): Unit = {
          putService(new Provider.Service(
            outer, "KeyManagerFactory", KeyManagerFactory.getDefaultAlgorithm, tag.runtimeClass.getCanonicalName, emptyList(), emptyMap()
          ))
        }
      })
    }
    KeyManagerFactorySetup(provider, managerFactoryParameters)
  }

  /**
   * Java API
   */
  def createProviding[T <: KeyManagerFactorySpi](clazz: Class[T]): KeyManagerFactorySetup = {
    providing[T]()(ClassTag(clazz))
  }

  /**
   * Java API
   */
  def createProviding[T <: KeyManagerFactorySpi](clazz: Class[T], managerFactoryParameters: ManagerFactoryParameters): KeyManagerFactorySetup = {
    providing[T](Some(managerFactoryParameters))(ClassTag(clazz))
  }

  def delegatingTo(keyManagerFactory: KeyManagerFactory): KeyManagerFactorySetup =
    KeyManagerFactorySetup(DelegatingKeyManagerFactoryProvider, Some(DelegatingKeyManagerFactoryParameters(keyManagerFactory)))
}

case class TrustManagerFactorySetup(provider: Provider, trustManagerFactoryParameters: Option[ManagerFactoryParameters])
  extends CryptoServiceProviderSetup

object TrustManagerFactorySetup {
  /**
   * Java API
   */
  def create(provider: Provider): TrustManagerFactorySetup = TrustManagerFactorySetup(provider, None)

  /**
   * Java API
   */
  def create(trustManagerFactoryProvider: Provider, trustManagerFactoryParameters: ManagerFactoryParameters): TrustManagerFactorySetup =
    TrustManagerFactorySetup(trustManagerFactoryProvider, Some(trustManagerFactoryParameters))

  def providing[T <: TrustManagerFactorySpi](managerFactoryParameters: Option[ManagerFactoryParameters] = None)(implicit tag: ClassTag[T]): TrustManagerFactorySetup = {
    CryptoServiceProviderSetup.validate(tag)

    val provider = new Provider(s"$tag-provider", 1.0d, s"TrustManagerFactory providing $tag") { outer ⇒
      AccessController.doPrivileged(new PrivilegedAction[Unit] {
        override def run(): Unit = {
          putService(new Provider.Service(
            outer, "TrustManagerFactory", TrustManagerFactory.getDefaultAlgorithm, tag.runtimeClass.getCanonicalName, emptyList(), emptyMap()
          ))
        }
      })
    }
    TrustManagerFactorySetup(provider, managerFactoryParameters)
  }

  /**
   * Java api
   */
  def createProviding[T <: TrustManagerFactorySpi](clazz: Class[T]): TrustManagerFactorySetup = {
    providing[T]()(ClassTag(clazz))
  }

  /**
   * Java api
   */
  def createProviding[T <: TrustManagerFactorySpi](clazz: Class[T], managerFactoryParameters: ManagerFactoryParameters): TrustManagerFactorySetup = {
    providing[T](Some(managerFactoryParameters))(ClassTag(clazz))
  }

  def delegatingTo(trustManagerFactory: TrustManagerFactory): TrustManagerFactorySetup =
    TrustManagerFactorySetup(DelegatingTrustManagerFactoryProvider, Some(DelegatingTrustManagerFactoryParameters(trustManagerFactory)))
}
