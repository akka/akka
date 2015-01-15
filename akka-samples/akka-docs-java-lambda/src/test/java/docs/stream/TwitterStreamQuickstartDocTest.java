/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream;

import static docs.stream.TwitterStreamQuickstartDocTest.Model.AKKA;
import static docs.stream.TwitterStreamQuickstartDocTest.Model.tweets;

import docs.stream.TwitterStreamQuickstartDocTest.Model.Author;
import docs.stream.TwitterStreamQuickstartDocTest.Model.Hashtag;
import docs.stream.TwitterStreamQuickstartDocTest.Model.Tweet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;

import akka.actor.ActorSystem;
import akka.dispatch.Foreach;
import akka.japi.JavaPartialFunction;
import akka.stream.FlowMaterializer;
import akka.stream.OverflowStrategy;
import akka.stream.javadsl.Broadcast;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.FlowGraph;
import akka.stream.javadsl.KeyedSink;
import akka.stream.javadsl.MaterializedMap;
import akka.stream.javadsl.RunnableFlow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.JavaTestKit;

@SuppressWarnings("unused")
public class TwitterStreamQuickstartDocTest {

  static ActorSystem system;
  

  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("SampleActorTest");
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }
  
  static abstract class Model {
    //#model
    public static class Author {
      public final String handle;
      
      public Author(String handle) {
        this.handle = handle;
      }
      
      @Override
      public String toString() {
        return "Author(" + handle + ")";
      }
    }
    
    public static class Hashtag {
      public final String name;
      
      public Hashtag(String name) {
        this.name = name;
      }

      @Override
      public int hashCode() {
        return name.hashCode();
      }

      @Override
      public boolean equals(Object obj) {
        if (this == obj) 
          return true;
        if (obj == null)
          return false;
        if (getClass() != obj.getClass())
          return false;
        Hashtag other = (Hashtag) obj;
        return name.equals(other.name);
      }
      
      @Override
      public String toString() {
        return "Hashtag(" + name + ")";
      }
    }
    
    public static class Tweet {
      public final Author author;
      public final long timestamp;
      public final String body;
      
      public Tweet(Author author, long timestamp, String body) {
        this.author = author;
        this.timestamp = timestamp;
        this.body = body;
      }
      
      public Set<Hashtag> hashtags() {
        return Arrays.asList(body.split(" ")).stream()
          .filter(a -> a.startsWith("#"))
          .map(a -> new Hashtag(a))
          .collect(Collectors.toSet());
      }
      
      @Override
      public String toString() {
        return "Tweet(" + author + "," + timestamp + "," + body + ")";
      }
          
    }
    
    public static final Hashtag AKKA = new Hashtag("#akka"); 
    
    //#model
    
    public static final Source<Tweet> tweets = Source.from(
      Arrays.asList(new Tweet[] {
        new Tweet(new Author("rolandkuhn"), System.currentTimeMillis(), "#akka rocks!"), 
        new Tweet(new Author("patriknw"), System.currentTimeMillis(), "#akka !"), 
        new Tweet(new Author("bantonsson"), System.currentTimeMillis(), "#akka !"), 
        new Tweet(new Author("drewhk"), System.currentTimeMillis(), "#akka !"), 
        new Tweet(new Author("ktosopl"), System.currentTimeMillis(), "#akka on the rocks!"), 
        new Tweet(new Author("mmartynas"), System.currentTimeMillis(), "wow #akka !"), 
        new Tweet(new Author("akkateam"), System.currentTimeMillis(), "#akka rocks!"), 
        new Tweet(new Author("bananaman"), System.currentTimeMillis(), "#bananas rock!"), 
        new Tweet(new Author("appleman"), System.currentTimeMillis(), "#apples rock!"), 
        new Tweet(new Author("drama"), System.currentTimeMillis(), "we compared #apples to #oranges!")
      }));
  }
  
  static abstract class Example0 {
    //#tweet-source
    Source<Tweet> tweets; 
    //#tweet-source
  }
  
  static abstract class Example1 {
    //#materializer-setup
    final ActorSystem system = ActorSystem.create("reactive-tweets");
    final FlowMaterializer mat = FlowMaterializer.create(system);
    //#materializer-setup
  }
  
  static class Example2 {
    public void run(final FlowMaterializer mat) throws TimeoutException, InterruptedException {
      //#backpressure-by-readline
      final Future<?> completion =
        Source.from(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
          .map(i -> { System.out.println("map => " + i); return i; })
          .foreach(i -> System.console().readLine("Element = %s continue reading? [press enter]\n", i), mat);
  
      Await.ready(completion, FiniteDuration.create(1, TimeUnit.MINUTES));
      //#backpressure-by-readline
    }
  }
  
  
  final FlowMaterializer mat = FlowMaterializer.create(system);
  
  @Test
  public void demonstrateFilterAndMap() {
    //#authors-filter-map
    final Source<Author> authors =
      tweets
        .filter(t -> t.hashtags().contains(AKKA))
        .map(t -> t.author);
    //#authors-filter-map

    new Object() {
      //#authors-collect
      JavaPartialFunction<Tweet, Author> collectFunction = 
        new JavaPartialFunction<Tweet, Author>() {
          public Author apply(Tweet t, boolean isCheck) {
            if (t.hashtags().contains(AKKA)) {
              if (isCheck) return null; // to spare the expensive or side-effecting code
              return t.author;
            } else {
              throw noMatch();
            }
          }
        };
      
      final Source<Author> authors =
        tweets.collect(collectFunction);
      //#authors-collect
    };

    //#authors-foreachsink-println
    authors.runWith(Sink.foreach(a -> System.out.println(a)), mat);
    //#authors-foreachsink-println

    //#authors-foreach-println
    authors.foreach(a -> System.out.println(a), mat);
    //#authors-foreach-println
  }
  
  @Test
  public void demonstrateMapConcat() {
    //#hashtags-mapConcat
    final Source<Hashtag> hashtags = 
      tweets.mapConcat(t -> new ArrayList<Hashtag>(t.hashtags()));
    //#hashtags-mapConcat
  }
  
  static abstract class HiddenDefinitions {
    //#flow-graph-broadcast
    Sink<Author> writeAuthors; 
    Sink<Hashtag> writeHashtags;
    //#flow-graph-broadcast
  }
  
  @Test
  public void demonstrateBroadcast() {
    final Sink<Author> writeAuthors = Sink.ignore();
    final Sink<Hashtag> writeHashtags = Sink.ignore();

    //#flow-graph-broadcast
    final Broadcast<Tweet> b = Broadcast.create();
    final FlowGraph g = FlowGraph.builder()
      .addEdge(tweets, b)  
      .addEdge(b, Flow.<Tweet>empty().map(t -> t.author), writeAuthors)
      .addEdge(b, Flow.<Tweet>empty().mapConcat(t -> new ArrayList<Hashtag>(t.hashtags())), 
          writeHashtags)
      .build();
    
    g.run(mat);
    //#flow-graph-broadcast
  }
  
  long slowComputation(Tweet t) {
    try {
      // act as if performing some heavy computation
      Thread.sleep(500);
    } catch (InterruptedException e) {} 
    return 42;
  }
  
  @Test
  public void demonstrateSlowProcessing() {
    //#tweets-slow-consumption-dropHead
    tweets
      .buffer(10, OverflowStrategy.dropHead())
      .map(t -> slowComputation(t))
      .runWith(Sink.ignore(), mat);
    //#tweets-slow-consumption-dropHead
  }
  
  @Test
  public void demonstrateCountOnFiniteStream() {
    //#tweets-fold-count
    final KeyedSink<Integer, Future<Integer>> sumSink = 
      Sink.<Integer, Integer>fold(0, (acc, elem) -> acc + elem);

    final RunnableFlow counter = tweets.map(t -> 1).to(sumSink);
    final MaterializedMap map = counter.run(mat);

    final Future<Integer> sum = map.get(sumSink);

    sum.foreach(new Foreach<Integer>() {
      public void each(Integer c) {
        System.out.println("Total tweets processed: " + c);
      }
    }, system.dispatcher());
    //#tweets-fold-count
    
    new Object() {
      //#tweets-fold-count-oneline
      final Future<Integer> sum = tweets.map(t -> 1).runWith(sumSink, mat);
      //#tweets-fold-count-oneline
    };
  }
  
  @Test
  public void demonstrateMaterializeMultipleTimes() {
    final Source<Tweet> tweetsInMinuteFromNow = tweets; // not really in second, just acting as if

    //#tweets-runnable-flow-materialized-twice
    final KeyedSink<Integer, Future<Integer>> sumSink = 
      Sink.<Integer, Integer>fold(0, (acc, elem) -> acc + elem);
    final RunnableFlow counterRunnableFlow =
      tweetsInMinuteFromNow
        .filter(t -> t.hashtags().contains(AKKA))
        .map(t -> 1)
        .to(sumSink);

    // materialize the stream once in the morning
    final MaterializedMap morningMaterialized = counterRunnableFlow.run(mat);
    // and once in the evening, reusing the
    final MaterializedMap eveningMaterialized = counterRunnableFlow.run(mat);

    // the sumSink materialized two different futures
    // we use it as key to get the materialized value out of the materialized map
    final Future<Integer> morningTweetsCount = morningMaterialized.get(sumSink);
    final Future<Integer> eveningTweetsCount = eveningMaterialized.get(sumSink);
    //#tweets-runnable-flow-materialized-twice

    final MaterializedMap map = counterRunnableFlow.run(mat);

    final Future<Integer> sum = map.get(sumSink);
    
    sum.foreach(new Foreach<Integer>() {
      public void each(Integer c) {
        System.out.println("Total tweets processed: " + c);
      }
    }, system.dispatcher());
    
  }

}
