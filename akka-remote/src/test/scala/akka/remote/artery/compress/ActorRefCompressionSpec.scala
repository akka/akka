/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.artery.compress

import akka.actor.{ ActorRefProvider, MinimalActorRef, ActorPath, ActorRef }
import akka.stream.scaladsl.Flow
import akka.testkit.AkkaSpec
import akka.remote.artery.compress.OutboundCompressionTable.NotCompressed

class ActorRefCompressionSpec extends AkkaSpec("akka.loglevel = INFO") {

  val compression = new OutboundCompressionTable(system)

  val alice: ActorRef = new MinimalActorRef {
    override def provider: ActorRefProvider = null
    override def path: ActorPath = ActorPath.fromString("akka://system@127.0.0.1:2121/user/example/alice")
  }
  val bob: ActorRef = new MinimalActorRef {
    override def provider: ActorRefProvider = null
    override def path: ActorPath = ActorPath.fromString("akka://system@127.0.0.1:2121/user/example/bob")
  }
  val carl: ActorRef = new MinimalActorRef {
    override def provider: ActorRefProvider = null
    override def path: ActorPath = ActorPath.fromString("akka://system@127.0.0.1:2121/user/example/carl")
  }

  def compressed(id: Int): CompressedActorRef = CompressedActorRef(id)

  "OutgoingCompressionTable" must {

    "always compress deadLetters" in {
      compression.compress(system.deadLetters) should ===(compressed(0))
    }

    "not compress if ActorRef not seen before" in {
      compression.compress(alice) should ===(NotCompressed)
    }

    "compress if ActorRef seen before and heavy-hitter" in {
      val id = 1
      compression.compress(alice) should ===(NotCompressed)
      compression.register(alice, id)
      compression.compress(alice) should ===(compressed(id))
    }
  }

}
