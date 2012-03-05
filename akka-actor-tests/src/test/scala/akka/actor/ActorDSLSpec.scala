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
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.BeforeAndAfterEach
import akka.actor.AkkaDSL._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorDSLSpec extends AkkaSpec with DefaultTimeout with BeforeAndAfterEach {
  import ActorWithBoundedStashSpec._

  implicit val sys = system

  override def atStartup {
    system.eventStream.publish(Mute(EventFilter[Exception]("Crashing...")))
  }

  def myProps(creator: ⇒ Actor): Props = Props(creator).withDispatcher("my-dispatcher")

  "An Actor created with the DSL " must {

    "have context, respond and die" in {
      var in1 = false
      var in2 = false

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

  "An Actor created with the DSL " must {

    "be able to react from a regular thread " in {
      val tA = threadSender
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

}