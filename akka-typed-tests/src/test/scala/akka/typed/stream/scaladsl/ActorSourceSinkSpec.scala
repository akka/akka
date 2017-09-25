/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed.stream.scaladsl

import akka.actor
import akka.event.Logging
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.testkit.AkkaSpec
import akka.typed.TypedSpec.guardian
import akka.typed.internal.adapter.ActorSystemAdapter
import akka.typed.testkit.TestKitSettings
import akka.typed.testkit.scaladsl.TestProbe
import akka.typed.{ ActorRef, ActorSystem, TypedSpec }
import org.scalatest.{ Matchers, WordSpec }

class ActorSourceSinkSpec extends WordSpec with Matchers {

  implicit val adaptedSystem: ActorSystem[TypedSpec.Command] =
    ActorSystem.adapter(Logging.simpleName(getClass), guardian(), config = Some(AkkaSpec.testConf))
  implicit val settings = TestKitSettings(adaptedSystem)
  implicit val untypedSystem: actor.ActorSystem = ActorSystemAdapter.toUntyped(adaptedSystem)

  implicit val mat = ActorMaterializer()(untypedSystem)

  "ActorSource" should {
    "work with Akka Typed" in {
      val p = TestProbe[String]()

      val in: ActorRef[String] =
        ActorSource.actorRef[String](10, OverflowStrategy.dropBuffer)
          .map(_ + "!")
          .to(ActorSink.actorRef(p.ref, "DONE", ex ⇒ "FAILED: " + ex.getMessage))
          .run()

      val msg = "hello"

      in ! msg
      p.expectMsg(msg)
    }
  }

}
