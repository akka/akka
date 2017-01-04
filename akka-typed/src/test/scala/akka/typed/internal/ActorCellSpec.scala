/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import org.scalactic.ConversionCheckedTripleEquals
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.exceptions.TestFailedException
import org.scalatest._
import org.junit.runner.RunWith

@RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorCellSpec extends Spec with Matchers with BeforeAndAfterAll with ScalaFutures with ConversionCheckedTripleEquals {

  import ScalaDSL._

  val sys = new ActorSystemStub("ActorCellSpec")
  def ec = sys.controlledExecutor

  object `An ActorCell` {

    def `must be creatable`(): Unit = {
      val parent = new DebugRef[String](sys.path / "creatable", true)
      val cell = new ActorCell(sys, Deferred[String](() ⇒ { parent ! "created"; Static { s ⇒ parent ! s } }), ec, 1000, parent)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("created") :: Nil)
        cell.send("hello")
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("hello") :: Nil)
      }
    }

    def `must be creatable with ???`(): Unit = {
      val parent = new DebugRef[String](sys.path / "creatable???", true)
      val self = new DebugRef[String](sys.path / "creatableSelf", true)
      val ??? = new NotImplementedError
      val cell = new ActorCell(sys, Deferred[String](() ⇒ { parent ! "created"; throw ??? }), ec, 1000, parent)
      cell.setSelf(self)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("created") :: Nil)
        // explicitly verify termination via self-signal
        self.receiveAll() should ===(Left(Terminate()) :: Nil)
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(self, ???)) :: Nil)
      }
    }

    def `must be able to terminate after construction`(): Unit = {
      val parent = new DebugRef[String](sys.path / "terminate", true)
      val self = new DebugRef[String](sys.path / "terminateSelf", true)
      val cell = new ActorCell(sys, Deferred[String](() ⇒ { parent ! "created"; Stopped }), ec, 1000, parent)
      cell.setSelf(self)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("created") :: Nil)
        // explicitly verify termination via self-signal
        self.receiveAll() should ===(Left(Terminate()) :: Nil)
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(self, null)) :: Nil)
      }
    }

    def `must be able to terminate after PreStart`(): Unit = {
      val parent = new DebugRef[String](sys.path / "terminate", true)
      val self = new DebugRef[String](sys.path / "terminateSelf", true)
      val cell = new ActorCell(sys, Deferred(() ⇒ { parent ! "created"; Full[String] { case Sig(ctx, PreStart) ⇒ Stopped } }), ec, 1000, parent)
      cell.setSelf(self)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("created") :: Nil)
        // explicitly verify termination via self-signal
        self.receiveAll() should ===(Left(Terminate()) :: Nil)
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(self, null)) :: Nil)
      }
    }

    def `must terminate upon failure during processing`(): Unit = {
      val parent = new DebugRef[String](sys.path / "terminate", true)
      val self = new DebugRef[String](sys.path / "terminateSelf", true)
      val ex = new AssertionError
      val cell = new ActorCell(sys, Deferred(() ⇒ { parent ! "created"; Static[String](s ⇒ throw ex) }), ec, 1000, parent)
      cell.setSelf(self)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("created") :: Nil)
        cell.send("")
        ec.runOne()
        ec.queueSize should ===(0)
        // explicitly verify termination via self-signal
        self.receiveAll() should ===(Left(Terminate()) :: Nil)
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(self, ex)) :: Nil)
      }
    }

    def `must signal failure when starting behavior is "same"`(): Unit = {
      val parent = new DebugRef[String](sys.path / "startSame", true)
      val self = new DebugRef[String](sys.path / "startSameSelf", true)
      val cell = new ActorCell(sys, Deferred(() ⇒ { parent ! "created"; Same[String] }), ec, 1000, parent)
      cell.setSelf(self)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("created") :: Nil)
        // explicitly verify termination via self-signal
        self.receiveAll() should ===(Left(Terminate()) :: Nil)
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() match {
          case Left(DeathWatchNotification(`self`, exc)) :: Nil ⇒
            exc should not be null
            exc shouldBe an[IllegalArgumentException]
            exc.getMessage should include("Same")
          case other ⇒ fail(s"$other was not a DeathWatchNotification")
        }
      }
    }

    def `must signal failure when starting behavior is "unhandled"`(): Unit = {
      val parent = new DebugRef[String](sys.path / "startSame", true)
      val self = new DebugRef[String](sys.path / "startSameSelf", true)
      val cell = new ActorCell(sys, Deferred(() ⇒ { parent ! "created"; Unhandled[String] }), ec, 1000, parent)
      cell.setSelf(self)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Right("created") :: Nil)
        // explicitly verify termination via self-signal
        self.receiveAll() should ===(Left(Terminate()) :: Nil)
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() match {
          case Left(DeathWatchNotification(`self`, exc)) :: Nil ⇒
            exc should not be null
            exc shouldBe an[IllegalArgumentException]
            exc.getMessage should include("Unhandled")
          case other ⇒ fail(s"$other was not a DeathWatchNotification")
        }
      }
    }

    /*
     * also tests:
     * - must reschedule for self-message
     * - must not reschedule for message when already activated
     * - must not reschedule for signal when already activated
     */
    def `must not execute more messages than were batched naturally`(): Unit = {
      val parent = new DebugRef[String](sys.path / "batching", true)
      val cell = new ActorCell(sys, SelfAware[String] { self ⇒ Static { s ⇒ self ! s; parent ! s } }, ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Nil)
        cell.send("one")
        cell.send("two")
        ec.queueSize should ===(1)
        ec.runOne()
        ec.queueSize should ===(1)
        parent.receiveAll() should ===(Right("one") :: Right("two") :: Nil)
        ec.runOne()
        ec.queueSize should ===(1)
        parent.receiveAll() should ===(Right("one") :: Right("two") :: Nil)
        cell.send("three")
        ec.runOne()
        ec.queueSize should ===(1)
        parent.receiveAll() should ===(Right("one") :: Right("two") :: Right("three") :: Nil)
        cell.sendSystem(Terminate())
        ec.queueSize should ===(1)
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
      }
    }

    def `must signal DeathWatch when terminating normally`(): Unit = {
      val parent = new DebugRef[String](sys.path / "watchNormal", true)
      val client = new DebugRef[String](parent.path / "client", true)
      val cell = new ActorCell(sys, Empty[String], ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Watch(ref, client))
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
        client.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
      }
    }

    /*
     * also tests:
     * - must turn a DeathWatchNotification into a Terminated signal while watching
     * - must terminate with DeathPactException when not handling a Terminated signal
     * - must send a Watch message when watching another actor
     */
    def `must signal DeathWatch when terminating abnormally`(): Unit = {
      val parent = new DebugRef[String](sys.path / "watchAbnormal", true)
      val client = new DebugRef[String](parent.path / "client", true)
      val other = new DebugRef[String](parent.path / "other", true)
      val cell = new ActorCell(sys, ContextAware[String] { ctx ⇒ ctx.watch(parent); Empty }, ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(Watch(parent, ref)) :: Nil)
        // test that unwatched termination is ignored
        cell.sendSystem(DeathWatchNotification(other, null))
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Nil)
        // now trigger failure by death pact
        cell.sendSystem(Watch(ref, client))
        cell.sendSystem(DeathWatchNotification(parent, null))
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() match {
          case Left(DeathWatchNotification(ref, exc)) :: Nil ⇒
            exc should not be null
            exc shouldBe a[DeathPactException]
          case other ⇒ fail(s"$other was not a DeathWatchNotification")
        }
        client.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
      }
    }

    def `must signal DeathWatch when watching after termination`(): Unit = {
      val parent = new DebugRef[String](sys.path / "watchLate", true)
      val client = new DebugRef[String](parent.path / "client", true)
      val cell = new ActorCell(sys, Stopped[String], ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
        cell.sendSystem(Watch(ref, client))
        ec.queueSize should ===(0)
        sys.deadLettersInbox.receiveAll() should ===(Left(Watch(ref, client)) :: Nil)
        // correct behavior of deadLetters is verified in ActorSystemSpec
      }
    }

    def `must signal DeathWatch when watching after termination but before deactivation`(): Unit = {
      val parent = new DebugRef[String](sys.path / "watchSomewhatLate", true)
      val client = new DebugRef[String](parent.path / "client", true)
      val cell = new ActorCell(sys, Empty[String], ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        cell.sendSystem(Terminate())
        cell.sendSystem(Watch(ref, client))
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
        sys.deadLettersInbox.receiveAll() should ===(Left(Watch(ref, client)) :: Nil)
      }
    }

    def `must not signal DeathWatch after Unwatch has been processed`(): Unit = {
      val parent = new DebugRef[String](sys.path / "watchUnwatch", true)
      val client = new DebugRef[String](parent.path / "client", true)
      val cell = new ActorCell(sys, Empty[String], ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Watch(ref, client))
        cell.sendSystem(Unwatch(ref, client))
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
        client.receiveAll() should ===(Nil)
      }
    }

    def `must send messages to deadLetters after being terminated`(): Unit = {
      val parent = new DebugRef[String](sys.path / "sendDeadLetters", true)
      val cell = new ActorCell(sys, Stopped[String], ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
        cell.send("42")
        ec.queueSize should ===(0)
        sys.deadLettersInbox.receiveAll() should ===(Right("42") :: Nil)
      }
    }

    /*
     * also tests:
     * - child creation
     */
    def `must not terminate before children have terminated`(): Unit = {
      val parent = new DebugRef[ActorRef[Nothing]](sys.path / "waitForChild", true)
      val cell = new ActorCell(sys, ContextAware[String] { ctx ⇒
        ctx.spawn(SelfAware[String] { self ⇒ parent ! self; Empty }, "child")
        Empty
      }, ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne() // creating subject
        parent.hasSomething should ===(false)
        ec.runOne() // creating child
        ec.queueSize should ===(0)
        val child = parent.receiveAll() match {
          case Right(child) :: Nil ⇒
            child.sorryForNothing.sendSystem(Watch(child, parent))
            child
          case other ⇒ fail(s"$other was not List(Right(<child>))")
        }
        ec.runOne()
        ec.queueSize should ===(0)
        cell.sendSystem(Terminate())
        ec.runOne() // begin subject termination, will initiate child termination
        parent.hasSomething should ===(false)
        ec.runOne() // terminate child
        parent.receiveAll() should ===(Left(DeathWatchNotification(child, null)) :: Nil)
        ec.runOne() // terminate subject
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
      }
    }

    def `must properly terminate if failing while handling Terminated for child actor`(): Unit = {
      val parent = new DebugRef[ActorRef[Nothing]](sys.path / "terminateWhenDeathPact", true)
      val cell = new ActorCell(sys, ContextAware[String] { ctx ⇒
        ctx.watch(ctx.spawn(SelfAware[String] { self ⇒ parent ! self; Empty }, "child"))
        Empty
      }, ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne() // creating subject
        parent.hasSomething should ===(false)
        ec.runOne() // creating child
        ec.queueSize should ===(0)
        val child = parent.receiveAll() match {
          case Right(child: ActorRefImpl[Nothing]) :: Nil ⇒
            child.sendSystem(Watch(child, parent))
            child
          case other ⇒ fail(s"$other was not List(Right(<child>))")
        }
        ec.runOne()
        ec.queueSize should ===(0)
        child.sendSystem(Terminate())
        ec.runOne() // child terminates and enqueues DeathWatchNotification
        parent.receiveAll() should ===(Left(DeathWatchNotification(child, null)) :: Nil)
        ec.runOne() // cell fails during Terminated and terminates with DeathPactException
        parent.receiveAll() match {
          case Left(DeathWatchNotification(`ref`, ex: DeathPactException)) :: Nil ⇒
            ex.getMessage should include("death pact")
          case other ⇒ fail(s"$other was not Left(DeathWatchNotification($ref, DeathPactException))")
        }
        ec.queueSize should ===(0)
      }
    }

    def `must not terminate twice if failing in PostStop`(): Unit = {
      val parent = new DebugRef[String](sys.path / "terminateProperlyPostStop", true)
      val cell = new ActorCell(sys, Full[String] { case Sig(_, PostStop) ⇒ ??? }, ec, 1000, parent)
      val ref = new LocalActorRef(parent.path / "child", cell)
      cell.setSelf(ref)
      debugCell(cell) {
        ec.queueSize should ===(0)
        cell.sendSystem(Create())
        ec.runOne()
        ec.queueSize should ===(0)
        cell.sendSystem(Terminate())
        ec.runOne()
        ec.queueSize should ===(0)
        parent.receiveAll() should ===(Left(DeathWatchNotification(ref, null)) :: Nil)
      }
    }
  }

  private def debugCell[T, U](cell: ActorCell[T])(block: ⇒ U): U =
    try block
    catch {
      case ex: TestFailedException ⇒
        println(cell)
        throw ex
    }

}
