/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.event

import language.postfixOps

import org.scalatest.BeforeAndAfterAll
import scala.concurrent.duration._
import akka.testkit._
import org.scalatest.WordSpec
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import akka.actor._

object LoggingReceiveSpec {
  class TestLogActor extends Actor {
    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 5 seconds)(List(classOf[Throwable]))
    def receive = { case _ ⇒ }
  }
}

class LoggingReceiveSpec extends WordSpec with BeforeAndAfterAll {

  import LoggingReceiveSpec._
  val config = ConfigFactory.parseString("""
    akka.loglevel=DEBUG
    akka.actor.serialize-messages = off # debug noise from serialization
    """).withFallback(AkkaSpec.testConf)
  val appLogging = ActorSystem("logging", ConfigFactory.parseMap(Map("akka.actor.debug.receive" -> true).asJava).withFallback(config))
  val appAuto = ActorSystem("autoreceive", ConfigFactory.parseMap(Map("akka.actor.debug.autoreceive" -> true).asJava).withFallback(config))
  val appLifecycle = ActorSystem("lifecycle", ConfigFactory.parseMap(Map("akka.actor.debug.lifecycle" -> true).asJava).withFallback(config))

  val filter = TestEvent.Mute(EventFilter.custom {
    case _: Logging.Debug ⇒ true
    case _: Logging.Info  ⇒ true
    case _                ⇒ false
  })
  appLogging.eventStream.publish(filter)
  appAuto.eventStream.publish(filter)
  appLifecycle.eventStream.publish(filter)

  def ignoreMute(t: TestKit) {
    t.ignoreMsg {
      case (_: TestEvent.Mute | _: TestEvent.UnMute) ⇒ true
    }
  }

  override def afterAll() {
    TestKit.shutdownActorSystem(appLogging)
    TestKit.shutdownActorSystem(appAuto)
    TestKit.shutdownActorSystem(appLifecycle)
  }

  "A LoggingReceive" must {

    "decorate a Receive" in {
      new TestKit(appLogging) {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        system.eventStream.subscribe(testActor, classOf[UnhandledMessage])
        val a = system.actorOf(Props(new Actor {
          def receive = new LoggingReceive(Some("funky"), {
            case null ⇒
          })
        }))
        a ! "hallo"
        expectMsg(1 second, Logging.Debug("funky", classOf[DummyClassForStringSources],
          "received unhandled message hallo from " + system.deadLetters))
        expectMsgType[UnhandledMessage](1 second)
      }
    }

    "be added on Actor if requested" in {
      new TestKit(appLogging) with ImplicitSender {
        ignoreMute(this)
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        system.eventStream.subscribe(testActor, classOf[UnhandledMessage])

        val r: Actor.Receive = {
          case null ⇒
        }

        val actor = TestActorRef(new Actor {
          def switch: Actor.Receive = { case "becomenull" ⇒ context.become(r, false) }
          def receive = switch orElse LoggingReceive {
            case x ⇒ sender() ! "x"
          }
        })

        val name = actor.path.toString
        actor ! "buh"
        expectMsg(Logging.Debug(actor.path.toString, actor.underlyingActor.getClass,
          "received handled message buh from " + self))
        expectMsg("x")

        actor ! "becomenull"

        actor ! "bah"
        expectMsgPF() {
          case UnhandledMessage("bah", testActor, `actor`) ⇒ true
        }
      }
    }

    "not duplicate logging" in {
      new TestKit(appLogging) with ImplicitSender {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        val actor = TestActorRef(new Actor {
          def receive = LoggingReceive(LoggingReceive {
            case _ ⇒ sender() ! "x"
          })
        })
        actor ! "buh"
        expectMsg(Logging.Debug(actor.path.toString, actor.underlyingActor.getClass,
          "received handled message buh from " + self))
        expectMsg("x")
      }
    }

  }

