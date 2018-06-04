/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.Done
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, Props }
import akka.actor.testkit.typed.Effect
import akka.actor.testkit.typed.Effect._
import akka.actor.testkit.typed.scaladsl.BehaviorTestKitSpec.{ Child, Father }
import akka.actor.testkit.typed.scaladsl.BehaviorTestKitSpec.Father._
import org.scalatest.{ Matchers, WordSpec }

import scala.reflect.ClassTag

object BehaviorTestKitSpec {
  object Father {

    case class Reproduce(times: Int)

    sealed trait Command

    case class SpawnChildren(numberOfChildren: Int) extends Command
    case class SpawnChildrenWithProps(numberOfChildren: Int, props: Props) extends Command
    case class SpawnAnonymous(numberOfChildren: Int) extends Command
    case class SpawnAnonymousWithProps(numberOfChildren: Int, props: Props) extends Command
    case object SpawnAdapter extends Command
    case class SpawnAdapterWithName(name: String) extends Command
    case class CreateMessageAdapter[U](messageClass: Class[U], f: U ⇒ Command) extends Command
    case class SpawnAndWatchUnwatch(name: String) extends Command
    case class SpawnAndWatchWith(name: String) extends Command
    case class SpawnSession(replyTo: ActorRef[ActorRef[String]], sessionHandler: ActorRef[String]) extends Command
    case class KillSession(session: ActorRef[String], replyTo: ActorRef[Done]) extends Command

    val init: Behavior[Command] = Behaviors.receive[Command] { (ctx, msg) ⇒
      msg match {
        case SpawnChildren(numberOfChildren) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { i ⇒
            ctx.spawn(Child.initial, s"child$i")
          }
          Behaviors.same
        case SpawnChildrenWithProps(numberOfChildren, props) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { i ⇒
            ctx.spawn(Child.initial, s"child$i", props)
          }
          Behaviors.same
        case SpawnAnonymous(numberOfChildren) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { _ ⇒
            ctx.spawnAnonymous(Child.initial)
          }
          Behaviors.same
        case SpawnAnonymousWithProps(numberOfChildren, props) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { _ ⇒
            ctx.spawnAnonymous(Child.initial, props)
          }
          Behaviors.same
        case SpawnAdapter ⇒
          ctx.spawnMessageAdapter {
            r: Reproduce ⇒ SpawnAnonymous(r.times)
          }
          Behaviors.same
        case SpawnAdapterWithName(name) ⇒
          ctx.spawnMessageAdapter({
            r: Reproduce ⇒ SpawnAnonymous(r.times)
          }, name)
          Behaviors.same
        case SpawnAndWatchUnwatch(name) ⇒
          val c = ctx.spawn(Child.initial, name)
          ctx.watch(c)
          ctx.unwatch(c)
          Behaviors.same
        case m @ SpawnAndWatchWith(name) ⇒
          val c = ctx.spawn(Child.initial, name)
          ctx.watchWith(c, m)
          Behaviors.same
        case SpawnSession(replyTo, sessionHandler) ⇒
          val session = ctx.spawnAnonymous[String](Behaviors.receiveMessage { msg ⇒
            sessionHandler ! msg
            Behavior.same
          })
          replyTo ! session
          Behaviors.same
        case KillSession(session, replyTo) ⇒
          ctx.stop(session)
          replyTo ! Done
          Behaviors.same
        case CreateMessageAdapter(messageClass, f) ⇒
          ctx.messageAdapter(f)(ClassTag(messageClass))
          Behaviors.same

      }
    }
  }

  object Child {

    sealed trait Action

    val initial: Behavior[Action] = Behaviors.receive[Action] { (_, msg) ⇒
      msg match {
        case _ ⇒
          Behaviors.empty
      }
    }

  }

}

class BehaviorTestKitSpec extends WordSpec with Matchers {

  private val props = Props.empty.withDispatcherFromConfig("cat")

