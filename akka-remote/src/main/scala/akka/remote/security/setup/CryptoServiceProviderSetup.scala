/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.security.setup

import java.lang.reflect.Modifier
import java.security.{ AccessController, PrivilegedAction, Provider }
import java.util.Collections.{ emptyList, emptyMap }

import javax.net.ssl._
import akka.actor.setup.Setup
import akka.annotation.InternalApi
import akka.remote.security.provider._

import scala.reflect.ClassTag

/**
 * INTERNAL API
 */
@InternalApi
private[setup] object Validate {
  def apply[T](tag: ClassTag[T]): Unit = {
    val clazz = tag.runtimeClass
    require(Modifier.isPublic(clazz.getModifiers), s"$tag must be a public class")
    require(!clazz.isLocalClass, s"$tag must not be a local class")
    require(!clazz.isMemberClass, s"$tag must must not be a member class")
  }
}

/**
 * Allows programmatic control of [[javax.net.ssl.KeyManagerFactory]] used by Akka Remote SSL.
 *
 * @param provider Provider that will be used to instantiate a KeyManagerFactory for the default algorithm.
 * @param keyManagerFactoryParameters If supplied will be used to initialise the KeyManagerFactory instead of any key store specified in Config.
 */
case class KeyManagerFactorySetup(provider: Provider, keyManagerFactoryParameters: Option[ManagerFactoryParameters])
  extends Setup

/**
 * Factory for [[akka.remote.security.setup.KeyManagerFactorySetup]] instances.
 */
object KeyManagerFactorySetup {
  /**
   * Java API factory method to create a KeyManagerFactorySetup.
   *
   * @param provider Provider that will be used to instantiate a [[javax.net.ssl.KeyManagerFactory]] for the default algorithm.
   * Will be initialised using the key store specified in Config.
   */
  def create(provider: Provider): KeyManagerFactorySetup = KeyManagerFactorySetup(provider, None)

  /**
   * Java API factory method to create a KeyManagerFactorySetup.
   *
   * @param provider Provider that will be used to instantiate a [[javax.net.ssl.KeyManagerFactory]] for the default algorithm.
   * @param managerFactoryParameters Will be used to initialise the [[javax.net.ssl.KeyManagerFactory]] instead of Config settings.
   */
  def create(provider: Provider, managerFactoryParameters: ManagerFactoryParameters): KeyManagerFactorySetup =
    KeyManagerFactorySetup(provider, Some(managerFactoryParameters))

  /**
   * Factory to create a KeyManagerFactorySetup to instantiate a [[javax.net.ssl.KeyManagerFactory]] based on the given KeyManagerFactorySpi for the default
   * algorithm. This will be initialised using the key store specified in Config.
   *
   * @tparam T Class on which to base a KeyManagerFactory. Must be public and top level.
   */
  def providing[T <: KeyManagerFactorySpi](implicit tag: ClassTag[T]): KeyManagerFactorySetup = {
    providing[T](None)(tag)
  }

  /**
   * Factory to create a KeyManagerFactorySetup to instantiate a [[javax.net.ssl.KeyManagerFactory]] based on the given KeyManagerFactorySpi for the default algorithm.
   *
   * @tparam T Class on which to base a KeyManagerFactory. Must be public and top level.
   * @param managerFactoryParameters Used to initialise the KeyManagerFactory instead of any key store specified in Config.
   */
  def providing[T <: KeyManagerFactorySpi](managerFactoryParameters: ManagerFactoryParameters)(implicit tag: ClassTag[T]): KeyManagerFactorySetup = {
    providing[T](Some(managerFactoryParameters))(tag)
  }

  private def providing[T <: KeyManagerFactorySpi](managerFactoryParameters: Option[ManagerFactoryParameters])(implicit tag: ClassTag[T]): KeyManagerFactorySetup = {
    Validate(tag)

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
   * Java API factory to create a KeyManagerFactorySetup to instantiate a [[javax.net.ssl.KeyManagerFactory]] based on the given KeyManagerFactorySpi for the
   * default algorithm. This will be initialised using the key store specified in Config.
   *
   * @param clazz Class on which to base a KeyManagerFactory. Must be public and top level.
   */
  def createProviding[T <: KeyManagerFactorySpi](clazz: Class[T]): KeyManagerFactorySetup = {
    providing[T](None)(ClassTag(clazz))
  }

  /**
   * Java API factory to create a KeyManagerFactorySetup to instantiate a [[javax.net.ssl.KeyManagerFactory]] based on the given KeyManagerFactorySpi
   * for the default algorithm.
   *
   * @param clazz Class on which to base a KeyManagerFactory. Must be public and top level.
   * @param managerFactoryParameters Used to initialise the KeyManagerFactory instead of any key store specified in Config.
   */
  def createProviding[T <: KeyManagerFactorySpi](clazz: Class[T], managerFactoryParameters: ManagerFactoryParameters): KeyManagerFactorySetup = {
    providing[T](Some(managerFactoryParameters))(ClassTag(clazz))
  }

  /**
   * Factory to create a KeyManagerFactorySetup to instantiate a [[javax.net.ssl.KeyManagerFactory]] that delegates to the given KeyManagerFactory for the
   * default algorithm. The delegate is assumed to be already initialised.
   *
   * @param keyManagerFactory Initialised delegate KeyManagerFactory.
   */
  def delegatingTo(keyManagerFactory: KeyManagerFactory): KeyManagerFactorySetup =
    KeyManagerFactorySetup(DelegatingKeyManagerFactoryProvider, Some(DelegatingKeyManagerFactoryParameters(keyManagerFactory)))
}

/**
 * Allows programmatic control of [[javax.net.ssl.TrustManagerFactory]] used by Akka Remote SSL.
 *
 * @param provider Provider that will be used to instantiate a TrustManagerFactory for the default algorithm.
 * @param trustManagerFactoryParameters If supplied will be used to initialise the TrustManagerFactory instead of any trust store specified in Config.
 */
case class TrustManagerFactorySetup(provider: Provider, trustManagerFactoryParameters: Option[ManagerFactoryParameters])
  extends Setup

/**
 * Factory for [[akka.remote.security.setup.TrustManagerFactorySetup]] instances.
 */
object TrustManagerFactorySetup {
  /**
   * Java API factory method to create a TrustManagerFactorySetup.
   *
   * @param provider Provider that will be used to instantiate a [[javax.net.ssl.TrustManagerFactory]] for the default algorithm.
   * Will be initialised using the trust store specified in Config.
   */
  def create(provider: Provider): TrustManagerFactorySetup = TrustManagerFactorySetup(provider, None)

