/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import language.postfixOps

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.reflect.ClassTag
import akka.dispatch._
import akka.testkit.AkkaSpec
import akka.testkit.ImplicitSender
import scala.collection.JavaConverters._
import com.typesafe.config.ConfigFactory
import akka.actor.Actor
import akka.actor.Props
import scala.concurrent.duration._

object DispatchersSpec {
  val config = """
    myapp {
      mydispatcher {
        throughput = 17
      }
      thread-pool-dispatcher {
        executor = thread-pool-executor
      }
      my-pinned-dispatcher {
        executor = thread-pool-executor
        type = PinnedDispatcher
      }
      balancing-dispatcher {
        type = BalancingDispatcher
      }
    }
    """

  class ThreadNameEcho extends Actor {
    def receive = {
      case _ ⇒ sender ! Thread.currentThread.getName
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DispatchersSpec extends AkkaSpec(DispatchersSpec.config) with ImplicitSender {
  import DispatchersSpec._
  val df = system.dispatchers
  import df._

  val tipe = "type"
  val keepalivems = "keep-alive-time"
  val corepoolsizefactor = "core-pool-size-factor"
  val maxpoolsizefactor = "max-pool-size-factor"
  val allowcoretimeout = "allow-core-timeout"
  val throughput = "throughput"
  val id = "id"

  def instance(dispatcher: MessageDispatcher): (MessageDispatcher) ⇒ Boolean = _ == dispatcher
  def ofType[T <: MessageDispatcher: ClassTag]: (MessageDispatcher) ⇒ Boolean = _.getClass == implicitly[ClassTag[T]].runtimeClass

  def typesAndValidators: Map[String, (MessageDispatcher) ⇒ Boolean] = Map(
    "BalancingDispatcher" -> ofType[BalancingDispatcher],
    "PinnedDispatcher" -> ofType[PinnedDispatcher],
    "Dispatcher" -> ofType[Dispatcher])

  def validTypes = typesAndValidators.keys.toList

  val defaultDispatcherConfig = settings.config.getConfig("akka.actor.default-dispatcher")

  lazy val allDispatchers: Map[String, MessageDispatcher] = {
    validTypes.map(t ⇒ (t, from(ConfigFactory.parseMap(Map(tipe -> t, id -> t).asJava).
      withFallback(defaultDispatcherConfig)))).toMap
  }

  "Dispatchers" must {

    "use defined properties" in {
      val dispatcher = lookup("myapp.mydispatcher")
      dispatcher.throughput must be(17)
    }

    "use specific id" in {
      val dispatcher = lookup("myapp.mydispatcher")
      dispatcher.id must be("myapp.mydispatcher")
    }

    "use default dispatcher for missing config" in {
      val dispatcher = lookup("myapp.other-dispatcher")
      dispatcher must be === defaultGlobalDispatcher
    }

    "have only one default dispatcher" in {
      val dispatcher = lookup(Dispatchers.DefaultDispatcherId)
      dispatcher must be === defaultGlobalDispatcher
      dispatcher must be === system.dispatcher
    }

    "throw IllegalArgumentException if type does not exist" in {
      intercept[IllegalArgumentException] {
        from(ConfigFactory.parseMap(Map(tipe -> "typedoesntexist", id -> "invalid-dispatcher").asJava).
          withFallback(defaultDispatcherConfig))
      }
    }

    "get the correct types of dispatchers" in {
      //All created/obtained dispatchers are of the expeced type/instance
      assert(typesAndValidators.forall(tuple ⇒ tuple._2(allDispatchers(tuple._1))))
    }

    "provide lookup of dispatchers by id" in {
      val d1 = lookup("myapp.mydispatcher")
      val d2 = lookup("myapp.mydispatcher")
      d1 must be === d2
    }

    "include system name and dispatcher id in thread names for fork-join-executor" in {
      system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.mydispatcher")) ! "what's the name?"
      val Expected = "(DispatchersSpec-myapp.mydispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    "include system name and dispatcher id in thread names for thread-pool-executor" in {
      system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.thread-pool-dispatcher")) ! "what's the name?"
      val Expected = "(DispatchersSpec-myapp.thread-pool-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    "include system name and dispatcher id in thread names for default-dispatcher" in {
      system.actorOf(Props[ThreadNameEcho]) ! "what's the name?"
      val Expected = "(DispatchersSpec-akka.actor.default-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    "include system name and dispatcher id in thread names for pinned dispatcher" in {
      system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.my-pinned-dispatcher")) ! "what's the name?"
      val Expected = "(DispatchersSpec-myapp.my-pinned-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

    "include system name and dispatcher id in thread names for balancing dispatcher" in {
      system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.balancing-dispatcher")) ! "what's the name?"
      val Expected = "(DispatchersSpec-myapp.balancing-dispatcher-[1-9][0-9]*)".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

  }

}
