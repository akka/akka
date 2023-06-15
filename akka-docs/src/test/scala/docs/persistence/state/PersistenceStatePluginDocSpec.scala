/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.persistence.state

import akka.Done
import akka.actor.{ ActorSystem, ExtendedActorSystem }

import akka.persistence.state.scaladsl.DurableStateStore
import akka.persistence.state.{ DurableStateStoreProvider, DurableStateStoreRegistry }
import akka.testkit.TestKit
import com.typesafe.config._
import docs.persistence
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Future
import scala.concurrent.duration._

//#plugin-imports
import akka.persistence._
import akka.persistence.state.scaladsl.DurableStateUpdateStore
import akka.persistence.state.scaladsl.GetObjectResult
//#plugin-imports

class PersistenceStatePluginDocSpec extends AnyWordSpec {

  val providerConfigJava =
    """
    //#plugin-config-java
    # Path to the state store plugin to be used
    akka.persistence.state.plugin = "my-java-state-store"

    # My custom state store plugin
    my-java-state-store {
      # Class name of the plugin.
      class = "docs.persistence.state.MyJavaStateStoreProvider"
    }
    //#plugin-config-java
  """

  val providerConfig =
    """
    //#plugin-config-scala
    # Path to the state store plugin to be used
    akka.persistence.state.plugin = "my-state-store"

    # My custom state store plugin
    my-state-store {
      # Class name of the plugin.
      class = "docs.persistence.state.MyStateStoreProvider"
    }
    //#plugin-config-scala
      """

  "it should work for scala" in {
    val system = ActorSystem("PersistenceStatePluginDocSpec", ConfigFactory.parseString(providerConfig))
    try {
      Persistence(system)
      DurableStateStoreRegistry(system).durableStateStoreFor[DurableStateUpdateStore[Any]]("my-state-store")
    } finally {
      TestKit.shutdownActorSystem(system, 10.seconds, false)
    }
  }

  "it should work for java" in {
    val system = ActorSystem("PersistenceStatePluginDocSpec", ConfigFactory.parseString(providerConfigJava))
    try {
      Persistence(system)
      DurableStateStoreRegistry(system).durableStateStoreFor[DurableStateUpdateStore[Any]]("my-java-state-store")
    } finally {
      TestKit.shutdownActorSystem(system, 10.seconds, false)
    }
  }
}
