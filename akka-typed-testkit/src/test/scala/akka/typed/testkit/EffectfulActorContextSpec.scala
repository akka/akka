/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.typed.testkit

import akka.typed.scaladsl.Actor
import akka.typed.testkit.Effect.Spawned
import akka.typed.testkit.EffectfulActorContextSpec.Father
import akka.typed.testkit.EffectfulActorContextSpec.Father._
import akka.typed.{ ActorSystem, Behavior, Props }
import org.scalatest.{ FlatSpec, Matchers }

object EffectfulActorContextSpec {
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

    def initial: Behavior[Action] = Actor.immutable[Action] { (_, msg) ⇒
      msg match {
        case _ ⇒
          Actor.empty
      }
    }

  }

}

class EffectfulActorContextSpec extends FlatSpec with Matchers {

  val props = Props.empty.withMailboxCapacity(10)

  "EffectfulActorContext's spawn" should "create children when no props specified" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnChildren(2))
    val effects = ctx.getAllEffects()
    effects should contain only (Spawned("child0", Some(Props.empty)), Spawned("child1", Some(Props.empty)))
  }

  it should "create children when props specified and record effects" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnChildrenWithProps(2, props))
    val effects = ctx.getAllEffects()
    effects should contain only (Spawned("child0", Some(props)), Spawned("child1", Some(props)))
  }

  "EffectfulActorContext's spawnAnonymous" should "create children when no props specified and record effects" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnAnonymous(2))
    val effects = ctx.getAllEffects()
    effects.size shouldBe 2
    effects.foreach { eff ⇒
      eff shouldBe a[Spawned]
      eff.asInstanceOf[Spawned].props shouldBe Some(Props.empty)
    }
  }

  it should "create children when props specified and record effects" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnAnonymousWithProps(2, props))
    val effects = ctx.getAllEffects()
    effects.size shouldBe 2
    effects.foreach { eff ⇒
      eff shouldBe a[Spawned]
      eff.asInstanceOf[Spawned].props shouldBe Some(props)
    }
  }

  "EffectfulActorContext's spawnAdapter" should "create adapters without name and record effects" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnAdapter)
    val effects = ctx.getAllEffects()
    effects.size shouldBe 1
    effects.foreach { eff ⇒
      eff shouldBe a[Spawned]
      eff.asInstanceOf[Spawned].props shouldBe None
    }
  }

  it should "create adapters with name and record effects" in {
    val system = ActorSystem.create(Father.init(), "father-system")
    val ctx = new EffectfulActorContext[Father.Command]("father-test", Father.init(), 100, system)

    ctx.run(SpawnAdapterWithName("adapter"))
    val effects = ctx.getAllEffects()
    effects.size shouldBe 1
    effects.foreach { eff ⇒
      eff shouldBe a[Spawned]
      eff.asInstanceOf[Spawned].props shouldBe None
    }
  }
}