  "An Actor" must {

    "log AutoReceiveMessages if requested" in {
      new TestKit(appAuto) {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        val actor = TestActorRef(new Actor {
          def receive = {
            case _ ⇒
          }
        })
        val name = actor.path.toString
        actor ! PoisonPill
        fishForMessage(hint = "received AutoReceiveMessage Envelope(PoisonPill") {
          case Logging.Debug(`name`, _, msg: String) if msg startsWith "received AutoReceiveMessage Envelope(PoisonPill" ⇒ true
          case _ ⇒ false
        }
        awaitCond(actor.isTerminated)
      }
    }

    "log Supervision events if requested" in {
      new TestKit(appLifecycle) {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        within(3 seconds) {
          val lifecycleGuardian = appLifecycle.asInstanceOf[ActorSystemImpl].guardian
          val lname = lifecycleGuardian.path.toString
          val supervisor = TestActorRef[TestLogActor](Props[TestLogActor])
          val sname = supervisor.path.toString

          fishForMessage(hint = "now supervising") {
            case Logging.Debug(`lname`, _, msg: String) if msg startsWith "now supervising" ⇒ true
            case _ ⇒ false
          }

          TestActorRef[TestLogActor](Props[TestLogActor], supervisor, "none")

          fishForMessage(hint = "now supervising") {
            case Logging.Debug(`sname`, _, msg: String) if msg startsWith "now supervising" ⇒ true
            case _ ⇒ false
          }
        }
      }
    }

    "log DeathWatch events if requested" in {
      new TestKit(appLifecycle) {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        within(3 seconds) {
          val supervisor = TestActorRef[TestLogActor](Props[TestLogActor])
          val sclass = classOf[TestLogActor]
          val actor = TestActorRef[TestLogActor](Props[TestLogActor], supervisor, "none")
          val aname = actor.path.toString

          supervisor watch actor
          fishForMessage(hint = "now watched by") {
            case Logging.Debug(`aname`, `sclass`, msg: String) if msg.startsWith("now watched by") ⇒ true
            case m ⇒ false
          }

          supervisor unwatch actor
          fishForMessage(hint = "no longer watched by") {
            case Logging.Debug(`aname`, `sclass`, msg: String) if msg.startsWith("no longer watched by") ⇒ true
            case _ ⇒ false
          }
        }
      }
    }

    "log LifeCycle events if requested" in {
      new TestKit(appLifecycle) {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        system.eventStream.subscribe(testActor, classOf[Logging.Error])
        within(3 seconds) {
          val supervisor = TestActorRef[TestLogActor](Props[TestLogActor])
          val sname = supervisor.path.toString
          val sclass = classOf[TestLogActor]

          fishForMessage(hint = "started") {
            case Logging.Debug(`sname`, `sclass`, msg: String) if msg startsWith "started" ⇒ true
            case _ ⇒ false
          }

          val actor = TestActorRef[TestLogActor](Props[TestLogActor], supervisor, "none")
          val aname = actor.path.toString
          val aclass = classOf[TestLogActor]

          fishForMessage(hint = "started") {
            case Logging.Debug(`aname`, `aclass`, msg: String) if msg startsWith "started" ⇒ true
            case _ ⇒ false
          }

          EventFilter[ActorKilledException](occurrences = 1) intercept {
            actor ! Kill
            val set = receiveWhile(messages = 3) {
              case Logging.Error(_: ActorKilledException, `aname`, _, "Kill") ⇒ 1
              case Logging.Debug(`aname`, `aclass`, "restarting")             ⇒ 2
              case Logging.Debug(`aname`, `aclass`, "restarted")              ⇒ 3
            }.toSet
            expectNoMsg(Duration.Zero)
            assert(set == Set(1, 2, 3), set + " was not Set(1, 2, 3)")
          }

          system.stop(supervisor)
          expectMsgAllOf(
            Logging.Debug(aname, aclass, "stopped"),
            Logging.Debug(sname, sclass, "stopping"),
            Logging.Debug(sname, sclass, "stopped"))
        }
      }
    }

  }

}
