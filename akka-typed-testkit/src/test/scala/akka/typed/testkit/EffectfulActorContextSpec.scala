/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.typed.testkit

import akka.typed.scaladsl.Actor
import akka.typed.testkit.Effect.Spawned
import akka.typed.testkit.EffectfulActorContextSpec.Father
import akka.typed.testkit.EffectfulActorContextSpec.Father.{ SpawnAnonymous, SpawnChildren }
import akka.typed.{ ActorSystem, Behavior }
import org.scalatest.{ FlatSpec, Matchers }

object EffectfulActorContextSpec {
  object Father {
    sealed trait Command

    case class SpawnChildren(numberOfChildren: Int) extends Command
    case class SpawnAnonymous(numberOfChildren: Int) extends Command

    def behavior: Behavior[Command] = init()

    def init(): Behavior[Command] = Actor.immutable[Command] { (ctx, msg) ⇒
      msg match {
        case SpawnChildren(numberOfChildren) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { i ⇒
            ctx.spawn(Child.initial, s"child$i")
          }
          Actor.same
        case SpawnAnonymous(numberOfChildren) if numberOfChildren > 0 ⇒
          0.until(numberOfChildren).foreach { _ ⇒
            ctx.spawnAnonymous(Child.initial)
          }
          Actor.same
      }
    }
  }

  object Child {

    sealed trait Action

    def initial: Behavior[Action] = Actor.immutable[Action] { (_, msg) ⇒
      msg match {
        case _ ⇒
          Actor.empty
      }
    }

  }

}

class EffectfulActorContextSpec extends FlatSpec with Matchers {

  "EffectfulActorContext's spawn" should "create children" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnChildren(2))
    val effects = ctx.getAllEffects()
    effects should contain only (Spawned("child0"), Spawned("child1"))
  }

  "EffectfulActorContext's spawnAnonymous" should "create children" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnAnonymous(2))
    val effects = ctx.getAllEffects()
    effects.size shouldBe 2
    effects.foreach { eff ⇒
      eff shouldBe a[Spawned]
    }
  }
}
