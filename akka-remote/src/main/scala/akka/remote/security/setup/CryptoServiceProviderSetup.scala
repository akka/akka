package akka.remote.security.setup

import java.security.Provider
import javax.net.ssl.ManagerFactoryParameters

import akka.actor.setup.Setup

// TODO should be one Setup per Provider, with a common trait
case class CryptoServiceProviderSetup(
  provider:                      Provider,
  keyManagerFactoryParameters:   Option[KeyManagerFactoryParameters],
  trustManagerFactoryParameters: Option[TrustManagerFactoryParameters]) extends Setup

object CryptoServiceProviderSetup {
  class Builder(provider: Provider) {
    var keyManagerFactoryParameters: Option[KeyManagerFactoryParameters] = None
    var trustManagerFactoryParameters: Option[TrustManagerFactoryParameters] = None
    def withTrustManagerFactoryParameters(trustManagerFactoryParameters: TrustManagerFactoryParameters): Builder = {
      this.trustManagerFactoryParameters = Some(trustManagerFactoryParameters)
      this
    }
    def withKeyManagerFactoryParameters(keyManagerFactoryParameters: KeyManagerFactoryParameters): Builder = {
      this.keyManagerFactoryParameters = Some(keyManagerFactoryParameters)
      this
    }
    def build(): CryptoServiceProviderSetup = CryptoServiceProviderSetup(provider, keyManagerFactoryParameters, trustManagerFactoryParameters)
  }

  def builder(provider: Provider): Builder = new Builder(provider)
}

trait KeyManagerFactoryParameters extends ManagerFactoryParameters
trait TrustManagerFactoryParameters extends ManagerFactoryParameters