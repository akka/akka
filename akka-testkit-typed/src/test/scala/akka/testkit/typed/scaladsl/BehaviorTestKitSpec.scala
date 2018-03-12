/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.scaladsl

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.scaladsl.Behaviors.MutableBehavior
import akka.actor.typed.{ Behavior, Props }
import akka.testkit.typed.scaladsl.Effects.{ Spawned, SpawnedAdapter, SpawnedAnonymous }
import org.scalatest.{ Matchers, WordSpec }

import scala.concurrent.Future

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

    def behavior: Behavior[Command] = init()

    def init(): Behavior[Command] = Behaviors.immutable[Command] { (ctx, msg) ⇒
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
      }
    }

  }

  object Child {

    sealed trait Action

    val initial: Behavior[Action] = Behaviors.immutable[Action] { (_, msg) ⇒
      msg match {
        case _ ⇒
          Behaviors.empty
      }
    }

  }

  object Mutable {
    class MyMutableBehavior(ctx: ActorContext[String]) extends MutableBehavior[String] {
      implicit val ec = ctx.system.executionContext

      Future {
        println("Hoi")
      }

      override def onMessage(msg: String): Behavior[String] = this
    }

    def mutable(): Behavior[String] =
      Behaviors.mutable[String](ctx ⇒ new MyMutableBehavior(ctx))
  }
}

class BehaviorTestKitSpec extends WordSpec with Matchers {
  import BehaviorTestKitSpec._
  import BehaviorTestKitSpec.Father._

  private val props = Props.empty

  "BehaviorTestkit initialization" must {
    "trigger futures" in {
      val testkit = BehaviorTestKit[String](Mutable.mutable())
      //      testkit.run("x")
      Thread.sleep(1000)
    }
  }

  "BehaviorTestkit's spawn" must {
    "create children when no props specified" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init())
      testkit.run(SpawnChildren(2))
      val effects = testkit.retrieveAllEffects()
      effects should contain only (Spawned(Child.initial, "child0"), Spawned(Child.initial, "child1", Props.empty))
    }

    "create children when props specified and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init())
      testkit.run(SpawnChildrenWithProps(2, props))
      val effects = testkit.retrieveAllEffects()
      effects should contain only (Spawned(Child.initial, "child0", props), Spawned(Child.initial, "child1", props))
    }
  }

  "BehaviorTestkit's spawnAnonymous" must {
    "create children when no props specified and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init())
      testkit.run(SpawnAnonymous(2))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, Props.empty), SpawnedAnonymous(Child.initial, Props.empty))
    }

    "create children when props specified and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init())

      testkit.run(SpawnAnonymousWithProps(2, props))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, props), SpawnedAnonymous(Child.initial, props))
    }
  }

  "BehaviorTestkit's spawnMessageAdapter" must {
    "create adapters without name and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init())
      testkit.run(SpawnAdapter)
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAdapter)
    }

    "create adapters with name and record effects" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init())
      testkit.run(SpawnAdapterWithName("adapter"))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAdapter)
    }
  }

  "BehaviorTestkit's run" can {
    "run behaviors with messages without canonicalization" in {
      val testkit = BehaviorTestKit[Father.Command](Father.init())
      testkit.run(SpawnAdapterWithName("adapter"))
      testkit.currentBehavior should not be Behavior.same
      testkit.returnedBehavior shouldBe Behavior.same
    }
  }
}
