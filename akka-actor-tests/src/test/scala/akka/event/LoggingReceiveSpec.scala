/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event

import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import akka.util.duration._
import akka.testkit._
import org.scalatest.WordSpec
import akka.util.Duration
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._
import java.util.Properties
import akka.actor._

object LoggingReceiveSpec {
  class TestLogActor extends Actor {
    override val supervisorStrategy =
      OneForOneStrategy(maxNrOfRetries = 5, withinTimeRange = 5 seconds)(List(classOf[Throwable]))
    def receive = { case _ ⇒ }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class LoggingReceiveSpec extends WordSpec with BeforeAndAfterEach with BeforeAndAfterAll {

  import LoggingReceiveSpec._
  val config = ConfigFactory.parseMap(Map("akka.logLevel" -> "DEBUG").asJava).withFallback(AkkaSpec.testConf)
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

  override def afterAll {
    appLogging.shutdown()
    appAuto.shutdown()
    appLifecycle.shutdown()
  }

  "A LoggingReceive" must {

    "decorate a Receive" in {
      new TestKit(appLogging) {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        val a = system.actorOf(Props(new Actor {
          def receive = new LoggingReceive(Some("funky"), {
            case null ⇒
          })
        }))
        a ! "hallo"
        expectMsg(1 second, Logging.Debug("funky", classOf[DummyClassForStringSources], "received unhandled message hallo"))
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
            case x ⇒ sender ! "x"
          }
        })

        val name = actor.path.toString
        actor ! "buh"
        within(1 second) {
          expectMsg(Logging.Debug(actor.path.toString, actor.underlyingActor.getClass, "received handled message buh"))
          expectMsg("x")
        }

        actor ! "becomenull"

        within(500 millis) {
          actor ! "bah"
          val deadletters = system.deadLetters
          expectMsgPF() {
            case UnhandledMessage("bah", testActor, `actor`) ⇒ true
          }
        }
      }
    }

    "not duplicate logging" in {
      new TestKit(appLogging) with ImplicitSender {
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        val actor = TestActorRef(new Actor {
          def receive = LoggingReceive(LoggingReceive {
            case _ ⇒ sender ! "x"
          })
        })
        actor ! "buh"
        within(1 second) {
          expectMsg(Logging.Debug(actor.path.toString, actor.underlyingActor.getClass, "received handled message buh"))
          expectMsg("x")
        }
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
        expectMsgPF() {
          case Logging.Debug(`name`, _, msg: String) if msg startsWith "received AutoReceiveMessage Envelope(PoisonPill" ⇒ true
        }
        awaitCond(actor.isTerminated, 100 millis)
      }
    }

    "log LifeCycle changes if requested" in {
      new TestKit(appLifecycle) {
        val impl = system.asInstanceOf[ActorSystemImpl]
        val sys = impl.systemGuardian.path.toString
        ignoreMute(this)
        ignoreMsg {
          case Logging.Debug(`sys`, _, _) ⇒ true
        }
        system.eventStream.subscribe(testActor, classOf[Logging.Debug])
        system.eventStream.subscribe(testActor, classOf[Logging.Error])
        within(3 seconds) {
          val lifecycleGuardian = appLifecycle.asInstanceOf[ActorSystemImpl].guardian
          val lname = lifecycleGuardian.path.toString
          val supervisor = TestActorRef[TestLogActor](Props[TestLogActor])
          val sname = supervisor.path.toString
          val sclass = classOf[TestLogActor]

          val supervisorSet = receiveWhile(messages = 2) {
            case Logging.Debug(`lname`, _, msg: String) if msg startsWith "now supervising" ⇒ 1
            case Logging.Debug(`sname`, `sclass`, msg: String) if msg startsWith "started"  ⇒ 2
          }.toSet
          expectNoMsg(Duration.Zero)
          assert(supervisorSet == Set(1, 2), supervisorSet + " was not Set(1, 2)")

          val actor = TestActorRef[TestLogActor](Props[TestLogActor], supervisor, "none")
          val aname = actor.path.toString
          val aclass = classOf[TestLogActor]

          val set = receiveWhile(messages = 2) {
            case Logging.Debug(`sname`, _, msg: String) if msg startsWith "now supervising" ⇒ 1
            case Logging.Debug(`aname`, `aclass`, msg: String) if msg startsWith "started"  ⇒ 2
          }.toSet
          expectNoMsg(Duration.Zero)
          assert(set == Set(1, 2), set + " was not Set(1, 2)")

          supervisor watch actor
          expectMsgPF(hint = "now monitoring") {
            case Logging.Debug(ref, `sclass`, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("now monitoring")
          }

          supervisor unwatch actor
          expectMsgPF(hint = "stopped monitoring") {
            case Logging.Debug(ref, `sclass`, msg: String) ⇒
              ref == supervisor.underlyingActor && msg.startsWith("stopped monitoring")
          }

          EventFilter[ActorKilledException](occurrences = 1) intercept {
            actor ! Kill
            val set = receiveWhile(messages = 3) {
              case Logging.Error(_: ActorKilledException, `aname`, `aclass`, "Kill") ⇒ 1
              case Logging.Debug(`aname`, `aclass`, "restarting")                    ⇒ 2
              case Logging.Debug(`aname`, `aclass`, "restarted")                     ⇒ 3
            }.toSet
            expectNoMsg(Duration.Zero)
            assert(set == Set(1, 2, 3), set + " was not Set(1, 2, 3)")
          }

          system.stop(supervisor)
          expectMsg(Logging.Debug(sname, `sclass`, "stopping"))
          expectMsg(Logging.Debug(aname, `aclass`, "stopped"))
          expectMsg(Logging.Debug(sname, `sclass`, "stopped"))
        }
      }
    }

  }

}
