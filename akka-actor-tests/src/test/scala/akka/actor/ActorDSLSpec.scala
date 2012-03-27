/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.testkit.DefaultTimeout
import akka.testkit.TestEvent._

import akka.dispatch.{ Await, MessageQueueAppendFailedException, BoundedDequeBasedMailbox }
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
      @volatile var in1 = false
      @volatile var in2 = false

      val actor = actorOf { context ⇒
        {
          case m ⇒
            in1 = true
            m must be("hello")
            context.become {
              case m ⇒
                in2 = true
                m must be("hello")
            }
        }
      }

      actor ! "hello"
      actor ! "hello"

      Thread.sleep(200)
      in1 must be(true)
      in2 must be(true)

      actor ! PoisonPill
    }
  }

  "An actor created with the DSL" must {
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
        case m @ "hello" ⇒
          m must be("hello")
          receive {
            case next @ "world" ⇒
              next must be("world")
          }
      }
    }
  }

  "A thread actor" must {
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

      var i = 0
      while (i < 10000) {
        i += 1
        receive {
          case m ⇒
            m must be("hello")
        }
      }
    }
  }

  /*
  "A thread actor" must {
    "support the ask pattern" in {
      val tA = self
      val actor = actorOf { context ⇒
        {
          case m ⇒
            // send message and wait for response
            println("context.sender: " + context.sender)
            val f = context.sender ? "hello"
            val res = Await.result(f, 1 seconds)
            res must be("response")
        }
      }

      actor.tell("whatever", tA)

      receive {
        case resp ⇒
          println("tA received " + resp)
          actor ! "response"
      }
    }
  }
  */

}
