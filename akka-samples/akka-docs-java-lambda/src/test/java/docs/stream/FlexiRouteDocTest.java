/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import java.util.Arrays;
import java.util.List;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.FlexiRoute;
import akka.testkit.JavaTestKit;

public class FlexiRouteDocTest {
  
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("FlexiRouteDocTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);
  
  
  static//#flexiroute-unzip
  public class Unzip<A, B> extends FlexiRoute<Pair<A, B>, Object> {

    public final OutputPort<Pair<A, B>, A> outA = createOutputPort();
    public final OutputPort<Pair<A, B>, B> outB = createOutputPort();

    @Override
    public RouteLogic<Pair<A, B>, Object> createRouteLogic() {
      return new RouteLogic<Pair<A, B>, Object>() {

        @Override
        public List<OutputHandle> outputHandles(int outputCount) {
          if (outputCount != 2)
            throw new IllegalArgumentException(
                "Unzip must have two connected outputs, was " + outputCount);
          return Arrays.asList(outA, outB);
        }

        @Override
        public State<Pair<A, B>, Object> initialState() {
          return new State<Pair<A, B>, Object>(demandFromAll(outA, outB)) {
            @Override
            public State<Pair<A, B>, Object> onInput(RouteLogicContext<Pair<A, B>, 
                Object> ctx, 
                OutputHandle preferred, Pair<A, B> element) {
              ctx.emit(outA, element.first());
              ctx.emit(outB, element.second());
              return sameState();
            }
          };
        }

        @Override
        public CompletionHandling<Pair<A, B>> initialCompletionHandling() {
          return eagerClose();
        }

      };
    }
  }
  //#flexiroute-unzip
  
  static//#flexiroute-completion
  public class ImportantRoute<T> extends FlexiRoute<T, T> {

    public final OutputPort<T, T> important = createOutputPort();
    public final OutputPort<T, T> additional1 = createOutputPort();
    public final OutputPort<T, T> additional2 = createOutputPort();

    @Override
    public RouteLogic<T, T> createRouteLogic() {
      return new RouteLogic<T, T>() {
        @Override
        public List<OutputHandle> outputHandles(int outputCount) {
          if (outputCount != 3)
            throw new IllegalArgumentException(
                "Unzip must have 3 connected outputs, was " + outputCount);
          return Arrays.asList(additional1.handle(), additional2.handle());
        }
        
        @Override
        public CompletionHandling<T> initialCompletionHandling() {
          return new CompletionHandling<T>() {
            @Override
            public State<T, T> onDownstreamFinish(RouteLogicContextBase<T> ctx,
                OutputHandle output) {
              if (output == important) {
                // finish all downstreams, and cancel the upstream
                ctx.finish();
                return sameState();
              } else {
                return sameState();
              }
            }
            
            @Override
            public void onUpstreamFinish(RouteLogicContextBase<T> ctx) {
            }

            @Override
            public void onUpstreamFailure(RouteLogicContextBase<T> ctx, Throwable t) {
            }
          };
          
        }

        @Override
        public State<T, T> initialState() {
          return new State<T, T>(demandFromAny(important, additional1, additional2)) {
            @Override
            public State<T, T> onInput(RouteLogicContext<T, T> ctx, OutputHandle preferred, T element) {
              ctx.emit(preferred, element);
              return sameState();
            }
          };
        }
      };
    }
  }
  //#flexiroute-completion
  
}
