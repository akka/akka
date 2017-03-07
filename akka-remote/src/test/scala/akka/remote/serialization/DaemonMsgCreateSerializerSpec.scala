/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.serialization

import language.postfixOps
import akka.serialization.SerializationExtension
import com.typesafe.config.ConfigFactory
import akka.testkit.AkkaSpec
import akka.actor.{ Actor, ActorRef, Address, Deploy, ExtendedActorSystem, OneForOneStrategy, Props, SupervisorStrategy }
import akka.remote.{ DaemonMsgCreate, RemoteScope }
import akka.routing.{ FromConfig, RoundRobinPool }
import akka.util.ByteString

import scala.concurrent.duration._

object DaemonMsgCreateSerializerSpec {

  trait EmptyActor extends Actor {
    def receive = Actor.emptyBehavior
  }
  class MyActor extends EmptyActor
  class MyActorWithParam(ignore: String) extends EmptyActor
  class MyActorWithFunParam(fun: Function1[Int, Int]) extends EmptyActor
  class ActorWithDummyParameter(javaSerialized: DummyParameter, protoSerialized: ActorRef) extends EmptyActor
}

case class DummyParameter(val inner: String) extends Serializable

class DaemonMsgCreateSerializerSpec extends AkkaSpec {

  import DaemonMsgCreateSerializerSpec._
  val ser = SerializationExtension(system)
  val supervisor = system.actorOf(Props[MyActor], "supervisor")

  "Serialization" must {

    "resolve DaemonMsgCreateSerializer" in {
      ser.serializerFor(classOf[DaemonMsgCreate]).getClass should ===(classOf[DaemonMsgCreateSerializer])
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

    "serialize and de-serialize DaemonMsgCreate with FromClassCreator, with null parameters for Props" in {
      verifySerialization {
        DaemonMsgCreate(
          props = Props(classOf[MyActorWithParam], null),
          deploy = Deploy(),
          path = "foo",
          supervisor = supervisor)
      }
    }

    "serialize and de-serialize DaemonMsgCreate with function creator" in {
      verifySerialization {
        DaemonMsgCreate(
          props = Props(new MyActor),
          deploy = Deploy(),
          path = "foo",
          supervisor = supervisor)
      }
    }

    "serialize and de-serialize DaemonMsgCreate with FromClassCreator, with function parameters for Props" in {
      verifySerialization {
        DaemonMsgCreate(
          props = Props(classOf[MyActorWithFunParam], (i: Int) ⇒ i + 1),
          deploy = Deploy(),
          path = "foo",
          supervisor = supervisor)
      }
    }

    "deserialize the old wire format with just class and field for props parameters (if possible)" in {
      val serializer = new DaemonMsgCreateSerializer(system.asInstanceOf[ExtendedActorSystem])

      // the oldSnapshot was created with the version of DemonMsgCreateSerializer in Akka 2.4.17. See issue #22224.
      // It was created with:
      /*
      import org.apache.commons.codec.binary.Hex.encodeHex
      val bytes = serializer.toBinary(
        DaemonMsgCreate(Props(classOf[MyActorWithParam], "a string"), Deploy.local, "/user/test", system.actorFor("/user")))
      println(String.valueOf(encodeHex(bytes)))
      */
      import org.apache.commons.codec.binary.Hex.encodeHex

      println(String.valueOf(encodeHex(SerializationExtension(system).serialize("a string").get)))

      val oldBytesHex =
        "0a6a12020a001a48616b6b612e72656d6f74652e73657269616c697a6174696f" +
          "6e2e4461656d6f6e4d736743726561746553657269616c697a6572537065632" +
          "44d794163746f7257697468506172616d22086120737472696e672a106a6176" +
          "612e6c616e672e537472696e67122f0a00222baced000573720016616b6b612" +
          "e6163746f722e4c6f63616c53636f706524000000000000000102000078701a" +
          "0a2f757365722f74657374222b0a29616b6b613a2f2f4461656d6f6e4d73674" +
          "3726561746553657269616c697a6572537065632f75736572"

      import org.apache.commons.codec.binary.Hex.decodeHex
      val oldBytes = decodeHex(oldBytesHex.toCharArray)
      val result = serializer.fromBinary(oldBytes, classOf[DaemonMsgCreate])

      result match {
        case dmc: DaemonMsgCreate ⇒
          dmc.props.args should ===(Seq("a string": Any))
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
          routerConfig = RoundRobinPool(nrOfInstances = 5, supervisorStrategy = supervisorStrategy),
          scope = RemoteScope(Address("akka", "Test", "host1", 1921)),
          dispatcher = "mydispatcher")
        val deploy2 = Deploy(
          path = "path2",
          config = ConfigFactory.parseString("a=2"),
          routerConfig = FromConfig,
          scope = RemoteScope(Address("akka", "Test", "host2", 1922)),
          dispatcher = Deploy.NoDispatcherGiven)
        DaemonMsgCreate(
          props = Props[MyActor].withDispatcher("my-disp").withDeploy(deploy1),
          deploy = deploy2,
          path = "foo",
          supervisor = supervisor)
      }
    }

    "allows for mixing serializers with and without manifests for props parameters" in {
      verifySerialization {
        DaemonMsgCreate(
          // parameters should trigger JavaSerializer for the first one and additional protobuf for the second (?)
          props = Props(classOf[ActorWithDummyParameter], new DummyParameter("dummy"), system.deadLetters),
          deploy = Deploy(),
          path = "foo",
          supervisor = supervisor)
      }
    }

    def verifySerialization(msg: DaemonMsgCreate): Unit = {
      assertDaemonMsgCreate(msg, ser.deserialize(ser.serialize(msg).get, classOf[DaemonMsgCreate]).get.asInstanceOf[DaemonMsgCreate])
    }

    def assertDaemonMsgCreate(expected: DaemonMsgCreate, got: DaemonMsgCreate): Unit = {
      // can't compare props.creator when function
      got.props.clazz should ===(expected.props.clazz)
      got.props.args.length should ===(expected.props.args.length)
      got.props.args zip expected.props.args foreach {
        case (g, e) ⇒
          if (e.isInstanceOf[Function0[_]]) ()
          else if (e.isInstanceOf[Function1[_, _]]) ()
          else g should ===(e)
      }
      got.props.deploy should ===(expected.props.deploy)
      got.deploy should ===(expected.deploy)
      got.path should ===(expected.path)
      got.supervisor should ===(expected.supervisor)
    }

  }
}

