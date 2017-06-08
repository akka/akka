/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import scala.concurrent.{ Promise, Future }

class FunctionRefSpec extends TypedSpecSetup {

  object `A FunctionRef` {

    def `must forward messages that are received after getting the ActorRef (completed later)`(): Unit = {
      val p = Promise[ActorRef[String]]
      val ref = ActorRef(p.future)
      val target = new DebugRef[String](ref.path / "target", true)
      p.success(target)
      ref ! "42"
      ref ! "43"
      target.receiveAll() should ===(Left(Watch(target, ref)) :: Right("42") :: Right("43") :: Nil)
    }

    def `must forward messages that are received after getting the ActorRef (already completed)`(): Unit = {
      val target = new DebugRef[String](ActorRef.FuturePath / "target", true)
      val f = Future.successful(target)
      val ref = ActorRef(f)
      ref ! "42"
      ref ! "43"
      target.receiveAll() should ===(Left(Watch(target, ref)) :: Right("42") :: Right("43") :: Nil)
    }

    def `must forward messages that are received before getting the ActorRef`(): Unit = {
      val p = Promise[ActorRef[String]]
      val ref = ActorRef(p.future)
      ref ! "42"
      ref ! "43"
      val target = new DebugRef[String](ref.path / "target", true)
      p.success(target)
      target.receiveAll() should ===(Right("42") :: Right("43") :: Left(Watch(target, ref)) :: Nil)
    }

    def `must notify watchers when the future fails`(): Unit = {
      val p = Promise[ActorRef[String]]
      val ref = ActorRef(p.future)
      val client1 = new DebugRef(ref.path / "c1", true)

      ref.sorry.sendSystem(Watch(ref, client1))
      client1.hasSomething should ===(false)

      p.failure(new Exception)
      client1.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client1.hasSomething should ===(false)

      val client2 = new DebugRef(ref.path / "c2", true)

      ref.sorry.sendSystem(Watch(ref, client2))
      client2.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client2.hasSomething should ===(false)
      client1.hasSomething should ===(false)
    }

    def `must notify watchers when terminated`(): Unit = {
      val p = Promise[ActorRef[String]]
      val ref = ActorRef(p.future)
      val client1 = new DebugRef(ref.path / "c1", true)

      ref.sorry.sendSystem(Watch(ref, client1))
      client1.hasSomething should ===(false)

      ref.sorry.sendSystem(Terminate())
      client1.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client1.hasSomething should ===(false)

      val client2 = new DebugRef(ref.path / "c2", true)

      ref.sorry.sendSystem(Watch(ref, client2))
      client2.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client2.hasSomething should ===(false)
      client1.hasSomething should ===(false)
    }

    def `must notify watchers when terminated after receiving the target`(): Unit = {
      val p = Promise[ActorRef[String]]
      val ref = ActorRef(p.future)
      val client1 = new DebugRef(ref.path / "c1", true)

      ref.sorry.sendSystem(Watch(ref, client1))
      client1.hasSomething should ===(false)

      val target = new DebugRef[String](ref.path / "target", true)
      p.success(target)
      ref ! "42"
      ref ! "43"
      target.receiveAll() should ===(Left(Watch(target, ref)) :: Right("42") :: Right("43") :: Nil)

      ref.sorry.sendSystem(Terminate())
      client1.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client1.hasSomething should ===(false)
      target.receiveAll() should ===(Left(Unwatch(target, ref)) :: Nil)

      val client2 = new DebugRef(ref.path / "c2", true)

      ref.sorry.sendSystem(Watch(ref, client2))
      client2.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client2.hasSomething should ===(false)
      client1.hasSomething should ===(false)
    }

    def `must notify watchers when receiving the target after terminating`(): Unit = {
      val p = Promise[ActorRef[String]]
      val ref = ActorRef(p.future)
      val client1 = new DebugRef(ref.path / "c1", true)

      ref.sorry.sendSystem(Watch(ref, client1))
      client1.hasSomething should ===(false)

      ref.sorry.sendSystem(Terminate())
      client1.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client1.hasSomething should ===(false)

      val target = new DebugRef[String](ref.path / "target", true)
      p.success(target)
      ref ! "42"
      ref ! "43"
      target.hasSomething should ===(false)

      val client2 = new DebugRef(ref.path / "c2", true)

      ref.sorry.sendSystem(Watch(ref, client2))
      client2.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client2.hasSomething should ===(false)
      client1.hasSomething should ===(false)
    }

    def `must notify watchers when the target ActorRef terminates`(): Unit = {
      val p = Promise[ActorRef[String]]
      val ref = ActorRef(p.future)
      val client1 = new DebugRef(ref.path / "c1", true)

      ref.sorry.sendSystem(Watch(ref, client1))
      client1.hasSomething should ===(false)

      val target = new DebugRef[String](ref.path / "target", true)
      p.success(target)
      ref ! "42"
      ref ! "43"
      target.receiveAll() should ===(Left(Watch(target, ref)) :: Right("42") :: Right("43") :: Nil)

      ref.sorry.sendSystem(DeathWatchNotification(target, null))
      client1.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client1.hasSomething should ===(false)
      target.hasSomething should ===(false)

      val client2 = new DebugRef(ref.path / "c2", true)

      ref.sorry.sendSystem(Watch(ref, client2))
      client2.receiveSignal() should ===(DeathWatchNotification(ref, null))
      client2.hasSomething should ===(false)
      client1.hasSomething should ===(false)
    }

  }

}
