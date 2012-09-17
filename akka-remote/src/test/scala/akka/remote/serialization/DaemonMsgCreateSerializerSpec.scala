/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import language.postfixOps

import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import akka.testkit.AkkaSpec
import akka.actor.{ Actor, Address, Props, Deploy, OneForOneStrategy, SupervisorStrategy, FromClassCreator }
import akka.remote.{ DaemonMsgCreate, RemoteScope }
import akka.routing.{ RoundRobinRouter, FromConfig }
import scala.concurrent.util.duration._

object DaemonMsgCreateSerializerSpec {
  class MyActor extends Actor {
    def receive = {
      case _ ⇒
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class DaemonMsgCreateSerializerSpec extends AkkaSpec {

  import DaemonMsgCreateSerializerSpec._
  val ser = SerializationExtension(system)
  val supervisor = system.actorOf(Props[MyActor], "supervisor")

  "Serialization" must {

    "resolve DaemonMsgCreateSerializer" in {
      ser.serializerFor(classOf[DaemonMsgCreate]).getClass must be(classOf[DaemonMsgCreateSerializer])
    }

    "serialize and de-serialize DaemonMsgCreate with FromClassCreator" in {
      verifySerialization {
        DaemonMsgCreate(
          props = Props[MyActor],
          deploy = Deploy(),
          path = "foo",
          supervisor = supervisor)
      }
    }

    "serialize and de-serialize DaemonMsgCreate with function creator" in {
      verifySerialization {
        DaemonMsgCreate(
          props = Props.empty.withCreator(new MyActor),
          deploy = Deploy(),
          path = "foo",
          supervisor = supervisor)
      }
    }

    "serialize and de-serialize DaemonMsgCreate with Deploy and RouterConfig" in {
      verifySerialization {
        // Duration.Inf doesn't equal Duration.Inf, so we use another for test
        val supervisorStrategy = OneForOneStrategy(3, 10 seconds) {
          case _ ⇒ SupervisorStrategy.Escalate
        }
        val deploy1 = Deploy(
          path = "path1",
          config = ConfigFactory.parseString("a=1"),
          routerConfig = RoundRobinRouter(nrOfInstances = 5, supervisorStrategy = supervisorStrategy),
          scope = RemoteScope(Address("akka", "Test", "host1", 1921)))
        val deploy2 = Deploy(
          path = "path2",
          config = ConfigFactory.parseString("a=2"),
          routerConfig = FromConfig,
          scope = RemoteScope(Address("akka", "Test", "host2", 1922)))
        DaemonMsgCreate(
          props = Props[MyActor].withDispatcher("my-disp").withDeploy(deploy1),
          deploy = deploy2,
          path = "foo",
          supervisor = supervisor)
      }
    }

    def verifySerialization(msg: DaemonMsgCreate): Unit = {
      assertDaemonMsgCreate(msg, ser.deserialize(ser.serialize(msg).get, classOf[DaemonMsgCreate]).get.asInstanceOf[DaemonMsgCreate])
    }

    def assertDaemonMsgCreate(expected: DaemonMsgCreate, got: DaemonMsgCreate): Unit = {
      // can't compare props.creator when function
      if (expected.props.creator.isInstanceOf[FromClassCreator])
        assert(got.props.creator === expected.props.creator)
      assert(got.props.dispatcher === expected.props.dispatcher)
      assert(got.props.dispatcher === expected.props.dispatcher)
      assert(got.props.routerConfig === expected.props.routerConfig)
      assert(got.props.deploy === expected.props.deploy)
      assert(got.deploy === expected.deploy)
      assert(got.path === expected.path)
      assert(got.supervisor === expected.supervisor)
    }

  }
}

