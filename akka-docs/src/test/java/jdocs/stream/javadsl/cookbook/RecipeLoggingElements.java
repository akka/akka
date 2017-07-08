/**
 *  Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package jdocs.stream.javadsl.cookbook;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.stream.ActorMaterializer;
import akka.stream.Attributes;
import akka.stream.Materializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Keep;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.SourceQueue;
import akka.stream.javadsl.SourceQueueWithComplete;
import akka.stream.testkit.TestSubscriber;
import akka.testkit.DebugFilter;
import akka.testkit.InfoFilter;
import akka.testkit.WarningFilter;
import akka.testkit.javadsl.TestKit;
import com.typesafe.config.ConfigFactory;
import jdocs.stream.SilenceSystemOut;
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
    TestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }

  @Test
  public void workWithPrintln() throws Exception {
    new TestKit(system) {
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
    new TestKit(system) {
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
  @Test
  public void enableVerboseLogging() throws Exception {
    new TestKit(system) {
      {
        final Source<Integer,SourceQueueWithComplete<Integer>> mySource = Source.<Integer>queue(100, OverflowStrategy.dropHead());
        final TestSubscriber.Probe<Integer> s = TestSubscriber.probe(system);

        //#verbose-logging
        // enable verbose logging in source stage
        mySource.addAttributes(Attributes.verboseLogging());
        //#verbose-logging

        final SourceQueueWithComplete<Integer> queueSource = 
          mySource.addAttributes(Attributes.verboseLogging())
            .toMat(Sink.fromSubscriber(s), Keep.left()).run(mat);

        new WarningFilter(null, "Queue is using .* of its buffer capacity", true, false, 6).intercept(new AbstractFunction0<Object> () {
          public Void apply() {
            for(int i = 1; i <= 100; i++)
              queueSource.offer(i);
            return null;
          }
        }, system);
      }
    };
  }
  
}
