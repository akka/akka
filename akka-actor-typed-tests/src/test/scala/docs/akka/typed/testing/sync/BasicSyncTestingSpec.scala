package docs.akka.typed.testing.sync

//#imports
import akka.actor.typed._
import akka.actor.typed.scaladsl._
import akka.typed.testkit._
import akka.typed.testkit.Effect._
//#imports
import org.scalatest.{ Matchers, WordSpec }

object BasicSyncTestingSpec {
  //#child
  val childActor = Actor.immutable[String] { (_, _) ⇒
    Actor.same[String]
  }
  //#child

  //#under-test
  sealed trait Cmd
  case class DoAnEffect(cmd: String) extends Cmd
  case class SayHello(who: ActorRef[String]) extends Cmd

  val myBehaviour = Actor.immutablePartial[Cmd] {
    case (ctx, DoAnEffect("create child")) ⇒
      ctx.spawn(childActor, "child")
      Actor.same
    case (ctx, DoAnEffect("nameless child")) ⇒
      ctx.spawnAnonymous(childActor)
      Actor.same
    case (ctx, DoAnEffect("say hello to child")) ⇒
      val child: ActorRef[String] = ctx.spawn(childActor, "child")
      child ! "hello"
      Actor.same
    case (ctx, DoAnEffect("say hello to nameless child")) ⇒
      val child: ActorRef[String] = ctx.spawnAnonymous(childActor)
      child ! "hello stranger"
      Actor.same
    case (_, SayHello(who)) ⇒
      who ! "hello"
      Actor.same
    //#under-test
  }

}

class BasicSyncTestingSpec extends WordSpec with Matchers {

  import BasicSyncTestingSpec._

  "Typed actor synchronous testing" must {

    "record spawning" in {
      //#test-child
      val testKit = BehaviorTestkit(myBehaviour)
      testKit.run(DoAnEffect("create child"))
      testKit.expectEffect(Spawned(childActor, "child"))
      //#test-child
    }

    "record spawning anonymous" in {
      //#test-anonymous-child
      val testKit = BehaviorTestkit(myBehaviour)
      testKit.run(DoAnEffect("nameless child"))
      testKit.expectEffect(SpawnedAnonymous(childActor))
      //#test-anonymous-child
    }

    "record message sends" in {
      //#test-message
      val testKit = BehaviorTestkit(myBehaviour)
      val inbox = TestInbox[String]()
      testKit.run(SayHello(inbox.ref))
      inbox.expectMsg("hello")
      //#test-message
    }

    "send a message to a spawned child" in {
      //#test-child-message
      val testKit = BehaviorTestkit(myBehaviour)
      testKit.run(DoAnEffect("say hello to child"))
      val childInbox = testKit.childInbox[String]("child")
      childInbox.expectMsg("hello")
      //#test-child-message
    }

    "send a message to an anonymous spawned child" in {
      //#test-child-message-anonymous
      val testKit = BehaviorTestkit(myBehaviour)
      testKit.run(DoAnEffect("say hello to nameless child"))
      // Anonymous actors are created as: $a $b etc
      val childInbox = testKit.childInbox[String]("$a")
      childInbox.expectMsg("hello stranger")
      //#test-child-message-anonymous
    }
  }
}
