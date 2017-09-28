package akka.remote.security.setup

import java.security.Provider
import javax.net.ssl.ManagerFactoryParameters

import akka.actor.setup.Setup

abstract class CryptoServiceProviderSetup extends Setup {
  def provider: Provider
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
}