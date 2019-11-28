/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.util.UUID

import akka.remote.artery.ArterySpecSupport
import akka.remote.testkit.{ MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit.{ DefaultTimeout, ImplicitSender }
import com.typesafe.config.ConfigFactory
import org.scalatest.Suite

object RemotingMultiNodeSpec {

  def commonConfig =
    ConfigFactory.parseString(s"""
        akka.actor.warn-about-java-serializer-usage = off
        akka.remote.artery.advanced.flight-recorder {
          enabled=on
          destination=target/flight-recorder-${UUID.randomUUID().toString}.afr
        }
      """).withFallback(ArterySpecSupport.tlsConfig) // TLS only used if transport=tls-tcp

}

abstract class RemotingMultiNodeSpec(config: MultiNodeConfig)
    extends MultiNodeSpec(config)
    with Suite
    with STMultiNodeSpec
    with ImplicitSender
    with DefaultTimeout { self: MultiNodeSpec =>

}
