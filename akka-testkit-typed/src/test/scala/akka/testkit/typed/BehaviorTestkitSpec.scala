/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.testkit.typed

import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.{ Behavior, Props }
import akka.testkit.typed.BehaviorTestkitSpec.Father._
import akka.testkit.typed.BehaviorTestkitSpec.{ Child, Father }
import akka.testkit.typed.Effect.{ Spawned, SpawnedAdapter, SpawnedAnonymous }
import org.scalatest.{ Matchers, WordSpec }

object BehaviorTestkitSpec {
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

    def init(): Behavior[Command] = Actor.immutable[Command] { (ctx, msg) ⇒
      msg match {
        case SpawnChildren(numberOfChildren) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { i ⇒
            ctx.spawn(Child.initial, s"child$i")
          }
          Actor.same
        case SpawnChildrenWithProps(numberOfChildren, props) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { i ⇒
            ctx.spawn(Child.initial, s"child$i", props)
          }
          Actor.same
        case SpawnAnonymous(numberOfChildren) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { _ ⇒
            ctx.spawnAnonymous(Child.initial)
          }
          Actor.same
        case SpawnAnonymousWithProps(numberOfChildren, props) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { _ ⇒
            ctx.spawnAnonymous(Child.initial, props)
          }
          Actor.same
        case SpawnAdapter ⇒
          ctx.spawnAdapter {
            r: Reproduce ⇒ SpawnAnonymous(r.times)
          }
          Actor.same
        case SpawnAdapterWithName(name) ⇒
          ctx.spawnAdapter({
            r: Reproduce ⇒ SpawnAnonymous(r.times)
          }, name)
          Actor.same
      }
    }
  }

  object Child {

    sealed trait Action

    val initial: Behavior[Action] = Actor.immutable[Action] { (_, msg) ⇒
      msg match {
        case _ ⇒
          Actor.empty
      }
    }

  }

}

class BehaviorTestkitSpec extends WordSpec with Matchers {

  private val props = Props.empty

  "BehaviourTestkit's spawn" should {
    "create children when no props specified" in {
      val ctx = BehaviorTestkit[Father.Command](Father.init())

      ctx.run(SpawnChildren(2))
      val effects = ctx.retrieveAllEffects()
      effects should contain only (Spawned(Child.initial, "child0"), Spawned(Child.initial, "child1", Props.empty))
    }

    "create children when props specified and record effects" in {
      val ctx = BehaviorTestkit[Father.Command](Father.init())

      ctx.run(SpawnChildrenWithProps(2, props))
      val effects = ctx.retrieveAllEffects()
      effects should contain only (Spawned(Child.initial, "child0", props), Spawned(Child.initial, "child1", props))
    }
  }

  "BehaviourTestkit's spawnAnonymous" should {
    "create children when no props specified and record effects" in {
      val ctx = BehaviorTestkit[Father.Command](Father.init())

      ctx.run(SpawnAnonymous(2))
      val effects = ctx.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, Props.empty), SpawnedAnonymous(Child.initial, Props.empty))
    }

    "create children when props specified and record effects" in {
      val ctx = BehaviorTestkit[Father.Command](Father.init())

      ctx.run(SpawnAnonymousWithProps(2, props))
      val effects = ctx.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, props), SpawnedAnonymous(Child.initial, props))
    }
  }

  "BehaviourTestkit's spawnAdapter" should {
    "create adapters without name and record effects" in {
      val ctx = BehaviorTestkit[Father.Command](Father.init())

      ctx.run(SpawnAdapter)
      val effects = ctx.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAdapter)
    }

    "create adapters with name and record effects" in {
      val ctx = BehaviorTestkit[Father.Command](Father.init())

      ctx.run(SpawnAdapterWithName("adapter"))
      val effects = ctx.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAdapter)
    }
  }
}
