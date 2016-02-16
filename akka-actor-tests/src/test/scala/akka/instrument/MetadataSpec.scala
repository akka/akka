/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.instrument

import akka.actor.{ Props, Actor, ActorSystem }
import akka.testkit.TestKit
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpecLike }

class MetadataSpec
  extends TestKit(ActorSystem("MetadataSpec"))
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  import MetadataSpec._

  val echo = system.actorOf(Props(new EchoActor), "echo")

  override def afterAll(): Unit = shutdown()

  "DefaultActorMetadata" should {
    "attach, extract, and remove metadata" in {
      DefaultActorMetadata.extractFrom(echo) shouldBe null
      DefaultActorMetadata.attachTo(echo, "abc")
      DefaultActorMetadata.extractFrom(echo) shouldBe "abc"
      DefaultActorMetadata.removeFrom(echo) shouldBe "abc"
      DefaultActorMetadata.extractFrom(echo) shouldBe null
    }

    "extract actor class" in {
      DefaultActorMetadata.actorClass(echo) shouldBe classOf[EchoActor]
    }
  }

  "Ensemble.MultiActorMetadata" should {
    "attach, extract, and remove metadata" in {
      val metadata1 = new Ensemble.MultiActorMetadata(0, 2)
      val metadata2 = new Ensemble.MultiActorMetadata(1, 2)

      DefaultActorMetadata.extractFrom(echo) shouldBe null
      metadata1.extractFrom(echo) shouldBe null
      metadata2.extractFrom(echo) shouldBe null

      metadata1.attachTo(echo, "abc")
      metadata1.extractFrom(echo) shouldBe "abc"
      metadata2.extractFrom(echo) shouldBe null

      metadata2.attachTo(echo, "xyz")
      metadata1.extractFrom(echo) shouldBe "abc"
      metadata2.extractFrom(echo) shouldBe "xyz"

      metadata1.removeFrom(echo) shouldBe "abc"
      metadata1.extractFrom(echo) shouldBe null
      metadata2.extractFrom(echo) shouldBe "xyz"

      metadata2.removeFrom(echo) shouldBe "xyz"
      metadata1.extractFrom(echo) shouldBe null
      metadata2.extractFrom(echo) shouldBe null
    }

    "extract actor class" in {
      val metadata1 = new Ensemble.MultiActorMetadata(0, 2)
      val metadata2 = new Ensemble.MultiActorMetadata(1, 2)

      metadata1.actorClass(echo) shouldBe classOf[EchoActor]
      metadata2.actorClass(echo) shouldBe classOf[EchoActor]
    }
  }
}

object MetadataSpec {
  class EchoActor extends Actor {
    def receive = { case m ⇒ sender ! m }
  }
}
