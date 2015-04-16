/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import akka.stream.*;
import akka.stream.OperationAttributes;

import org.junit.AfterClass;
import org.junit.BeforeClass;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.javadsl.FlexiRoute;
import akka.testkit.JavaTestKit;
import scala.runtime.BoxedUnit;

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
  public class Unzip<A, B> extends FlexiRoute<Pair<A, B>, FanOutShape2<Pair<A, B>, A, B>> {
    public Unzip() {
      super(new FanOutShape2<Pair<A, B>, A, B>("Unzip"), OperationAttributes.name("Unzip"));
    }
    @Override
    public RouteLogic<Pair<A, B>> createRouteLogic(final FanOutShape2<Pair<A, B>, A, B> s) {
      return new RouteLogic<Pair<A, B>>() {
        @Override
        public State<BoxedUnit, Pair<A, B>> initialState() {
          return new State<BoxedUnit, Pair<A, B>>(demandFromAll(s.out0(), s.out1())) {
            @Override
            public State<BoxedUnit, Pair<A, B>> onInput(RouteLogicContext<Pair<A, B>> ctx, BoxedUnit x,
                                                        Pair<A, B> element) {
              ctx.emit(s.out0(), element.first());
              ctx.emit(s.out1(), element.second());
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
  public class ImportantRoute<T> extends FlexiRoute<T, FanOutShape3<T, T, T, T>> {

    public ImportantRoute() {
      super(new FanOutShape3<T, T, T, T>("ImportantRoute"), OperationAttributes.name("ImportantRoute"));
    }

    @Override
    public RouteLogic<T> createRouteLogic(FanOutShape3<T, T, T, T> s) {
      return new RouteLogic<T>() {

        @Override
        public CompletionHandling<T> initialCompletionHandling() {
          return new CompletionHandling<T>() {
            @Override
            public State<T, T> onDownstreamFinish(RouteLogicContextBase<T> ctx,
                OutPort output) {
              if (output == s.out0()) {
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
        public State<OutPort, T> initialState() {
          return new State<OutPort, T>(demandFromAny(s.out0(), s.out1(), s.out2())) {
            @SuppressWarnings("unchecked")
            @Override
            public State<T, T> onInput(RouteLogicContext<T> ctx, OutPort preferred, T element) {
              ctx.emit((Outlet<T>) preferred, element);
              return sameState();
            }
          };
        }
      };
    }
  }
  //#flexiroute-completion
  
}
