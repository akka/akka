/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.patterns

import Receptionist._
import akka.typed.ScalaDSL._
import akka.typed.AskPattern._
import scala.concurrent.duration._
import akka.typed._

class ReceptionistSpec extends TypedSpec {

  trait ServiceA
  case object ServiceKeyA extends ServiceKey[ServiceA]
  val behaviorA = Static[ServiceA](msg ⇒ ())

  trait ServiceB
  case object ServiceKeyB extends ServiceKey[ServiceB]
  val behaviorB = Static[ServiceB](msg ⇒ ())

  trait CommonTests {
    implicit def system: ActorSystem[TypedSpec.Command]

    def `must register a service`(): Unit = {
      val ctx = new EffectfulActorContext("register", behavior, 1000, system)
      val a = Inbox[ServiceA]("a")
      val r = Inbox[Registered[_]]("r")
      ctx.run(Register(ServiceKeyA, a.ref)(r.ref))
      ctx.getAllEffects() should be(Effect.Watched(a.ref) :: Nil)
      r.receiveMsg() should be(Registered(ServiceKeyA, a.ref))
      val q = Inbox[Listing[ServiceA]]("q")
      ctx.run(Find(ServiceKeyA)(q.ref))
      ctx.getAllEffects() should be(Nil)
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a.ref)))
      assertEmpty(a, r, q)
    }

    def `must register two services`(): Unit = {
      val ctx = new EffectfulActorContext("registertwo", behavior, 1000, system)
      val a = Inbox[ServiceA]("a")
      val r = Inbox[Registered[_]]("r")
      ctx.run(Register(ServiceKeyA, a.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyA, a.ref))
      val b = Inbox[ServiceB]("b")
      ctx.run(Register(ServiceKeyB, b.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyB, b.ref))
      val q = Inbox[Listing[_]]("q")
      ctx.run(Find(ServiceKeyA)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a.ref)))
      ctx.run(Find(ServiceKeyB)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyB, Set(b.ref)))
      assertEmpty(a, b, r, q)
    }

    def `must register two services with the same key`(): Unit = {
      val ctx = new EffectfulActorContext("registertwosame", behavior, 1000, system)
      val a1 = Inbox[ServiceA]("a1")
      val r = Inbox[Registered[_]]("r")
      ctx.run(Register(ServiceKeyA, a1.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyA, a1.ref))
      val a2 = Inbox[ServiceA]("a2")
      ctx.run(Register(ServiceKeyA, a2.ref)(r.ref))
      r.receiveMsg() should be(Registered(ServiceKeyA, a2.ref))
      val q = Inbox[Listing[_]]("q")
      ctx.run(Find(ServiceKeyA)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a1.ref, a2.ref)))
      ctx.run(Find(ServiceKeyB)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyB, Set.empty[ActorRef[ServiceB]]))
      assertEmpty(a1, a2, r, q)
    }

    def `must unregister services when they terminate`(): Unit = {
      val ctx = new EffectfulActorContext("registertwosame", behavior, 1000, system)
      val r = Inbox[Registered[_]]("r")
      val a = Inbox[ServiceA]("a")
      ctx.run(Register(ServiceKeyA, a.ref)(r.ref))
      ctx.getEffect() should be(Effect.Watched(a.ref))
      r.receiveMsg() should be(Registered(ServiceKeyA, a.ref))

      val b = Inbox[ServiceB]("b")
      ctx.run(Register(ServiceKeyB, b.ref)(r.ref))
      ctx.getEffect() should be(Effect.Watched(b.ref))
      r.receiveMsg() should be(Registered(ServiceKeyB, b.ref))

      val c = Inbox[Any]("c")
      ctx.run(Register(ServiceKeyA, c.ref)(r.ref))
      ctx.run(Register(ServiceKeyB, c.ref)(r.ref))
      ctx.getAllEffects() should be(Seq(Effect.Watched(c.ref), Effect.Watched(c.ref)))
      r.receiveMsg() should be(Registered(ServiceKeyA, c.ref))
      r.receiveMsg() should be(Registered(ServiceKeyB, c.ref))

      val q = Inbox[Listing[_]]("q")
      ctx.run(Find(ServiceKeyA)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a.ref, c.ref)))
      ctx.run(Find(ServiceKeyB)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyB, Set(b.ref, c.ref)))

      ctx.signal(Terminated(c.ref)(null))
      ctx.run(Find(ServiceKeyA)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyA, Set(a.ref)))
      ctx.run(Find(ServiceKeyB)(q.ref))
      q.receiveMsg() should be(Listing(ServiceKeyB, Set(b.ref)))
      assertEmpty(a, b, c, r, q)
    }

    def `must work with ask`(): Unit = sync(runTest("Receptionist") {
      StepWise[Registered[ServiceA]] { (ctx, startWith) ⇒
        val self = ctx.self
        startWith.withKeepTraces(true) {
          val r = ctx.spawnAnonymous(behavior)
          val s = ctx.spawnAnonymous(behaviorA)
          val f = r ? Register(ServiceKeyA, s)
          r ! Register(ServiceKeyA, s)(self)
          (f, s)
        }.expectMessage(1.second) {
          case (msg, (f, s)) ⇒
            msg should be(Registered(ServiceKeyA, s))
            f.foreach(self ! _)(system.executionContext)
            s
        }.expectMessage(1.second) {
          case (msg, s) ⇒
            msg should be(Registered(ServiceKeyA, s))
        }
      }
    })

  }

  object `A Receptionist (native)` extends CommonTests with NativeSystem
  object `A Receptionist (adapted)` extends CommonTests with AdaptedSystem

}
