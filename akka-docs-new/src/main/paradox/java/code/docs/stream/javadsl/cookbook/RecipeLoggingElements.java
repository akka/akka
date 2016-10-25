/**
 *  Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package docs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.stream.ActorMaterializer;
import akka.stream.Attributes;
import akka.stream.Materializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.DebugFilter;
import akka.testkit.JavaTestKit;
import com.typesafe.config.ConfigFactory;
import docs.stream.SilenceSystemOut;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.runtime.AbstractFunction0;

import java.util.Arrays;

public class RecipeLoggingElements extends RecipeTest {
  static ActorSystem system;
  static Materializer mat;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLoggingElements", ConfigFactory.parseString("akka.loglevel=DEBUG\nakka.loggers = [akka.testkit.TestEventListener]"));
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  @Test
  public void workWithPrintln() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        final Source<String, NotUsed> mySource = Source.from(Arrays.asList("1", "2", "3"));

        //#println-debug
        mySource.map(elem -> {
          System.out.println(elem);
          return elem;
        });
        //#println-debug
      }
    };
  }

  @Test
  public void workWithLog() throws Exception {
    new JavaTestKit(system) {
      private <T> T analyse(T i) {
        return i;
      }

      {
        final Source<String, NotUsed> mySource = Source.from(Arrays.asList("1", "2", "3"));

        final int onElement = Logging.WarningLevel();
        final int onFinish = Logging.ErrorLevel();
        final int onFailure = Logging.ErrorLevel();

        //#log-custom
        // customise log levels
        mySource.log("before-map")
          .withAttributes(Attributes.createLogLevels(onElement, onFinish, onFailure))
          .map(i -> analyse(i));

        // or provide custom logging adapter
        final LoggingAdapter adapter = Logging.getLogger(system, "customLogger");
        mySource.log("custom", adapter);
        //#log-custom


        new DebugFilter("customLogger", "[custom] Element: ", false, false, 3).intercept(new AbstractFunction0<Object> () {
          public Void apply() {
            mySource.log("custom", adapter).runWith(Sink.ignore(), mat);
            return null;
          }
        }, system);
      }
    };
  }

}
