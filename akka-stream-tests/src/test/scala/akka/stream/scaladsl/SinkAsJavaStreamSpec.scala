/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.util.stream.Collectors

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.impl.{ PhasedFusingActorMaterializer, StreamSupervisor }
import akka.stream.impl.StreamSupervisor.Children
import akka.stream.testkit.Utils._
import akka.stream.testkit.scaladsl.StreamTestKit._
import akka.stream.testkit._
import akka.stream.testkit.scaladsl.TestSource
import akka.util.ByteString

class SinkAsJavaStreamSpec extends StreamSpec(UnboundedMailboxConfig) {
  val settings = ActorMaterializerSettings(system).withDispatcher("akka.actor.default-dispatcher")
  implicit val materializer = ActorMaterializer(settings)

  "Java Stream Sink" must {

    "work in happy case" in {
      val javaSource = Source(1 to 100).runWith(StreamConverters.asJavaStream())
      javaSource.count() should ===(100L)
    }

    "fail if parent stream is failed" in {
      val javaSource = Source(1 to 100).map(_ ⇒ throw TE("")).runWith(StreamConverters.asJavaStream())
      a[TE] shouldBe thrownBy {
        javaSource.findFirst()
      }
    }

    "work with collector that is assigned to materialized value" in {
      val javaSource = Source(1 to 100).map(_.toString).runWith(StreamConverters.asJavaStream())
      javaSource.collect(Collectors.joining(", ")) should ===((1 to 100).mkString(", "))
    }

    "work with empty stream" in {
      val javaSource = Source.empty.runWith(StreamConverters.asJavaStream())
      javaSource.count() should ===(0L)
    }

    "work with endless stream" in assertAllStagesStopped {
      val javaSource = Source.repeat(1).runWith(StreamConverters.asJavaStream())
      javaSource.limit(10).count() should ===(10L)
      javaSource.close()
    }

    "allow overriding the dispatcher using Attributes" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)

      try {
        TestSource.probe[ByteString].runWith(StreamConverters.asJavaStream()
          .addAttributes(ActorAttributes.dispatcher("akka.actor.default-dispatcher")))(materializer)
        materializer.asInstanceOf[PhasedFusingActorMaterializer].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "asJavaStream").get
        assertDispatcher(ref, "akka.actor.default-dispatcher")
      } finally shutdown(sys)
    }

    "work in separate IO dispatcher" in assertAllStagesStopped {
      val sys = ActorSystem("dispatcher-testing", UnboundedMailboxConfig)
      val materializer = ActorMaterializer()(sys)

      try {
        TestSource.probe[ByteString].runWith(StreamConverters.asJavaStream())(materializer)
        materializer.asInstanceOf[PhasedFusingActorMaterializer].supervisor.tell(StreamSupervisor.GetChildren, testActor)
        val ref = expectMsgType[Children].children.find(_.path.toString contains "asJavaStream").get
        assertDispatcher(ref, "akka.stream.default-blocking-io-dispatcher")
      } finally shutdown(sys)
    }
  }
}
