/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream

import akka.NotUsed
import akka.actor.{ Actor, Props }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import akka.testkit.AkkaSpec
import docs.CompileOnlySpec
import scala.concurrent.Future

class FlowStreamRefsDocSpec extends AkkaSpec with CompileOnlySpec {

  "offer a source ref" in compileOnlySpec {
    //#offer-source
    import akka.stream.SourceRef
    import akka.pattern.pipe

    case class RequestLogs(streamId: Int)
    case class LogsOffer(streamId: Int, sourceRef: SourceRef[String])

    class DataSource extends Actor {
      import context.dispatcher
      implicit val mat = ActorMaterializer()(context)

      def receive = {
        case RequestLogs(streamId) ⇒
          // obtain the source you want to offer:
          val source: Source[String, NotUsed] = streamLogs(streamId)

          // materialize the SourceRef:
          val ref: Future[SourceRef[String]] = source.runWith(StreamRefs.sourceRef())

          // wrap the SourceRef in some domain message, such that the sender knows what source it is
          val reply: Future[LogsOffer] = ref.map(LogsOffer(streamId, _))

          // reply to sender
          reply pipeTo sender()
      }

      def streamLogs(streamId: Long): Source[String, NotUsed] = ???
    }
    //#offer-source

    implicit val mat = ActorMaterializer()
    //#offer-source-use
    val sourceActor = system.actorOf(Props[DataSource], "dataSource")

    sourceActor ! RequestLogs(1337)
    val offer = expectMsgType[LogsOffer]

    // implicitly converted to a Source:
    offer.sourceRef.runWith(Sink.foreach(println))
    // alternatively explicitly obtain Source from SourceRef:
    // offer.sourceRef.source.runWith(Sink.foreach(println))

    //#offer-source-use
  }

  "offer a sink ref" in compileOnlySpec {
    //#offer-sink
    import akka.pattern.pipe
    import akka.stream.SinkRef

    case class PrepareUpload(id: String)
    case class MeasurementsSinkReady(id: String, sinkRef: SinkRef[String])

    class DataReceiver extends Actor {

      import context.dispatcher
      implicit val mat = ActorMaterializer()(context)

      def receive = {
        case PrepareUpload(nodeId) ⇒
          // obtain the source you want to offer:
          val sink: Sink[String, NotUsed] = logsSinkFor(nodeId)

          // materialize the SinkRef (the remote is like a source of data for us):
          val ref: Future[SinkRef[String]] = StreamRefs.sinkRef[String]().to(sink).run()

          // wrap the SinkRef in some domain message, such that the sender knows what source it is
          val reply: Future[MeasurementsSinkReady] = ref.map(MeasurementsSinkReady(nodeId, _))

          // reply to sender
          reply pipeTo sender()
      }

      def logsSinkFor(nodeId: String): Sink[String, NotUsed] = ???
    }

    //#offer-sink

    implicit val mat = ActorMaterializer()
    def localMetrics(): Source[String, NotUsed] = Source.single("")

    //#offer-sink-use
    val receiver = system.actorOf(Props[DataReceiver], "receiver")

    receiver ! PrepareUpload("system-42-tmp")
    val ready = expectMsgType[MeasurementsSinkReady]

    // stream local metrics to Sink's origin:
    localMetrics().runWith(ready.sinkRef)
    //#offer-sink-use
  }

  "show how to configure timeouts with attrs" in compileOnlySpec {

    implicit val mat: ActorMaterializer = null
    //#attr-sub-timeout
    // configure the timeout for source
    import scala.concurrent.duration._
    import akka.stream.StreamRefAttributes

    // configuring Sink.sourceRef (notice that we apply the attributes to the Sink!):
    Source.repeat("hello")
      .runWith(StreamRefs.sourceRef().addAttributes(StreamRefAttributes.subscriptionTimeout(5.seconds)))

    // configuring SinkRef.source:
    StreamRefs.sinkRef().addAttributes(StreamRefAttributes.subscriptionTimeout(5.seconds))
      .runWith(Sink.ignore) // not very interesting Sink, just an example
    //#attr-sub-timeout
  }

}
