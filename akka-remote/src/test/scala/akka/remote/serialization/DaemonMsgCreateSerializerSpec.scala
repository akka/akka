/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.actor.{
  Actor,
  ActorRef,
  Address,
  Deploy,
  ExtendedActorSystem,
  OneForOneStrategy,
  Props,
  SupervisorStrategy
}
import akka.remote.{ DaemonMsgCreate, RemoteScope }
import akka.routing.{ FromConfig, RoundRobinPool }
import akka.serialization.{ Serialization, SerializationExtension }
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import scala.language.postfixOps

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

trait SerializationVerification { self: AkkaSpec =>

  def ser: Serialization

  def verifySerialization(msg: DaemonMsgCreate): Unit = {
    assertDaemonMsgCreate(
      msg,
      ser.deserialize(ser.serialize(msg).get, classOf[DaemonMsgCreate]).get.asInstanceOf[DaemonMsgCreate])
  }

  def assertDaemonMsgCreate(expected: DaemonMsgCreate, got: DaemonMsgCreate): Unit = {
    // can't compare props.creator when function
    got.props.clazz should ===(expected.props.clazz)
    got.props.args.length should ===(expected.props.args.length)
    got.props.args.zip(expected.props.args).foreach {
      case (g, e) =>
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

class DaemonMsgCreateSerializerSpec extends AkkaSpec with SerializationVerification {

  import DaemonMsgCreateSerializerSpec._
  val ser = SerializationExtension(system)
  val supervisor = system.actorOf(Props[MyActor], "supervisor")

  "Serialization" must {

    "resolve DaemonMsgCreateSerializer" in {
      ser.serializerFor(classOf[DaemonMsgCreate]).getClass should ===(classOf[DaemonMsgCreateSerializer])
    }

    "serialize and de-serialize DaemonMsgCreate with FromClassCreator" in {
      verifySerialization {
        DaemonMsgCreate(props = Props[MyActor], deploy = Deploy(), path = "foo", supervisor = supervisor)
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
        DaemonMsgCreate(props = Props(new MyActor), deploy = Deploy(), path = "foo", supervisor = supervisor)
      }
    }

    "serialize and de-serialize DaemonMsgCreate with FromClassCreator, with function parameters for Props" in {
      verifySerialization {
        DaemonMsgCreate(
          props = Props(classOf[MyActorWithFunParam], (i: Int) => i + 1),
          deploy = Deploy(),
          path = "foo",
          supervisor = supervisor)
      }
    }

    "deserialize the old wire format with just class and field for props parameters (if possible)" in {
      val system = ActorSystem(
        "DaemonMsgCreateSerializer-old-wire-format",
        ConfigFactory.parseString("""
          # in 2.4 this is off by default, but in 2.5+ its on so we wouldn't
          # get the right set of serializers (and since the old wire protocol doesn't
          # contain serializer ids that will go unnoticed with unpleasant consequences)
          akka.actor.enable-additional-serialization-bindings = off
        """))

      try {
        val serializer = new DaemonMsgCreateSerializer(system.asInstanceOf[ExtendedActorSystem])

        // the oldSnapshot was created with the version of DemonMsgCreateSerializer in Akka 2.4.17. See issue #22224.
        // It was created with:
        /*
      import org.apache.commons.codec.binary.Hex.encodeHex
      val bytes = serializer.toBinary(
        DaemonMsgCreate(Props(classOf[MyActorWithParam], "a string"), Deploy.local, "/user/test", system.actorFor("/user")))
      println(String.valueOf(encodeHex(bytes)))
         */

        val oldBytesHex =
          "0a7112020a001a48616b6b612e72656d6f74652e73657269616c697a617" +
          "4696f6e2e4461656d6f6e4d736743726561746553657269616c697a6572" +
          "53706563244d794163746f7257697468506172616d220faced000574000" +
          "86120737472696e672a106a6176612e6c616e672e537472696e67122f0a" +
          "00222baced000573720016616b6b612e6163746f722e4c6f63616c53636" +
          "f706524000000000000000102000078701a0a2f757365722f7465737422" +
          "2b0a29616b6b613a2f2f4461656d6f6e4d7367437265617465536572696" +
          "16c697a6572537065632f75736572"

        import org.apache.commons.codec.binary.Hex.decodeHex
        val oldBytes = decodeHex(oldBytesHex.toCharArray)
        val result = serializer.fromBinary(oldBytes, classOf[DaemonMsgCreate])

        result match {
          case dmc: DaemonMsgCreate =>
            dmc.props.args should ===(Seq("a string": Any))
        }
      } finally {
        TestKit.shutdownActorSystem(system)
      }
    }

    "serialize and de-serialize DaemonMsgCreate with Deploy and RouterConfig" in {
      verifySerialization {
        // Duration.Inf doesn't equal Duration.Inf, so we use another for test
        // we don't serialize the supervisor strategy, but always fallback to default
        val supervisorStrategy = SupervisorStrategy.defaultStrategy
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

  }
}

class DaemonMsgCreateSerializerNoJavaSerializationSpec extends AkkaSpec("""
   akka.actor.allow-java-serialization=off
   akka.actor.serialize-messages=off
   akka.actor.serialize-creators=off
  """) with SerializationVerification {

  import DaemonMsgCreateSerializerSpec.MyActor

  val supervisor = system.actorOf(Props[MyActor], "supervisor")
  val ser = SerializationExtension(system)

  "serialize and de-serialize DaemonMsgCreate with Deploy and RouterConfig" in {
    verifySerialization {
      // Duration.Inf doesn't equal Duration.Inf, so we use another for test
      val supervisorStrategy = OneForOneStrategy(3, 10 seconds) {
        case _ => SupervisorStrategy.Escalate
      }

      val deploy1 = Deploy(
        path = "path1",
        config = ConfigFactory.parseString("a=1"),
        // a whole can of worms: routerConfig = RoundRobinPool(nrOfInstances = 5, supervisorStrategy = supervisorStrategy),
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

}
