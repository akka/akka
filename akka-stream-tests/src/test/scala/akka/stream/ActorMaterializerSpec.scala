package akka.stream

import akka.actor.{ ActorSystem, Props }
import akka.stream.impl.{ StreamSupervisor, ActorMaterializerImpl }
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.{ AkkaSpec, TestActor, ImplicitSender }

import scala.concurrent.Await
import scala.concurrent.duration._

class ActorMaterializerSpec extends AkkaSpec with ImplicitSender {

  "ActorMaterializer" must {

    "report shutdown status properly" in {
      val m = ActorMaterializer.create(system)

      m.isShutdown should ===(false)
      m.shutdown()
      m.isShutdown should ===(true)
    }

    "properly shut down actors associated with it" in {
      val m = ActorMaterializer.create(system)

      val f = Source.maybe[Int].runFold(0)(_ + _)(m)

      m.shutdown()

      an[AbruptTerminationException] should be thrownBy Await.result(f, 3.seconds)
    }

    "refuse materialization after shutdown" in {
      val m = ActorMaterializer.create(system)
      m.shutdown()
      an[IllegalStateException] should be thrownBy
        Source(1 to 5).runForeach(println)(m)
    }

    "shut down the supervisor actor it encapsulates" in {
      val m = ActorMaterializer.create(system).asInstanceOf[ActorMaterializerImpl]

      Source.maybe[Any].to(Sink.ignore).run()(m)
      m.supervisor ! StreamSupervisor.GetChildren
      expectMsgType[StreamSupervisor.Children]
      m.shutdown()

      m.supervisor ! StreamSupervisor.GetChildren
      expectNoMsg(1.second)
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
