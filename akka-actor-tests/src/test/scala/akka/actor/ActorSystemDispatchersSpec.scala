/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.actor.setup.ActorSystemSetup
import akka.dispatch.ExecutionContexts
import akka.testkit.{ AkkaSpec, ImplicitSender, TestActors, TestProbe }
import com.typesafe.config.ConfigFactory

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object ActorSystemDispatchersSpec {

  class SnitchingExecutionContext(testActor: ActorRef, underlying: ExecutionContext) extends ExecutionContext {

    def execute(runnable: Runnable): Unit = {
      testActor ! "called"
      underlying.execute(runnable)
    }

    def reportFailure(t: Throwable): Unit = {
      testActor ! "failed"
      underlying.reportFailure(t)
    }
  }

}

class ActorSystemDispatchersSpec extends AkkaSpec with ImplicitSender {

  import ActorSystemDispatchersSpec._

  "The ActorSystem" must {

    "work with a passed in ExecutionContext" in {
      val ecProbe = TestProbe()
      val ec = new SnitchingExecutionContext(ecProbe.ref, ExecutionContexts.global())

      val system2 = ActorSystem(name = "ActorSystemDispatchersSpec-passed-in-ec", defaultExecutionContext = Some(ec))

      try {
        val ref = system2.actorOf(Props(new Actor {
          def receive = {
            case "ping" => sender() ! "pong"
          }
        }))

        val probe = TestProbe()

        ref.tell("ping", probe.ref)

        ecProbe.expectMsg(1.second, "called")
        probe.expectMsg(1.second, "pong")
      } finally {
        shutdown(system2)
      }
    }

    "not use passed in ExecutionContext if executor is configured" in {
      val ecProbe = TestProbe()
      val ec = new SnitchingExecutionContext(ecProbe.ref, ExecutionContexts.global())

      val config = ConfigFactory.parseString("akka.actor.default-dispatcher.executor = \"fork-join-executor\"")
      val system2 = ActorSystem(
        name = "ActorSystemDispatchersSpec-ec-configured",
        config = Some(config),
        defaultExecutionContext = Some(ec))

      try {
        val ref = system2.actorOf(TestActors.echoActorProps)
        val probe = TestProbe()

        ref.tell("ping", probe.ref)

        ecProbe.expectNoMessage(200.millis)
        probe.expectMsg(1.second, "ping")
      } finally {
        shutdown(system2)
      }
    }

    def userGuardianDispatcher(system: ActorSystem): String = {
      val impl = system.asInstanceOf[ActorSystemImpl]
      impl.guardian.asInstanceOf[ActorRefWithCell].underlying.asInstanceOf[ActorCell].dispatcher.id
    }

    "provide a single place to override the internal dispatcher" in {
      val sys = ActorSystem(
        "ActorSystemDispatchersSpec-override-internal-disp",
        ConfigFactory.parseString("""
            akka.actor.internal-dispatcher = akka.actor.default-dispatcher
          """))
      try {
        // that the user guardian runs on the overriden dispatcher instead of internal
        // isn't really a guarantee any internal actor has been made running on the right one
        // but it's better than no test coverage at all
        userGuardianDispatcher(sys) should ===("akka.actor.default-dispatcher")
      } finally {
        shutdown(sys)
      }
    }

    "provide internal dispatcher instance through BootstrapSetup" in {
      pending // Not sure how important this is and quite tricky to implement, deferring
      // would be something like
      // ActorSystem("name", BootstrapSetup().withCustomInternalExecutionContext(myEc))
    }

    "use an internal dispatcher for the guardian by default" in {
      userGuardianDispatcher(system) should ===("akka.actor.default-internal-dispatcher")
    }

    "use the default dispatcher by a user provided user guardian" in {
      val sys = new ActorSystemImpl(
        "ActorSystemDispatchersSpec-custom-user-guardian",
        ConfigFactory.defaultReference(),
        getClass.getClassLoader,
        None,
        Some(Props.empty),
        ActorSystemSetup.empty)
      sys.start()
      try {
        userGuardianDispatcher(sys) should ===("akka.actor.default-dispatcher")
      } finally shutdown(sys)
    }

  }

}
