/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor.dispatch

import scala.collection.JavaConverters.mapAsJavaMapConverter
import scala.reflect.ClassTag
import com.typesafe.config.ConfigFactory
import akka.ConfigurationException
import akka.actor.{ Actor, ActorRef, Props }
import akka.dispatch.{ BalancingDispatcher, Dispatcher, Dispatchers, MessageDispatcher, PinnedDispatcher }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.routing.FromConfig
import akka.actor.Identify
import akka.actor.ActorIdentity

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
        type = "akka.dispatch.BalancingDispatcherConfigurator"
      }
    }
    akka.actor.deployment {
      /echo1 {
        dispatcher = myapp.mydispatcher
      }
      /echo2 {
        dispatcher = myapp.mydispatcher
      }
      /pool1 {
        router = random-pool
        nr-of-instances = 3
        pool-dispatcher {
          fork-join-executor.parallelism-min = 3
          fork-join-executor.parallelism-max = 3
        }
      }
    }
    """

  class ThreadNameEcho extends Actor {
    def receive = {
      case _ ⇒ sender() ! Thread.currentThread.getName
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
    "PinnedDispatcher" -> ofType[PinnedDispatcher],
    "Dispatcher" -> ofType[Dispatcher])

  def validTypes = typesAndValidators.keys.toList

  val defaultDispatcherConfig = settings.config.getConfig("akka.actor.default-dispatcher")

  lazy val allDispatchers: Map[String, MessageDispatcher] = {
    validTypes.map(t ⇒ (t, from(ConfigFactory.parseMap(Map(tipe -> t, id -> t).asJava).
      withFallback(defaultDispatcherConfig)))).toMap
  }

  def assertMyDispatcherIsUsed(actor: ActorRef): Unit = {
    actor ! "what's the name?"
    val Expected = "(DispatchersSpec-myapp.mydispatcher-[1-9][0-9]*)".r
    expectMsgPF(remaining) {
      case Expected(x) ⇒
    }
  }

  "Dispatchers" must {

    "use defined properties" in {
      val dispatcher = lookup("myapp.mydispatcher")
      dispatcher.throughput should be(17)
    }

    "use specific id" in {
      val dispatcher = lookup("myapp.mydispatcher")
      dispatcher.id should be("myapp.mydispatcher")
    }

    "complain about missing config" in {
      intercept[ConfigurationException] {
        lookup("myapp.other-dispatcher")
      }
    }

    "have only one default dispatcher" in {
      val dispatcher = lookup(Dispatchers.DefaultDispatcherId)
      dispatcher should be(defaultGlobalDispatcher)
      dispatcher should be(system.dispatcher)
    }

    "throw ConfigurationException if type does not exist" in {
      intercept[ConfigurationException] {
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
      d1 should be(d2)
    }

    "include system name and dispatcher id in thread names for fork-join-executor" in {
      assertMyDispatcherIsUsed(system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.mydispatcher")))
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

    "use dispatcher in deployment config" in {
      assertMyDispatcherIsUsed(system.actorOf(Props[ThreadNameEcho], name = "echo1"))
    }

    "use dispatcher in deployment config, trumps code" in {
      assertMyDispatcherIsUsed(
        system.actorOf(Props[ThreadNameEcho].withDispatcher("myapp.my-pinned-dispatcher"), name = "echo2"))
    }

    "use pool-dispatcher router of deployment config" in {
      val pool = system.actorOf(FromConfig.props(Props[ThreadNameEcho]), name = "pool1")
      pool ! Identify(None)
      val routee = expectMsgType[ActorIdentity].ref.get
      routee ! "what's the name?"
      val Expected = """(DispatchersSpec-akka\.actor\.deployment\./pool1\.pool-dispatcher-[1-9][0-9]*)""".r
      expectMsgPF(remaining) {
        case Expected(x) ⇒
      }
    }

  }

}
