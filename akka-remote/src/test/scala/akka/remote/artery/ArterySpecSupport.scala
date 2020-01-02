/*
 * Copyright (C) 2016-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import com.typesafe.config.{ Config, ConfigFactory }

object ArterySpecSupport {
  // same for all artery enabled remoting tests
  private val staticArteryRemotingConfig = ConfigFactory.parseString(s"""
    akka {
      actor {
        provider = remote
      }
      remote.warn-about-direct-use = off
      remote.artery {
        enabled = on
        canonical {
          hostname = localhost
          port = 0
        }
      }
    }""")

  /**
   * Artery enabled, flight recorder enabled, dynamic selection of port on localhost.
   */
  def defaultConfig: Config =
    staticArteryRemotingConfig.withFallback(tlsConfig) // TLS only used if transport=tls-tcp

  // set the test key-store and trust-store properties
  // TLS only used if transport=tls-tcp, which can be set from specific tests or
  // System properties (e.g. jenkins job)
  lazy val tlsConfig: Config = {
    val trustStore = getClass.getClassLoader.getResource("truststore").getPath
    val keyStore = getClass.getClassLoader.getResource("keystore").getPath

    ConfigFactory.parseString(s"""
      akka.remote.artery.ssl.config-ssl-engine {
        key-store = "$keyStore"
        trust-store = "$trustStore"
      }
    """)
  }

}
