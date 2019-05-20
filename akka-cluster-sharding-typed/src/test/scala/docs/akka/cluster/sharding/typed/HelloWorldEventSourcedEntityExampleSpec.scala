/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.cluster.sharding.typed

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.cluster.sharding.typed.scaladsl.Entity
import akka.cluster.typed.Cluster
import akka.cluster.typed.Join
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

object HelloWorldEventSourcedEntityExampleSpec {
  val config = ConfigFactory.parseString("""
      akka.actor.provider = cluster

      akka.remote.classic.netty.tcp.port = 0
      akka.remote.artery.canonical.port = 0
      akka.remote.artery.canonical.hostname = 127.0.0.1

      akka.persistence.journal.plugin = "akka.persistence.journal.inmem"
    """)
}

class HelloWorldEventSourcedEntityExampleSpec
    extends ScalaTestWithActorTestKit(HelloWorldEventSourcedEntityExampleSpec.config)
    with WordSpecLike {
  import HelloWorldPersistentEntityExample.HelloWorld
  import HelloWorldPersistentEntityExample.HelloWorld._

  val sharding = ClusterSharding(system)

  override def beforeAll(): Unit = {
    super.beforeAll()
    Cluster(system).manager ! Join(Cluster(system).selfMember.address)

    sharding.init(Entity(HelloWorld.entityTypeKey, ctx => HelloWorld.persistentEntity(ctx.entityId)))
  }

  "HelloWorld example" must {

    "sayHello" in {
      val probe = createTestProbe[Greeting]()
      val ref = ClusterSharding(system).entityRefFor(HelloWorld.entityTypeKey, "1")
      ref ! Greet("Alice")(probe.ref)
      probe.expectMessage(Greeting("Alice", 1))
      ref ! Greet("Bob")(probe.ref)
      probe.expectMessage(Greeting("Bob", 2))
    }

  }
}
