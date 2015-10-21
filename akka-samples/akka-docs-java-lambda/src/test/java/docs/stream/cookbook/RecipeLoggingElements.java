/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

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
import docs.stream.SilenceSystemOut;
import scala.runtime.AbstractFunction0;
import scala.runtime.BoxedUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import com.typesafe.config.ConfigFactory;

import java.util.Arrays;

public class RecipeLoggingElements extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeLoggingElements", ConfigFactory.parseString("akka.loglevel=DEBUG\nakka.loggers = [akka.testkit.TestEventListener]"));
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  @Test
  public void workWithPrintln() throws Exception {
    new JavaTestKit(system) {
      final SilenceSystemOut.System System = SilenceSystemOut.get(getTestActor());

      {
        final Source<String, BoxedUnit> mySource = Source.from(Arrays.asList("1", "2", "3"));

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
        final Source<String, BoxedUnit> mySource = Source.from(Arrays.asList("1", "2", "3"));

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

        new DebugFilter("customLogger", "[custom] Element: ", false, false, 3).intercept(new AbstractFunction0  () {
          public Void apply() {
            mySource.log("custom", adapter).runWith(Sink.ignore(), mat);
            return null;
          }
        }, system);
      }
    };
  }

}
