package docs

import java.io.{ InputStream, File }

import akka.http.scaladsl.model.Uri
import akka.stream.scaladsl._
import akka.stream._
import akka.stream.stage.{ OutHandler, InHandler, GraphStageLogic, GraphStage }
import akka.stream.testkit.{ AkkaSpec, TestPublisher, TestSubscriber }

import scala.concurrent.{ Future, ExecutionContext, Promise }
import scala.concurrent.duration._
import scala.util.{ Failure, Random, Success, Try }

class MigrationsScala extends AkkaSpec {

  "Examples in migration guide" must {
    "compile" in {
      val flow1 = Flow[Int]
      val flow2 = Flow[Int]

      def inlet: Inlet[Int] = ???
      def outlet: Outlet[Int] = ???

      def inlet1: Inlet[Int] = ???
      def outlet1: Outlet[Int] = ???
      def inlet2: Inlet[Int] = ???
      def outlet2: Outlet[Int] = ???

      lazy val dontExecuteMe = {
        //#flow-wrap
        val graphSource: Graph[SourceShape[Int], Unit] = ???
        val source: Source[Int, Unit] = Source.fromGraph(graphSource)

        val graphSink: Graph[SinkShape[Int], Unit] = ???
        val sink: Sink[Int, Unit] = Sink.fromGraph(graphSink)

        val graphFlow: Graph[FlowShape[Int, Int], Unit] = ???
        val flow: Flow[Int, Int, Unit] = Flow.fromGraph(graphFlow)

        Flow.fromSinkAndSource(Sink.head[Int], Source.single(0))
        //#flow-wrap

        //#bidiflow-wrap
        val bidiGraph: Graph[BidiShape[Int, Int, Int, Int], Unit] = ???
        val bidi: BidiFlow[Int, Int, Int, Int, Unit] = BidiFlow.fromGraph(bidiGraph)

        BidiFlow.fromFlows(flow1, flow2)

        BidiFlow.fromFunctions((x: Int) => x + 1, (y: Int) => y * 3)
        //#bidiflow-wrap

        //#graph-create
        // Replaces GraphDSL.closed()
        GraphDSL.create() { builder =>
          //...
          ClosedShape
        }

        // Replaces GraphDSL.partial()
        GraphDSL.create() { builder =>
          //...
          FlowShape(inlet, outlet)
        }
        //#graph-create

        //#graph-create-2
        Source.fromGraph(
          GraphDSL.create() { builder =>
            //...
            SourceShape(outlet)
          })

        Sink.fromGraph(
          GraphDSL.create() { builder =>
            //...
            SinkShape(inlet)
          })

        Flow.fromGraph(
          GraphDSL.create() { builder =>
            //...
            FlowShape(inlet, outlet)
          })

        BidiFlow.fromGraph(
          GraphDSL.create() { builder =>
            //...
            BidiShape(inlet1, outlet1, inlet2, outlet2)
          })
        //#graph-create-2

        //#graph-edges
        RunnableGraph.fromGraph(
          GraphDSL.create() { implicit builder =>
            import GraphDSL.Implicits._
            outlet ~> inlet
            outlet ~> flow ~> inlet
            //...
            ClosedShape
          })
        //#graph-edges

        val promise = Promise[Unit]()

        //#source-creators
        val src: Source[Int, Promise[Option[Int]]] = Source.maybe[Int]
        //...
        // This finishes the stream without emitting anything, just like Source.lazyEmpty did
        promise.trySuccess(Some(()))

        val ticks = Source.tick(1.second, 3.seconds, "tick")

        val pubSource = Source.fromPublisher(TestPublisher.manualProbe[Int]())

        val itSource = Source.fromIterator(() => Iterator.continually(Random.nextGaussian))

        val futSource = Source.fromFuture(Future.successful(42))

        val subSource = Source.asSubscriber
        //#source-creators

        //#sink-creators
        val subSink = Sink.fromSubscriber(TestSubscriber.manualProbe[Int]())
        //#sink-creators

        //#sink-as-publisher
        val pubSink = Sink.asPublisher(fanout = false)

        val pubSinkFanout = Sink.asPublisher(fanout = true)
        //#sink-as-publisher

        //#flatMapConcat
        Flow[Source[Int, Any]].flatMapConcat(identity)
        //#flatMapConcat

        //#group-flatten
        Flow[Int]
          .groupBy(2, _ % 2) // the first parameter sets max number of substreams
          .map(_ + 3)
          .concatSubstreams
        //#group-flatten

        val MaxDistinctWords = 1000
        //#group-fold
        Flow[String]
          .groupBy(MaxDistinctWords, identity)
          .fold(("", 0))((pair, word) => (word, pair._2 + 1))
          .mergeSubstreams
        //#group-fold

        //#port-async
        class MapAsyncOne[In, Out](f: In ⇒ Future[Out])(implicit ec: ExecutionContext)
          extends GraphStage[FlowShape[In, Out]] {
          val in: Inlet[In] = Inlet("MapAsyncOne.in")
          val out: Outlet[Out] = Outlet("MapAsyncOne.out")
          override val shape: FlowShape[In, Out] = FlowShape(in, out)

          // The actual logic is encapsulated in a GraphStageLogic now
          override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
            new GraphStageLogic(shape) {

              // All of the state *must* be encapsulated in the GraphStageLogic,
              // not in the GraphStage
              private var elemInFlight: Out = _

              val callback = getAsyncCallback(onAsyncInput)
              var holdingUpstream = false

              // All upstream related events now are handled in an InHandler instance
              setHandler(in, new InHandler {
                // No context or element parameter for onPush
                override def onPush(): Unit = {
                  // The element is not passed as an argument but needs to be dequeued explicitly
                  val elem = grab(in)
                  val future = f(elem)
                  future.onComplete(callback.invoke)
                  // ctx.holdUpstream is no longer needed, but we need to track the state
                  holdingUpstream = true
                }

                // No context parameter
                override def onUpstreamFinish(): Unit = {
                  if (holdingUpstream) absorbTermination()
                  else completeStage() // ctx.finish turns into completeStage()
                }
              })

              setHandler(out, new OutHandler {
                override def onPull(): Unit = {
                  if (elemInFlight != null) {
                    val e = elemInFlight
                    elemInFlight = null.asInstanceOf[Out]
                    pushIt(e)
                  } // holdDownstream is no longer needed
                }
              })

              // absorbTermination turns into the code below.
              // This emulates the behavior of the AsyncStage stage.
              private def absorbTermination(): Unit =
                if (isAvailable(shape.out)) getHandler(out).onPull()

              // The line below emulates the behavior of the AsyncStage holdingDownstream
              private def holdingDownstream(): Boolean =
                !(isClosed(in) || hasBeenPulled(in))

              // Any method can be used as a callback, we chose the previous name for
              // easier comparison with the original code
              private def onAsyncInput(input: Try[Out]) =
                input match {
                  case Failure(ex)                       ⇒ failStage(ex)
                  case Success(e) if holdingDownstream() ⇒ pushIt(e)
                  case Success(e) ⇒
                    elemInFlight = e
                  // ctx.ignore is no longer needed
                }

              private def pushIt(elem: Out): Unit = {
                // ctx.isFinishing turns into isClosed(in)
                if (isClosed(in)) {
                  // pushAndFinish is now two actions
                  push(out, elem)
                  completeStage()
                } else {
                  // pushAndPull is now two actions
                  push(out, elem)
                  pull(in)
                  holdingUpstream = false
                }
              }
            }

        }

        //#port-async

        val uri: Uri = ???
        //#raw-query
        val queryPart: Option[String] = uri.rawQueryString
        //#raw-query

        //#query-param
        val param: Option[String] = uri.query().get("a")
        //#query-param

        //#file-source-sink
        val fileSrc = FileIO.fromFile(new File("."))

        val otherFileSrc = FileIO.fromFile(new File("."), 1024)

        val someFileSink = FileIO.toFile(new File("."))
        //#file-source-sink

        class SomeInputStream extends java.io.InputStream { override def read(): Int = 0 }
        class SomeOutputStream extends java.io.OutputStream { override def write(b: Int): Unit = () }

        //#input-output-stream-source-sink
        val inputStreamSrc = StreamConverters.fromInputStream(() => new SomeInputStream())

        val otherInputStreamSrc = StreamConverters.fromInputStream(() => new SomeInputStream())

        val someOutputStreamSink = StreamConverters.fromOutputStream(() => new SomeOutputStream())
        //#input-output-stream-source-sink

        //#output-input-stream-source-sink
        val timeout: FiniteDuration = 0.seconds

        val outputStreamSrc = StreamConverters.asOutputStream()

        val otherOutputStreamSrc = StreamConverters.asOutputStream(timeout)

        val someInputStreamSink = StreamConverters.asInputStream()

        val someOtherInputStreamSink = StreamConverters.asInputStream(timeout)
        //#output-input-stream-source-sink
      }
    }
  }

}
