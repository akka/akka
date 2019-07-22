/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.util.Timeout
import org.scalatest.Matchers
import org.scalatest.WordSpec
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

class ActorRefResolverSpec extends WordSpec with ScalaFutures with Matchers {
  "ActorRefResolver" should {
    "use address from ref if there" in {
      val system1 = ActorSystem(Behaviors.empty[String], "1")
      val system2 = ActorSystem(Behaviors.empty[String], "2")
      try {
        val ref = system1.systemActorOf(Behaviors.empty, "ref1")(Timeout(1.second)).futureValue
        val serialized = ActorRefResolver(system2).toSerializationFormat(ref)
        serialized should startWith("akka://1/")
      } finally {
        system1.terminate()
        system2.terminate()
      }
    }
  }

}
