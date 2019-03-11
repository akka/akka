/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.Actor
import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.actor.Props
import akka.pattern.pipe
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.transport.ThrottlerTransportAdapter.Direction
import akka.stream.ActorMaterializer
import akka.stream.RemoteStreamRefActorTerminatedException
import akka.stream.SinkRef
import akka.stream.SourceRef
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.StreamRefs
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.testkit._
import com.typesafe.config.ConfigFactory

object StreamRefSpec extends MultiNodeConfig {
  val first = role("first")
  val second = role("second")
  val third = role("third")

  commonConfig(debugConfig(on = false).withFallback(ConfigFactory.parseString("""
        akka.cluster {
          auto-down-unreachable-after = 1s
        }""")).withFallback(MultiNodeClusterSpec.clusterConfig))

  testTransport(on = true)

  case class RequestLogs(streamId: Int)
  case class LogsOffer(streamId: Int, sourceRef: SourceRef[String])

  object DataSource {
    def props(streamLifecycleProbe: ActorRef): Props =
      Props(new DataSource(streamLifecycleProbe))
  }

  class DataSource(streamLifecycleProbe: ActorRef) extends Actor {
    import context.dispatcher
    implicit val mat = ActorMaterializer()(context)

    def receive = {
      case RequestLogs(streamId) =>
        // materialize the SourceRef:
        val (done: Future[Done], ref: Future[SourceRef[String]]) =
          Source
            .fromIterator(() => Iterator.from(1))
            .map(n => s"elem-$n")
            .watchTermination()(Keep.right)
            .toMat(StreamRefs.sourceRef())(Keep.both)
            .mapMaterializedValue { m =>
              streamLifecycleProbe ! s"started-$streamId"
              m
            }
            .run()

        done.onComplete {
          case Success(_) => streamLifecycleProbe ! s"completed-$streamId"
          case Failure(_) => streamLifecycleProbe ! s"failed-$streamId"
        }

        // wrap the SourceRef in some domain message, such that the sender knows what source it is
        val reply: Future[LogsOffer] = ref.map(LogsOffer(streamId, _))

        // reply to sender
        reply.pipeTo(sender())
    }

  }

  case class PrepareUpload(id: String)
  case class MeasurementsSinkReady(id: String, sinkRef: SinkRef[String])

  object DataReceiver {
    def props(streamLifecycleProbe: ActorRef): Props =
      Props(new DataReceiver(streamLifecycleProbe))
  }

  class DataReceiver(streamLifecycleProbe: ActorRef) extends Actor {

    import context.dispatcher
    implicit val mat = ActorMaterializer()(context)

    def receive = {
      case PrepareUpload(nodeId) =>
        // materialize the SinkRef (the remote is like a source of data for us):
        val (ref: Future[SinkRef[String]], done: Future[Done]) =
          StreamRefs
            .sinkRef[String]()
            .throttle(1, 1.second)
            .toMat(Sink.ignore)(Keep.both)
            .mapMaterializedValue { m =>
              streamLifecycleProbe ! s"started-$nodeId"
              m
            }
            .run()

        done.onComplete {
          case Success(_) => streamLifecycleProbe ! s"completed-$nodeId"
          case Failure(_) => streamLifecycleProbe ! s"failed-$nodeId"
        }

        // wrap the SinkRef in some domain message, such that the sender knows what source it is
        val reply: Future[MeasurementsSinkReady] = ref.map(MeasurementsSinkReady(nodeId, _))

        // reply to sender
        reply.pipeTo(sender())
    }

  }

}

class StreamRefMultiJvmNode1 extends StreamRefSpec
class StreamRefMultiJvmNode2 extends StreamRefSpec
class StreamRefMultiJvmNode3 extends StreamRefSpec

abstract class StreamRefSpec extends MultiNodeSpec(StreamRefSpec) with MultiNodeClusterSpec with ImplicitSender {
  import StreamRefSpec._

  private implicit val mat: ActorMaterializer = ActorMaterializer()

  "A cluster with Stream Refs" must {

    "join" taggedAs LongRunningTest in {
      awaitClusterUp(first, second, third)

      enterBarrier("after-1")
    }

    "stop stream with SourceRef after downing and removal" taggedAs LongRunningTest in {
      val dataSourceLifecycle = TestProbe()
      runOn(second) {
        system.actorOf(DataSource.props(dataSourceLifecycle.ref), "dataSource")
      }
      enterBarrier("actor-started")

      // only used from first
      var destinationForSource: TestSubscriber.Probe[String] = null

      runOn(first) {
        system.actorSelection(node(second) / "user" / "dataSource") ! Identify(None)
        val ref = expectMsgType[ActorIdentity].ref.get
        ref ! RequestLogs(1337)
        val dataSourceRef = expectMsgType[LogsOffer].sourceRef
        destinationForSource = dataSourceRef.runWith(TestSink.probe)
        destinationForSource.request(3).expectNext("elem-1").expectNext("elem-2").expectNext("elem-3")
      }
      runOn(second) {
        dataSourceLifecycle.expectMsg("started-1337")
      }
      enterBarrier("streams-started")

      runOn(first) {
        testConductor.blackhole(first, second, Direction.Both).await
        testConductor.blackhole(third, second, Direction.Both).await
      }
      enterBarrier("after-split")

      // auto-down
      runOn(first, third) {
        awaitMembersUp(2, Set(second).map(address))
      }
      runOn(second) {
        awaitMembersUp(1, Set(first, third).map(address))
      }
      enterBarrier("members-removed")

      runOn(first) {
        destinationForSource.expectError().getClass should ===(classOf[RemoteStreamRefActorTerminatedException])
      }
      runOn(second) {
        // it will be cancelled, i.e. competed
        dataSourceLifecycle.expectMsg("completed-1337")
      }

      enterBarrier("after-2")
    }

    "stop stream with SinkRef after downing and removal" taggedAs LongRunningTest in {
      import system.dispatcher
      val streamLifecycle1 = TestProbe()
      val streamLifecycle3 = TestProbe()
      runOn(third) {
        system.actorOf(DataReceiver.props(streamLifecycle3.ref), "dataReceiver")
      }
      enterBarrier("actor-started")

      runOn(first) {
        system.actorSelection(node(third) / "user" / "dataReceiver") ! Identify(None)
        val ref = expectMsgType[ActorIdentity].ref.get
        ref ! PrepareUpload("system-42-tmp")
        val ready = expectMsgType[MeasurementsSinkReady]

        Source
          .fromIterator(() => Iterator.from(1))
          .map(n => s"elem-$n")
          .watchTermination()(Keep.right)
          .to(ready.sinkRef)
          .run()
          .onComplete {
            case Success(_) => streamLifecycle1.ref ! s"completed-system-42-tmp"
            case Failure(_) => streamLifecycle1.ref ! s"failed-system-42-tmp"
          }
      }
      runOn(third) {
        streamLifecycle3.expectMsg("started-system-42-tmp")
      }
      enterBarrier("streams-started")

      runOn(first) {
        testConductor.blackhole(first, third, Direction.Both).await
      }
      enterBarrier("after-split")

      // auto-down
      runOn(first) {
        awaitMembersUp(1, Set(third).map(address))
      }
      runOn(third) {
        awaitMembersUp(1, Set(first).map(address))
      }
      enterBarrier("members-removed")

      runOn(first) {
        streamLifecycle1.expectMsg("completed-system-42-tmp")
      }
      runOn(third) {
        streamLifecycle3.expectMsg("failed-system-42-tmp")
      }

      enterBarrier("after-3")
    }

  }

}
