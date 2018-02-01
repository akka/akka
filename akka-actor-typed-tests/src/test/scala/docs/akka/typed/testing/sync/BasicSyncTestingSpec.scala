package docs.akka.typed.testing.sync

//#imports
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.testkit.typed._
import akka.testkit.typed.Effect._
//#imports
import org.scalatest.{ Matchers, WordSpec }

object BasicSyncTestingSpec {
  //#child
  val childActor = Behaviors.immutable[String] { (_, _) ⇒
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

  val myBehavior = Behaviors.immutablePartial[Cmd] {
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
    //#under-test
  }

}

class BasicSyncTestingSpec extends WordSpec with Matchers {

  import BasicSyncTestingSpec._

  "Typed actor synchronous testing" must {

    "record spawning" in {
      //#test-child
      val testKit = BehaviorTestkit(myBehavior)
      testKit.run(CreateChild("child"))
      testKit.expectEffect(Spawned(childActor, "child"))
      //#test-child
    }

    "record spawning anonymous" in {
      //#test-anonymous-child
      val testKit = BehaviorTestkit(myBehavior)
      testKit.run(CreateAnonymousChild)
      testKit.expectEffect(SpawnedAnonymous(childActor))
      //#test-anonymous-child
    }

    "record message sends" in {
      //#test-message
      val testKit = BehaviorTestkit(myBehavior)
      val inbox = TestInbox[String]()
      testKit.run(SayHello(inbox.ref))
      inbox.expectMsg("hello")
      //#test-message
    }

    "send a message to a spawned child" in {
      //#test-child-message
      val testKit = BehaviorTestkit(myBehavior)
      testKit.run(SayHelloToChild("child"))
      val childInbox = testKit.childInbox[String]("child")
      childInbox.expectMsg("hello")
      //#test-child-message
    }

    "send a message to an anonymous spawned child" in {
      //#test-child-message-anonymous
      val testKit = BehaviorTestkit(myBehavior)
      testKit.run(SayHelloToAnonymousChild)
      // Anonymous actors are created as: $a $b etc
      val childInbox = testKit.childInbox[String](s"$$a")
      childInbox.expectMsg("hello stranger")
      //#test-child-message-anonymous
    }
  }
}
