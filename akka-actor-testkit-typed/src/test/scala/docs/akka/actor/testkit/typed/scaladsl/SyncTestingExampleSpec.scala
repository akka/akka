/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

//#imports
import akka.actor.testkit.typed.CapturedLogEvent
import akka.actor.testkit.typed.Effect._
import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.event.Logging
//#imports
import org.scalatest.Matchers
import org.scalatest.WordSpec

object SyncTestingExampleSpec {
  //#child
  val childActor = Behaviors.receiveMessage[String] { _ ⇒
    Behaviors.same[String]
  }
  //#child

  //#under-test
  sealed trait Cmd
  case object CreateAnonymousChild extends Cmd
  case class CreateChild(childName: String) extends Cmd
  case class SayHelloToChild(childName: String) extends Cmd
  case object SayHelloToAnonymousChild extends Cmd
  case class SayHello(who: ActorRef[String]) extends Cmd
  case class LogAndSayHello(who: ActorRef[String]) extends Cmd

  val myBehavior = Behaviors.receivePartial[Cmd] {
    case (context, CreateChild(name)) ⇒
      context.spawn(childActor, name)
      Behaviors.same
    case (context, CreateAnonymousChild) ⇒
      context.spawnAnonymous(childActor)
      Behaviors.same
    case (context, SayHelloToChild(childName)) ⇒
      val child: ActorRef[String] = context.spawn(childActor, childName)
      child ! "hello"
      Behaviors.same
    case (context, SayHelloToAnonymousChild) ⇒
      val child: ActorRef[String] = context.spawnAnonymous(childActor)
      child ! "hello stranger"
      Behaviors.same
    case (_, SayHello(who)) ⇒
      who ! "hello"
      Behaviors.same
    case (context, LogAndSayHello(who)) ⇒
      context.log.info("Saying hello to {}", who.path.name)
      who ! "hello"
      Behaviors.same
  }
  //#under-test

}

class SyncTestingExampleSpec extends WordSpec with Matchers {

  import SyncTestingExampleSpec._

  "Typed actor synchronous testing" must {

    "record spawning" in {
      //#test-child
      val testKit = BehaviorTestKit(myBehavior)
      testKit.run(CreateChild("child"))
      testKit.expectEffect(Spawned(childActor, "child"))
      //#test-child
    }

    "record spawning anonymous" in {
      //#test-anonymous-child
      val testKit = BehaviorTestKit(myBehavior)
      testKit.run(CreateAnonymousChild)
      testKit.expectEffect(SpawnedAnonymous(childActor))
      //#test-anonymous-child
    }

    "record message sends" in {
      //#test-message
      val testKit = BehaviorTestKit(myBehavior)
      val inbox = TestInbox[String]()
      testKit.run(SayHello(inbox.ref))
      inbox.expectMessage("hello")
      //#test-message
    }

    "send a message to a spawned child" in {
      //#test-child-message
      val testKit = BehaviorTestKit(myBehavior)
      testKit.run(SayHelloToChild("child"))
      val childInbox = testKit.childInbox[String]("child")
      childInbox.expectMessage("hello")
      //#test-child-message
    }

    "send a message to an anonymous spawned child" in {
      //#test-child-message-anonymous
      val testKit = BehaviorTestKit(myBehavior)
      testKit.run(SayHelloToAnonymousChild)
      val child = testKit.expectEffectType[SpawnedAnonymous[String]]

      val childInbox = testKit.childInbox(child.ref)
      childInbox.expectMessage("hello stranger")
      //#test-child-message-anonymous
    }

    "log a message to the logger" in {
      //#test-check-logging
      val testKit = BehaviorTestKit(myBehavior)
      val inbox = TestInbox[String]("Inboxer")
      testKit.run(LogAndSayHello(inbox.ref))

      testKit.logEntries() shouldBe Seq(CapturedLogEvent(Logging.InfoLevel, "Saying hello to Inboxer"))
      //#test-check-logging
    }
  }
}
