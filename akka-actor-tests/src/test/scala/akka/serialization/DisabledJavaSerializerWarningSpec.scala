/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.serialization

import scala.concurrent.duration._
import akka.testkit._
import akka.testkit.TestEvent._

object DisabledJavaSerializerWarningSpec {
  final case class Msg(s: String)
}

class DisabledJavaSerializerWarningSpec extends AkkaSpec(
  """
  akka.actor {
    allow-java-serialization = off
    serialize-messages = on
    # this is by default on, but tests are running with off
    warn-about-java-serializer-usage = on
  }
  """) with ImplicitSender {

  import DisabledJavaSerializerWarningSpec._

  "DisabledJavaSerializer warning" must {

    "be logged for suspicious messages" in {
      EventFilter.warning(start = "Outgoing message attempted to use Java Serialization", occurrences = 1).intercept {
        val echo = system.actorOf(TestActors.echoActorProps)
        echo ! List("a")
        expectNoMsg(300.millis)
      }

    }

    "be skipped for well known local messages" in {
      EventFilter.warning(start = "Outgoing message attempted to use Java Serialization", occurrences = 0).intercept {
        val echo = system.actorOf(TestActors.echoActorProps)
        echo ! Msg("a") // Msg is in the akka package
        expectMsg(Msg("a"))
      }
    }

  }
}
