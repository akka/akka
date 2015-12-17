package docs;

import akka.actor.ActorSystem;
import akka.actor.Cancellable;
import akka.http.javadsl.model.Uri;
import akka.dispatch.Futures;
import akka.japi.function.Creator;
import akka.japi.Pair;
import akka.japi.function.Function;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.util.ByteString;
import scala.Option;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.concurrent.Promise;
import scala.runtime.BoxedUnit;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.nio.charset.Charset;

public class MigrationsJava {

    // This is compile-only code, no need for actually running anything.
    public static ActorMaterializer mat = null;
    public static ActorSystem sys = null;

    public static class SomeInputStream extends InputStream {
      public SomeInputStream() {}
      @Override public int read() throws IOException { return 0; }
    }

    public static class SomeOutputStream extends OutputStream {
      @Override public void write(int b) throws IOException { return; }
    }

    public static void main(String[] args) {

        Outlet<Integer> outlet = null;

        Outlet<Integer> outlet1 = null;
        Outlet<Integer> outlet2 = null;

        Inlet<Integer> inlet = null;

        Inlet<Integer> inlet1 = null;
        Inlet<Integer> inlet2 = null;

        Flow<Integer, Integer, BoxedUnit> flow = Flow.of(Integer.class);
        Flow<Integer, Integer, BoxedUnit> flow1 = Flow.of(Integer.class);
        Flow<Integer, Integer, BoxedUnit> flow2 = Flow.of(Integer.class);

        Promise<Option<Integer>> promise = null;


        {
            Graph<SourceShape<Integer>, BoxedUnit> graphSource = null;
            Graph<SinkShape<Integer>, BoxedUnit> graphSink = null;
            Graph<FlowShape<Integer, Integer>, BoxedUnit> graphFlow = null;

            //#flow-wrap
            Source<Integer, BoxedUnit> source = Source.fromGraph(graphSource);
            Sink<Integer, BoxedUnit> sink = Sink.fromGraph(graphSink);
            Flow<Integer, Integer, BoxedUnit> aflow = Flow.fromGraph(graphFlow);
            Flow.fromSinkAndSource(Sink.<Integer>head(), Source.single(0));
            Flow.fromSinkAndSourceMat(Sink.<Integer>head(), Source.single(0), Keep.left());
            //#flow-wrap

            Graph<BidiShape<Integer, Integer, Integer, Integer>, BoxedUnit> bidiGraph = null;

            //#bidi-wrap
            BidiFlow<Integer, Integer, Integer, Integer, BoxedUnit> bidiFlow =
                BidiFlow.fromGraph(bidiGraph);
            BidiFlow.fromFlows(flow1, flow2);
            BidiFlow.fromFlowsMat(flow1, flow2, Keep.both());
            //#bidi-wrap

        }

        {
            //#graph-create
            GraphDSL.create(builder -> {
                //...
                return ClosedShape.getInstance();
            });

            GraphDSL.create(builder -> {
                //...
                return new FlowShape<>(inlet, outlet);
            });
            //#graph-create
        }

        {
            //#graph-create-2
            GraphDSL.create(builder -> {
                //...
                return SourceShape.of(outlet);
            });

            GraphDSL.create(builder -> {
                //...
                return SinkShape.of(inlet);
            });

            GraphDSL.create(builder -> {
                //...
                return FlowShape.of(inlet, outlet);
            });

            GraphDSL.create(builder -> {
                //...
                return BidiShape.of(inlet1, outlet1, inlet2, outlet2);
            });
            //#graph-create-2
        }

        {
            //#graph-builder
            GraphDSL.create(builder -> {
                builder.from(outlet).toInlet(inlet);
                builder.from(outlet).via(builder.add(flow)).toInlet(inlet);
                builder.from(builder.add(Source.single(0))).to(builder.add(Sink.head()));
                //...
                return ClosedShape.getInstance();
            });
            //#graph-builder
        }

        //#source-creators
        Source<Integer, Promise<Option<Integer>>> src = Source.<Integer>maybe();
        // Complete the promise with an empty option to emulate the old lazyEmpty
        promise.trySuccess(scala.Option.empty());

        final Source<String, Cancellable> ticks = Source.tick(
          FiniteDuration.create(0, TimeUnit.MILLISECONDS),
          FiniteDuration.create(200, TimeUnit.MILLISECONDS),
          "tick");

        final Source<Integer, BoxedUnit> pubSource =
          Source.fromPublisher(TestPublisher.<Integer>manualProbe(true, sys));

        final Source<Integer, BoxedUnit> futSource =
          Source.fromFuture(Futures.successful(42));

        final Source<Integer, Subscriber<Integer>> subSource =
          Source.<Integer>asSubscriber();
        //#source-creators

        //#sink-creators
        final Sink<Integer, BoxedUnit> subSink =
          Sink.fromSubscriber(TestSubscriber.<Integer>manualProbe(sys));
        //#sink-creators

        //#sink-as-publisher
        final Sink<Integer, Publisher<Integer>> pubSink =
          Sink.<Integer>asPublisher(false);

        final Sink<Integer, Publisher<Integer>> pubSinkFanout =
          Sink.<Integer>asPublisher(true);
        //#sink-as-publisher

        //#empty-flow
        Flow<Integer, Integer, BoxedUnit> emptyFlow = Flow.<Integer>create();
        // or
        Flow<Integer, Integer, BoxedUnit> emptyFlow2 = Flow.of(Integer.class);
        //#empty-flow

        //#flatMapConcat
        Flow.<Source<Integer, BoxedUnit>>create().
          <Integer, BoxedUnit>flatMapConcat(new Function<Source<Integer, BoxedUnit>, Source<Integer, BoxedUnit>>(){
            @Override public Source<Integer, BoxedUnit> apply(Source<Integer, BoxedUnit> param) throws Exception {
              return param;
            }
          });
        //#flatMapConcat

        Uri uri = null;
        //#raw-query
        final akka.japi.Option<String> theRawQueryString = uri.rawQueryString();
        //#raw-query

        //#query-param
        final akka.japi.Option<String> aQueryParam = uri.query().get("a");
        //#query-param

        //#file-source-sink
        final Source<ByteString, Future<Long>> fileSrc =
          Source.file(new File("."));

        final Source<ByteString, Future<Long>> otherFileSrc =
          Source.file(new File("."), 1024);

        final Sink<ByteString, Future<Long>> fileSink =
          Sink.file(new File("."));
        //#file-source-sink

        //#input-output-stream-source-sink
        final Source<ByteString, Future<java.lang.Long>> inputStreamSrc =
          Source.inputStream(new Creator<InputStream>(){
            public InputStream create() {
              return new SomeInputStream();
            }
          });

        final Source<ByteString, Future<java.lang.Long>> otherInputStreamSrc =
          Source.inputStream(new Creator<InputStream>(){
            public InputStream create() {
              return new SomeInputStream();
            }
          }, 1024);

        final Sink<ByteString, Future<java.lang.Long>> outputStreamSink =
          Sink.outputStream(new Creator<OutputStream>(){
            public OutputStream create() {
              return new SomeOutputStream();
            }
          });
        //#input-output-stream-source-sink


      //#output-input-stream-source-sink
      final FiniteDuration timeout = FiniteDuration.Zero();

      final Source<ByteString, OutputStream> outputStreamSrc =
          Source.outputStream();

        final Source<ByteString, OutputStream> otherOutputStreamSrc =
          Source.outputStream(timeout);

        final Sink<ByteString, InputStream> someInputStreamSink =
          Sink.inputStream();

        final Sink<ByteString, InputStream> someOtherInputStreamSink =
          Sink.inputStream(timeout);
        //#output-input-stream-source-sink

    }

}
