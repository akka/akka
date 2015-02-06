/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.stage.Context;
import akka.stream.stage.Directive;
import akka.stream.stage.PushStage;
import akka.stream.stage.TerminationDirective;
import akka.testkit.JavaTestKit;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.Arrays;

public class RecipeLoggingElements extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLoggingElements");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  @Test
  public void workWithPrintln() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        final Source<String> mySource = Source.from(Arrays.asList("1", "2", "3"));

        //#println-debug
        mySource.map(elem -> {
          System.out.println(elem);
          return elem;
        })
          //#println-debug
          .runWith(Sink.ignore(), mat);
      }
    };
  }

  @Test
  public void workWithPushStage() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        final Source<String> mySource = Source.from(Arrays.asList("1", "2", "3"));

        //#loggingadapter
        final PushStage<String, String> LoggingStage = new PushStage<String, String>() {
          @Override
          public Directive onPush(String elem, Context<String> ctx) {
            java.lang.System.out.println("Element flowing thought: " + elem);
            return ctx.push(elem);
          }

          @Override
          public TerminationDirective onUpstreamFinish(Context<String> ctx) {
            java.lang.System.out.println("Upstream finished.");
            return super.onUpstreamFinish(ctx);
          }

          @Override
          public TerminationDirective onUpstreamFailure(Throwable cause, Context<String> ctx) {
            java.lang.System.out.println("Upstream failed: " + cause.getMessage());
            return super.onUpstreamFailure(cause, ctx);
          }
        };

        mySource.transform(() -> LoggingStage)
          //#loggingadapter
          .runWith(Sink.ignore(), mat);
      }
    };
  }

}
