/*
 * Copyright (C) 2014-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

import akka.Done
import akka.actor.Address
import akka.actor.testkit.typed.Effect._
import akka.actor.testkit.typed.scaladsl.BehaviorTestKitSpec.Parent._
import akka.actor.testkit.typed.scaladsl.BehaviorTestKitSpec.{ Child, Parent }
import akka.actor.testkit.typed.{ CapturedLogEvent, Effect }
import akka.actor.typed.receptionist.{ Receptionist, ServiceKey }
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior, Props, Terminated }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.event.Level

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.reflect.ClassTag

object BehaviorTestKitSpec {
  object Parent {

    case class Reproduce(times: Int)

    sealed trait Command

    case object SpawnChild extends Command
    case class SpawnChildren(numberOfChildren: Int) extends Command
    case class SpawnChildrenWithProps(numberOfChildren: Int, props: Props) extends Command
    case class SpawnAnonymous(numberOfChildren: Int) extends Command
    case class SpawnAnonymousWithProps(numberOfChildren: Int, props: Props) extends Command
    case class StopChild(child: ActorRef[String]) extends Command
    case object SpawnAdapter extends Command
    case class SpawnAdapterWithName(name: String) extends Command
    case class CreateMessageAdapter[U](
        messageClass: Class[U],
        f: U => Command,
        replyTo: Option[ActorRef[ActorRef[U]]] = None)
        extends Command
    case class SpawnAndWatchUnwatch(name: String) extends Command
    case class SpawnAndWatchWith(name: String) extends Command
    case class SpawnSession(replyTo: ActorRef[ActorRef[String]], sessionHandler: ActorRef[String]) extends Command
    case class KillSession(session: ActorRef[String], replyTo: ActorRef[Done]) extends Command
    case class Log(what: String) extends Command
    case class RegisterWithReceptionist(name: String) extends Command
    case class ScheduleCommand(key: Any, delay: FiniteDuration, mode: Effect.TimerScheduled.TimerMode, cmd: Command)
        extends Command
    case class CancelScheduleCommand(key: Any) extends Command
    case class IsTimerActive(key: Any, replyTo: ActorRef[Boolean]) extends Command

    val init: Behavior[Command] = Behaviors.withTimers { timers =>
      Behaviors
        .receive[Command] { (context, message) =>
          message match {
            case SpawnChild =>
              context.spawn(Child.initial, "child")
              Behaviors.same
            case SpawnChildren(numberOfChildren) if numberOfChildren > 0 =>
              0.until(numberOfChildren).foreach { i =>
                context.spawn(Child.initial, s"child$i")
              }
              Behaviors.same
            case SpawnChildrenWithProps(numberOfChildren, props) if numberOfChildren > 0 =>
              0.until(numberOfChildren).foreach { i =>
                context.spawn(Child.initial, s"child$i", props)
              }
              Behaviors.same
            case SpawnAnonymous(numberOfChildren) if numberOfChildren > 0 =>
              0.until(numberOfChildren).foreach { _ =>
                context.spawnAnonymous(Child.initial)
              }
              Behaviors.same
            case SpawnAnonymousWithProps(numberOfChildren, props) if numberOfChildren > 0 =>
              0.until(numberOfChildren).foreach { _ =>
                context.spawnAnonymous(Child.initial, props)
              }
              Behaviors.same
            case StopChild(child) =>
              context.stop(child)
              Behaviors.same
            case SpawnAdapter =>
              context.spawnMessageAdapter { (r: Reproduce) =>
                SpawnAnonymous(r.times)
              }
              Behaviors.same
            case SpawnAdapterWithName(name) =>
              context.spawnMessageAdapter({ (r: Reproduce) =>
                SpawnAnonymous(r.times)
              }, name)
              Behaviors.same
            case SpawnAndWatchUnwatch(name) =>
              val c = context.spawn(Child.initial, name)
              context.watch(c)
              context.unwatch(c)
              Behaviors.same
            case m @ SpawnAndWatchWith(name) =>
              val c = context.spawn(Child.initial, name)
              context.watchWith(c, m)
              Behaviors.same
            case SpawnSession(replyTo, sessionHandler) =>
              val session = context.spawnAnonymous[String](Behaviors.receiveMessage { message =>
                sessionHandler ! message
                Behaviors.same
              })
              replyTo ! session
              Behaviors.same
            case KillSession(session, replyTo) =>
              context.stop(session)
              replyTo ! Done
              Behaviors.same
            case CreateMessageAdapter(messageClass, f, replyTo) =>
              val adaptor = context.messageAdapter(f)(ClassTag(messageClass))
              replyTo.foreach(_ ! adaptor.unsafeUpcast)
              Behaviors.same
            case Log(what) =>
              context.log.info(what)
              Behaviors.same
            case RegisterWithReceptionist(name: String) =>
              context.system.receptionist ! Receptionist.Register(ServiceKey[Command](name), context.self)
              Behaviors.same
            case ScheduleCommand(key, delay, mode, cmd) =>
              mode match {
                case Effect.TimerScheduled.SingleMode     => timers.startSingleTimer(key, cmd, delay)
                case Effect.TimerScheduled.FixedDelayMode => timers.startTimerWithFixedDelay(key, cmd, delay, delay)
                case m: Effect.TimerScheduled.FixedDelayModeWithInitialDelay =>
                  timers.startTimerWithFixedDelay(key, cmd, m.initialDelay, delay)
                case Effect.TimerScheduled.FixedRateMode => timers.startTimerAtFixedRate(key, cmd, delay, delay)
                case m: Effect.TimerScheduled.FixedRateModeWithInitialDelay =>
                  timers.startTimerAtFixedRate(key, cmd, m.initialDelay, delay)
              }
              Behaviors.same
            case CancelScheduleCommand(key) =>
              timers.cancel(key)
              Behaviors.same
            case IsTimerActive(key, replyTo) =>
              replyTo ! timers.isTimerActive(key)
              Behaviors.same
            case unexpected =>
              throw new RuntimeException(s"Unexpected command: $unexpected")
          }
        }
        .receiveSignal {
          case (context, Terminated(_)) =>
            context.log.debug("Terminated")
            Behaviors.same
        }
    }
  }

  object Child {

    sealed trait Action

    val initial: Behavior[Action] = Behaviors.receive[Action] { (_, message) =>
      message match {
        case _ =>
          Behaviors.empty
      }
    }

  }

}

class BehaviorTestKitSpec extends AnyWordSpec with Matchers with LogCapturing {

  private val props = Props.empty.withDispatcherFromConfig("cat")

  private val testKitAddress = Address("akka", "StubbedActorContext")

  "BehaviorTestKit" must {

    "allow assertions on effect type" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnAnonymous(1))
      val spawnAnonymous = testkit.expectEffectType[Effect.SpawnedAnonymous[_]]
      spawnAnonymous.props should ===(Props.empty)
    }

    "allow expecting NoEffects by type" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.expectEffectType[NoEffects]
    }

    "allow expecting NoEffects" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.expectEffect(NoEffects)
    }

    "return if effects have taken place" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.hasEffects() should ===(false)
      testkit.run(SpawnAnonymous(1))
      testkit.hasEffects() should ===(true)
    }

    "allow assertions using partial functions - no match" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnChildren(1))
      val ae = intercept[AssertionError] {
        testkit.expectEffectPF {
          case SpawnedAnonymous(_, _) =>
        }
      }
      ae.getMessage should startWith("expected matching effect but got: ")
    }

    "allow assertions using partial functions - match" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnChildren(1))
      val childName = testkit.expectEffectPF {
        case Spawned(_, name, _) => name
      }
      childName should ===("child0")
    }

    "allow assertions using partial functions - match on NoEffect" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      val hasEffects = testkit.expectEffectPF {
        case NoEffects => false
      }
      hasEffects should ===(false)
    }

    "allow retrieving log messages issued by behavior" in {
      val what = "Hello!"
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(Log(what))
      testkit.logEntries() shouldBe Seq(CapturedLogEvent(Level.INFO, what))
    }

    "allow clearing log messages issued by behavior" in {
      val what = "Hi!"
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(Log(what))
      testkit.logEntries() shouldBe Seq(CapturedLogEvent(Level.INFO, what))
      testkit.clearLog()
      testkit.logEntries() shouldBe Seq.empty
    }

    "return default address" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.context.asScala.system.address shouldBe testKitAddress
    }
  }

  "BehaviorTestKit's spawn" must {
    "create children when no props specified" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnChildren(2))
      val effects = testkit.retrieveAllEffects()
      effects should contain.only(Spawned(Child.initial, "child0"), Spawned(Child.initial, "child1", Props.empty))
    }

    "create children when props specified and record effects" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnChildrenWithProps(2, props))
      val effects = testkit.retrieveAllEffects()
      effects should contain.only(Spawned(Child.initial, "child0", props), Spawned(Child.initial, "child1", props))
    }
  }

  "BehaviorTestkit's spawnAnonymous" must {
    "create children when no props specified and record effects" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnAnonymous(2))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, Props.empty), SpawnedAnonymous(Child.initial, Props.empty))
    }

    "create children when props specified and record effects" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)

      testkit.run(SpawnAnonymousWithProps(2, props))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymous(Child.initial, props), SpawnedAnonymous(Child.initial, props))
    }
  }

  "BehaviorTestkit's spawnMessageAdapter" must {
    "create adapters without name and record effects" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnAdapter)
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAnonymousAdapter())
    }

    "create adapters with name and record effects" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnAdapterWithName("adapter"))
      val effects = testkit.retrieveAllEffects()
      effects shouldBe Seq(SpawnedAdapter("adapter"))
    }
  }

  "BehaviorTestkit's messageAdapter" must {
    "create message adapters and record effects" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(CreateMessageAdapter(classOf[String], (_: String) => SpawnChildren(1)))
      testkit.expectEffectType[MessageAdapter[String, Command]]
    }

    "create message adapter and receive messages via the newly created adapter" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      val replyTo = TestInbox[ActorRef[Int]]("replyTo")
      testkit.run(CreateMessageAdapter(classOf[Int], SpawnChildren.apply, Some(replyTo.ref)))
      testkit.expectEffectType[MessageAdapter[String, Command]]
      val adaptorRef = replyTo.receiveMessage()
      adaptorRef ! 2
      testkit.selfInbox().hasMessages should be(true)
      testkit.runOne()
      testkit.expectEffectPF {
        case Spawned(_, childName, _) => childName should equal("child0")
      }
      testkit.expectEffectPF {
        case Spawned(_, childName, _) => childName should equal("child1")
      }
    }
  }

  "BehaviorTestkit's run".can {
    "run behaviors with messages without canonicalization" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(SpawnAdapterWithName("adapter"))
      testkit.currentBehavior should not be Behaviors.same
      testkit.returnedBehavior shouldBe Behaviors.same
    }
  }

  "BehaviorTestKit's signal" must {
    "not throw thread validation errors when context log is accessed" in {
      val other = TestInbox[String]()
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      noException should be thrownBy {
        testkit.signal(Terminated(other.ref))
      }
    }
  }

  "BehaviorTestKit’s watch" must {
    "record effects for watching and unwatching" in {
      val testkit = BehaviorTestKit(Parent.init)
      testkit.run(SpawnAndWatchUnwatch("hello"))
      val child = testkit.childInbox("hello").ref
      testkit.retrieveAllEffects() should be(
        Seq(Effects.spawned(Child.initial, "hello", Props.empty), Effects.watched(child), Effects.unwatched(child)))
    }

    "record effects for watchWith" in {
      val testkit = BehaviorTestKit(Parent.init)
      val spawnAndWatchWithMsg = SpawnAndWatchWith("hello")
      testkit.run(spawnAndWatchWithMsg)
      val child = testkit.childInbox("hello").ref
      testkit.retrieveAllEffects() should be(
        Seq(Effects.spawned(Child.initial, "hello", Props.empty), Effects.watchedWith(child, spawnAndWatchWithMsg)))
    }
  }

  "BehaviorTestKit’s child actor support" must {
    "allow retrieving and killing" in {
      val testkit = BehaviorTestKit(Parent.init)
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

      d.receiveAll() shouldBe Seq(Done)
      testkit.expectEffectType[Stopped]
    }

    "stop and restart a named child" in {
      val testkit = BehaviorTestKit(Parent.init)
      testkit.run(SpawnChild)
      val child = testkit.expectEffectType[Spawned[String]]

      testkit.run(StopChild(child.ref))
      testkit.expectEffect(Stopped(child.childName))

      testkit.run(SpawnChild)
      val newChild = testkit.expectEffectType[Spawned[_]]
      child.childName shouldBe newChild.childName
    }
  }
  "BehaviorTestKit's receptionist support" must {
    "register with receptionist without crash" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(RegisterWithReceptionist("aladin"))
    }
    "capture Register message in receptionist's inbox" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.receptionistInbox().hasMessages should equal(false)
      testkit.run(RegisterWithReceptionist("aladin"))
      testkit.receptionistInbox().hasMessages should equal(true)
      testkit.receptionistInbox().expectMessage(Receptionist.Register(ServiceKey[Command]("aladin"), testkit.ref))
      testkit.receptionistInbox().hasMessages should equal(false)
    }
  }

  "timer support" must {
    "schedule and cancel timers" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      val t = TestInbox[Boolean]()
      testkit.run(IsTimerActive("abc", t.ref))
      t.receiveMessage() shouldBe false
      testkit.run(ScheduleCommand("abc", 42.seconds, Effect.TimerScheduled.SingleMode, SpawnChild))
      testkit.expectEffectPF {
        case Effect.TimerScheduled(
            "abc",
            SpawnChild,
            finiteDuration,
            Effect.TimerScheduled.SingleMode,
            false /*not overriding*/ ) =>
          finiteDuration should equal(42.seconds)
      }
      testkit.run(IsTimerActive("abc", t.ref))
      t.receiveMessage() shouldBe true
      testkit.run(CancelScheduleCommand("abc"))
      testkit.expectEffectPF {
        case Effect.TimerCancelled(key) =>
          key should equal("abc")
      }
      testkit.run(IsTimerActive("abc", t.ref))
      t.receiveMessage() shouldBe false
    }

    "schedule and fire timers" in {
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(ScheduleCommand("abc", 42.seconds, Effect.TimerScheduled.SingleMode, SpawnChild))
      val send = testkit.expectEffectPF {
        case e @ Effect.TimerScheduled(
              "abc",
              SpawnChild,
              finiteDuration,
              Effect.TimerScheduled.SingleMode,
              false /*not overriding*/ ) =>
          finiteDuration should equal(42.seconds)
          e.send
      }
      send()
      testkit.runOne()
      testkit.expectEffectPF {
        case Effect.Spawned(_, "child", _) =>
      }
      //no effect since the timer's mode was single, hence removed after fired
      send()
      testkit.selfInbox().hasMessages should be(false)
    }

    "schedule and fire timers multiple times" in {
      val delay = 42.seconds
      val testkit = BehaviorTestKit[Parent.Command](Parent.init)
      testkit.run(ScheduleCommand("abc", delay, Effect.TimerScheduled.FixedRateMode, SpawnChild))
      val send = testkit.expectEffectPF {
        case e @ Effect.TimerScheduled(
              "abc",
              SpawnChild,
              finiteDuration,
              Effect.TimerScheduled.FixedRateModeWithInitialDelay(`delay`),
              false /*not overriding*/ ) =>
          finiteDuration should equal(delay)
          e.send
      }
      send()
      testkit.runOne()
      val child: ActorRef[String] = testkit.expectEffectPF {
        case spawned @ Effect.Spawned(_, "child", _) => spawned.asInstanceOf[Effect.Spawned[String]].ref
      }

      testkit.run(StopChild(child))
      testkit.expectEffect {
        Effect.Stopped("child")
      }
      //when scheduling with fixed rate the timer remains scheduled
      send()
      testkit.runOne()
      testkit.expectEffectPF {
        case Effect.Spawned(_, "child", _) =>
      }

      testkit.run(CancelScheduleCommand("abc"))
      testkit.expectEffect(Effect.TimerCancelled("abc"))
    }
  }
}