  /**
   * Java API factory method to create a TrustManagerFactorySetup.
   *
   * @param provider Provider that will be used to instantiate a [[javax.net.ssl.TrustManagerFactory]] for the default algorithm.
   * @param managerFactoryParameters Will be used to initialise the [[javax.net.ssl.TrustManagerFactory]] instead of Config settings.
   */
  def create(provider: Provider, managerFactoryParameters: ManagerFactoryParameters): TrustManagerFactorySetup =
    TrustManagerFactorySetup(provider, Some(managerFactoryParameters))

  /**
   * Factory to create a TrustManagerFactorySetup to instantiate a [[javax.net.ssl.TrustManagerFactory]] based on the given TrustManagerFactorySpi for the default
   * algorithm. This will be initialised using the trust store specified in Config.
   *
   * @tparam T Class on which to base a TrustManagerFactory. Must be public and top level.
   */
  def providing[T <: TrustManagerFactorySpi](implicit tag: ClassTag[T]): TrustManagerFactorySetup = {
    providing[T](None)(tag)
  }

  /**
   * Factory to create a TrustManagerFactorySetup to instantiate a [[javax.net.ssl.TrustManagerFactory]] based on the given TrustManagerFactorySpi for the default algorithm.
   *
   * @tparam T Class on which to base a TrustManagerFactory. Must be public and top level.
   * @param managerFactoryParameters Used to initialise the TrustManagerFactory instead of any trust store specified in Config.
   */
  def providing[T <: TrustManagerFactorySpi](managerFactoryParameters: ManagerFactoryParameters)(implicit tag: ClassTag[T]): TrustManagerFactorySetup = {
    providing[T](Some(managerFactoryParameters))(tag)
  }

  private def providing[T <: TrustManagerFactorySpi](managerFactoryParameters: Option[ManagerFactoryParameters])(implicit tag: ClassTag[T]): TrustManagerFactorySetup = {
    Validate(tag)

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
   * Java API factory to create a TrustManagerFactorySetup to instantiate a [[javax.net.ssl.TrustManagerFactory]] based on the given TrustManagerFactorySpi for the
   * default algorithm. This will be initialised using the trust store specified in Config.
   *
   * @param clazz Class on which to base a TrustManagerFactory. Must be public and top level.
   */
  def createProviding[T <: TrustManagerFactorySpi](clazz: Class[T]): TrustManagerFactorySetup = {
    providing[T](None)(ClassTag(clazz))
  }

  /**
   * Java API factory to create a TrustManagerFactorySetup to instantiate a [[javax.net.ssl.TrustManagerFactory]] based on the given TrustManagerFactorySpi
   * for the default algorithm.
   *
   * @param clazz Class on which to base a TrustManagerFactory. Must be public and top level.
   * @param managerFactoryParameters Used to initialise the TrustManagerFactory instead of any trust store specified in Config.
   */
  def createProviding[T <: TrustManagerFactorySpi](clazz: Class[T], managerFactoryParameters: ManagerFactoryParameters): TrustManagerFactorySetup = {
    providing[T](Some(managerFactoryParameters))(ClassTag(clazz))
  }

  /**
   * Factory to create a TrustManagerFactorySetup to instantiate a [[javax.net.ssl.TrustManagerFactory]] that delegates to the given TrustManagerFactory for the
   * default algorithm. The delegate is assumed to be already initialised.
   *
   * @param trustManagerFactory Initialised delegate TrustManagerFactory.
   */
  def delegatingTo(trustManagerFactory: TrustManagerFactory): TrustManagerFactorySetup =
    TrustManagerFactorySetup(DelegatingTrustManagerFactoryProvider, Some(DelegatingTrustManagerFactoryParameters(trustManagerFactory)))
}
