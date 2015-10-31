/**
 *  Copyright (C) 2015 Typesafe <http://typesafe.com/>
 */
package docs.stream.cookbook;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.testkit.TestPublisher;
import akka.stream.testkit.TestSubscriber;
import akka.stream.testkit.javadsl.TestSink;
import akka.stream.testkit.javadsl.TestSource;
import akka.testkit.JavaTestKit;
import akka.testkit.TestLatch;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class RecipeSimpleDrop extends RecipeTest {
  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("RecipeSimpleDrop");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final Materializer mat = ActorMaterializer.create(system);

  @Test
  public void work() throws Exception {
    new JavaTestKit(system) {
      {
    	@SuppressWarnings("unused")
        //#simple-drop
        final Flow<Message, Message, BoxedUnit> droppyStream =
          Flow.of(Message.class).conflate(i -> i, (lastMessage, newMessage) -> newMessage);
        //#simple-drop
    	final TestLatch latch = new TestLatch(2, system);
        final Flow<Message, Message, BoxedUnit> realDroppyStream =
                Flow.of(Message.class).conflate(i -> i, (lastMessage, newMessage) -> { latch.countDown(); return newMessage; });

        final Pair<TestPublisher.Probe<Message>, TestSubscriber.Probe<Message>> pubSub = TestSource
          .<Message> probe(system)
          .via(realDroppyStream)
          .toMat(TestSink.probe(system),
            (pub, sub) -> new Pair<>(pub, sub))
          .run(mat);
        final TestPublisher.Probe<Message> pub = pubSub.first();
        final TestSubscriber.Probe<Message> sub = pubSub.second();

        pub.sendNext(new Message("1"));
        pub.sendNext(new Message("2"));
        pub.sendNext(new Message("3"));
        
        Await.ready(latch, Duration.create(1, TimeUnit.SECONDS));
        
        sub.requestNext(new Message("3"));

        pub.sendComplete();
        sub.request(1);
        sub.expectComplete();
      }
    };
  }
}
