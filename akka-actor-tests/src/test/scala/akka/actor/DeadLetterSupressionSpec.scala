/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.event.Logging
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import akka.testkit.TestProbe

import scala.concurrent.duration._

object DeadLetterSupressionSpec {

  case object NormalMsg

  case object SuppressedMsg extends DeadLetterSuppression

}

class DeadLetterSupressionSpec extends AkkaSpec with ImplicitSender {
  import DeadLetterSupressionSpec._

  val deadActor = system.actorOf(TestActors.echoActorProps)
  watch(deadActor)
  deadActor ! PoisonPill
  expectTerminated(deadActor)

  s"must suppress message from default dead-letters logging (sent to dead: ${Logging.simpleName(deadActor)})" in {
    val deadListener = TestProbe()
    system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

    val suppressedListener = TestProbe()
    system.eventStream.subscribe(suppressedListener.ref, classOf[SuppressedDeadLetter])

    val allListener = TestProbe()
    system.eventStream.subscribe(allListener.ref, classOf[AllDeadLetters])

    deadActor ! SuppressedMsg
    deadActor ! NormalMsg

    deadListener.expectMsg(DeadLetter(NormalMsg, testActor, deadActor))
    deadListener.expectNoMessage(200.millis)

    suppressedListener.expectMsg(SuppressedDeadLetter(SuppressedMsg, testActor, system.deadLetters))
    suppressedListener.expectNoMessage(200.millis)

    allListener.expectMsg(SuppressedDeadLetter(SuppressedMsg, testActor, system.deadLetters))
    allListener.expectMsg(DeadLetter(NormalMsg, testActor, deadActor))
    allListener.expectNoMessage(200.millis)
  }

  s"must suppress message from default dead-letters logging (sent to dead: ${Logging.simpleName(system.deadLetters)})" in {
    val deadListener = TestProbe()
    system.eventStream.subscribe(deadListener.ref, classOf[DeadLetter])

    val suppressedListener = TestProbe()
    system.eventStream.subscribe(suppressedListener.ref, classOf[SuppressedDeadLetter])

    val allListener = TestProbe()
    system.eventStream.subscribe(allListener.ref, classOf[AllDeadLetters])

    system.deadLetters ! SuppressedMsg
    system.deadLetters ! NormalMsg

    deadListener.expectMsg(200.millis, DeadLetter(NormalMsg, testActor, system.deadLetters))

    suppressedListener.expectMsg(200.millis, SuppressedDeadLetter(SuppressedMsg, testActor, system.deadLetters))

    allListener.expectMsg(200.millis, SuppressedDeadLetter(SuppressedMsg, testActor, system.deadLetters))
    allListener.expectMsg(200.millis, DeadLetter(NormalMsg, testActor, system.deadLetters))

    Thread.sleep(200)
    deadListener.expectNoMessage(Duration.Zero)
    suppressedListener.expectNoMessage(Duration.Zero)
    allListener.expectNoMessage(Duration.Zero)
  }
}
