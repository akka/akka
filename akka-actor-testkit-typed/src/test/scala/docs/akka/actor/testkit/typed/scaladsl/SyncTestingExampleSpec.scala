/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.actor.testkit.typed.scaladsl

//#imports
import akka.actor.testkit.typed.Effect._
import akka.actor.testkit.typed.scaladsl.BehaviorTestKit
import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.actor.typed._
import akka.actor.typed.scaladsl._
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

  val myBehavior = Behaviors.receivePartial[Cmd] {
    case (ctx, CreateChild(name)) ⇒
      ctx.spawn(childActor, name)
      Behaviors.same
    case (ctx, CreateAnonymousChild) ⇒
      ctx.spawnAnonymous(childActor)
      Behaviors.same
    case (ctx, SayHelloToChild(childName)) ⇒
      val child: ActorRef[String] = ctx.spawn(childActor, childName)
      child ! "hello"
      Behaviors.same
    case (ctx, SayHelloToAnonymousChild) ⇒
      val child: ActorRef[String] = ctx.spawnAnonymous(childActor)
      child ! "hello stranger"
      Behaviors.same
    case (_, SayHello(who)) ⇒
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
      // Anonymous actors are created as: $a $b etc
      val childInbox = testKit.childInbox[String](s"$$a")
      childInbox.expectMessage("hello stranger")
      //#test-child-message-anonymous
    }
  }
}
