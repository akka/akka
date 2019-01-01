/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream

import akka.Done
import akka.actor.{ Actor, ActorSystem, PoisonPill, Props }
import akka.stream.ActorMaterializerSpec.ActorWithMaterializer
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.{ StreamSpec, TestPublisher }
import akka.testkit.{ ImplicitSender, TestActor, TestProbe }

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Failure, Try }

class ActorMaterializerSpec extends StreamSpec with ImplicitSender {

  "ActorMaterializer" must {

    "report shutdown status properly" in {
      val m = ActorMaterializer.create(system)

      m.isShutdown should ===(false)
      m.shutdown()
      m.isShutdown should ===(true)
    }

    "properly shut down actors associated with it" in {
      val m = ActorMaterializer.create(system)

      val f = Source.fromPublisher(TestPublisher.probe[Int]()(system)).runFold(0)(_ + _)(m)

      m.shutdown()

      an[AbruptTerminationException] should be thrownBy Await.result(f, 3.seconds)
    }

    "refuse materialization after shutdown" in {
      val m = ActorMaterializer.create(system)
      m.shutdown()
      the[IllegalStateException] thrownBy {
        Source(1 to 5).runWith(Sink.ignore)(m)
      } should have message "Trying to materialize stream after materializer has been shutdown"
    }

    "refuse materialization when shutdown while materializing" in {
      val m = ActorMaterializer.create(system)

      the[IllegalStateException] thrownBy {
        Source(1 to 5).mapMaterializedValue { _ ⇒
          // shutdown while materializing
          m.shutdown()
          Thread.sleep(100)
        }.runWith(Sink.ignore)(m)
      } should have message "Materializer shutdown while materializing stream"
    }

    "shut down the supervisor actor it encapsulates" in {
      val m = ActorMaterializer.create(system).asInstanceOf[PhasedFusingActorMaterializer]

      Source.maybe[Any].to(Sink.ignore).run()(m)
      m.supervisor ! StreamSupervisor.GetChildren
      expectMsgType[StreamSupervisor.Children]
      m.shutdown()

      m.supervisor ! StreamSupervisor.GetChildren
      expectNoMessage(1.second)
    }

    "terminate if ActorContext it was created from terminates" in {
      val p = TestProbe()

      val a = system.actorOf(Props(new ActorWithMaterializer(p)).withDispatcher("akka.test.stream-dispatcher"))

      p.expectMsg("hello")
      a ! PoisonPill
      val Failure(ex) = p.expectMsgType[Try[Done]]
    }

    "handle properly broken Props" in {
      val m = ActorMaterializer.create(system)
      an[IllegalArgumentException] should be thrownBy
        Await.result(
          Source.actorPublisher(Props(classOf[TestActor], "wrong", "arguments")).runWith(Sink.head)(m),
          3.seconds)
    }

    "report correctly if it has been shut down from the side" in {
      val sys = ActorSystem()
      val m = ActorMaterializer.create(sys)
      Await.result(sys.terminate(), Duration.Inf)
      m.isShutdown should ===(true)
    }
  }

}

object ActorMaterializerSpec {
  class ActorWithMaterializer(p: TestProbe) extends Actor {
    private val settings: ActorMaterializerSettings = ActorMaterializerSettings(context.system).withDispatcher("akka.test.stream-dispatcher")
    implicit val mat = ActorMaterializer(settings)(context)

    Source.repeat("hello")
      .take(1)
      .concat(Source.maybe)
      .map(p.ref ! _)
      .runWith(Sink.onComplete(signal ⇒ {
        p.ref ! signal
      }))

    def receive = Actor.emptyBehavior
  }
}
