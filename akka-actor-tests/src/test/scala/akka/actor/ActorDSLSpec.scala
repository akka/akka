/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.testkit.TestEvent._

import akka.dispatch.Await
import akka.pattern.ask
import akka.util.duration._

import akka.actor.ActorSystem.Settings
import akka.actor.ActorDSL._

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach
import org.scalatest.junit.JUnitRunner

@org.junit.runner.RunWith(classOf[JUnitRunner])
class ActorDSLSpec extends AkkaSpec with DefaultTimeout with BeforeAndAfterEach {
  import ActorWithBoundedStashSpec._

  implicit val sys = system

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[Exception]("Crashing...")))
  }

  def myProps(creator: ⇒ Actor): Props = Props(creator).withDispatcher("my-dispatcher")

  "An actor created with the DSL" must {
    "have a working context" in {
      val latch = new TestLatch

      val actor = actorOf { context ⇒
        {
          case m ⇒
            m must be("hello")
            context.become {
              case m ⇒
                m must be("hello")
                latch.open()
            }
        }
      }

      actor ! "hello"
      actor ! "hello"

      Await.ready(latch, 10 seconds)

      actor ! PoisonPill
    }

    "be able to exchange messages with a regular thread " in {
      val tA = self
      val actor = actorOf { context ⇒
        {
          case m ⇒
            m must be("hello")
            context.sender must be(tA)
            context.sender ! m
        }
      }

      actor ! "hello"

      receive {
        case m ⇒
          m must be("hello")
      }
    }
  }

  "A thread actor" must {
    "support nested receives" in {
      val latch = new TestLatch
      val tA = self
      val actor = actorOf { context ⇒
        {
          case m ⇒
            context.sender must be(tA)
            context.sender ! m
        }
      }

      actor ! "hello"
      actor ! "world"

      receive {
        case "hello" ⇒
          receive {
            case "world" ⇒
              latch.open()
          }
      }

      Await.ready(latch, 10 seconds)
    }

    "only receive user messages" in {
      val tA = self
      val actor = actorOf { context ⇒
        {
          case m ⇒
            context.sender must be(tA)
            for (_ ← 0 until 10000)
              context.sender ! m
        }
      }

      actor ! "hello"

      for (_ ← 0 until 10000)
        receive {
          case m ⇒
            m must be("hello")
        }
    }
  }

  "A thread actor" must {
    "support the ask pattern" in {
      val tA = self

      val actor = actorOf { context ⇒
        {
          case m ⇒
            // send message and wait for response
            val f = context.sender ? "hello"
        }
      }

      actor.tell("whatever", tA)

      receive {
        case resp ⇒
          resp must be("hello")
      }
    }
  }

}
