package docs;

import akka.japi.Pair;
import akka.japi.function.Function;
import akka.stream.*;
import akka.stream.javadsl.*;
import scala.Option;
import scala.concurrent.Promise;
import scala.runtime.BoxedUnit;

public class MigrationsJava {

    // This is compile-only code, no need for actually running anything.
    public static ActorMaterializer mat = null;

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
            FlowGraph.create(builder -> {
                //...
                return ClosedShape.getInstance();
            });

            FlowGraph.create(builder -> {
                //...
                return new FlowShape<>(inlet, outlet);
            });
            //#graph-create
        }

        {
            //#graph-create-2
            FlowGraph.create(builder -> {
                //...
                return new SourceShape<>(outlet);
            });

            FlowGraph.create(builder -> {
                //...
                return new SinkShape<>(inlet);
            });

            FlowGraph.create(builder -> {
                //...
                return new FlowShape<>(inlet, outlet);
            });

            FlowGraph.create(builder -> {
                //...
                return new BidiShape<>(inlet1, outlet1, inlet2, outlet2);
            });
            //#graph-create-2
        }

        {
            //#graph-builder
            FlowGraph.create(builder -> {
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
        //#source-creators

        //#empty-flow
        Flow<Integer, Integer, BoxedUnit> emptyFlow = Flow.<Integer>create();
        // or
        Flow<Integer, Integer, BoxedUnit> emptyFlow2 = Flow.of(Integer.class);
        //#empty-flow

        //#flatMapConcat
        Flow.<Source<Integer, BoxedUnit>>create().
          <Integer>flatMapConcat(new Function<Source<Integer, BoxedUnit>, Source<Integer, ?>>(){
            @Override public Source<Integer, ?> apply(Source<Integer, BoxedUnit> param) throws Exception {
              return param;
            }
          });
        //#flatMapConcat
    }

}