  "BehaviorTestKit" must {

    "allow assertions on effect type" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnAnonymous(1))
      val spawnAnonymous = testkit.expectEffectType[Effect.SpawnedAnonymous[_]]
      spawnAnonymous.props should ===(Props.empty)
    }

    "allow expecting NoEffects by type" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.expectEffectType[NoEffects]
    }

    "allow expecting NoEffects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.expectEffect(NoEffects)
    }

    "return if effects have taken place" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.hasEffects() should ===(false)
      testkit.run(SpawnAnonymous(1))
      testkit.hasEffects() should ===(true)
    }

    "allow assertions using partial functions - no match" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnChildren(1))
      val ae = intercept[AssertionError] {
        testkit.expectEffectPF {
          case SpawnedAnonymous(_, _) ⇒
        }
      }
      ae.getMessage should startWith("expected matching effect but got: ")
    }

    "allow assertions using partial functions - match" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnChildren(1))
      val childName = testkit.expectEffectPF {
        case Spawned(_, name, _) ⇒ name
      }
      childName should ===("child0")
    }

    "allow assertions using partial functions - match on NoEffect" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      val hasEffects = testkit.expectEffectPF {
        case NoEffects ⇒ false
      }
      hasEffects should ===(false)
    }
  }

  "BehaviorTestkit's spawn" must {
    "create children when no props specified" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnChildren(2))
      val effects = testkit.retrieveAllEffects()
      effects should contain only (Spawned(Child.initial, "child0"), Spawned(Child.initial, "child1", Props.empty))
    }

    "create children when props specified and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnChildrenWithProps(2, props))
      val effects = testkit.retrieveAllEffects()
      effects should contain only (Spawned(Child.initial, "child0", props), Spawned(Child.initial, "child1", props))
    }
  }

  "BehaviorTestkit's spawnAnonymous" must {
    "create children when no props specified and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnAnonymous(2))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, Props.empty), SpawnedAnonymous(Child.initial, Props.empty))
    }

    "create children when props specified and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)

      testkit.run(SpawnAnonymousWithProps(2, props))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, props), SpawnedAnonymous(Child.initial, props))
    }
  }

  "BehaviorTestkit's spawnMessageAdapter" must {
    "create adapters without name and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnAdapter)
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymousAdapter())
    }

    "create adapters with name and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnAdapterWithName("adapter"))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAdapter("adapter"))
    }
  }

  "BehaviorTestkit's messageAdapter" must {
    "create message adapters and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(CreateMessageAdapter(classOf[String], (_: String) ⇒ SpawnChildren(1)))
      testkit.expectEffectType[MessageAdapter[String, Command]]
    }
  }

  "BehaviorTestkit's run" can {
    "run behaviors with messages without canonicalization" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init)
      testkit.run(SpawnAdapterWithName("adapter"))
      testkit.currentBehavior should not be Behavior.same
      testkit.returnedBehavior shouldBe Behavior.same
    }
  }

  "BehaviorTestKit’s watch" must {
    "record effects for watching and unwatching" in {
      val testkit = BehaviorTestKit(Father.init)
      testkit.run(SpawnAndWatchUnwatch("hello"))
      val child = testkit.childInbox("hello").ref
      testkit.retrieveAllEffects() should be(Seq(
        Effects.spawned(Child.initial, "hello", Props.empty),
        Effects.watched(child),
        Effects.unwatched(child)
      ))
    }

    "record effects for watchWith" in {
      val testkit = BehaviorTestKit(Father.init)
      testkit.run(SpawnAndWatchWith("hello"))
      val child = testkit.childInbox("hello").ref
      testkit.retrieveAllEffects() should be(Seq(
        Effects.spawned(Child.initial, "hello", Props.empty),
        Effects.watched(child)
      ))
    }
  }

  "BehaviorTestKit’s child actor support" must {
    "allow retrieving and killing" in {
      val testkit = BehaviorTestKit(Father.init)
      val i = TestInbox[ActorRef[String]]()
      val h = TestInbox[String]()
      testkit.run(SpawnSession(i.ref, h.ref))

      val sessionRef = i.receiveMessage()
      i.hasMessages shouldBe false
      val s = testkit.expectEffectType[SpawnedAnonymous[_]]
      // must be able to get the created ref, even without explicit reply
      s.ref shouldBe sessionRef

      val session = testkit.childTestKit(sessionRef)
      session.run("hello")
      h.receiveAll() shouldBe Seq("hello")

      val d = TestInbox[Done]()
      testkit.run(KillSession(sessionRef, d.ref))

      d.receiveAll shouldBe Seq(Done)
      testkit.expectEffectType[Stopped]
    }
  }
}
