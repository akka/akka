/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.ddata;

import java.util.HashSet;
import java.util.Arrays;
import java.util.Set;
import java.math.BigInteger;
import java.util.Optional;

import akka.actor.*;
import com.typesafe.config.ConfigFactory;
import docs.AbstractJavaTest;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.Assert.assertEquals;
import scala.PartialFunction;
import org.junit.Test;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import scala.concurrent.duration.FiniteDuration;
import java.util.concurrent.ThreadLocalRandom;

import akka.cluster.Cluster;
import akka.cluster.ddata.*;
import akka.japi.pf.ReceiveBuilder;

import static akka.cluster.ddata.Replicator.*;

import akka.testkit.AkkaSpec;
import akka.testkit.ImplicitSender;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.serialization.SerializationExtension;

public class DistributedDataDocTest extends AbstractJavaTest {
  
  static ActorSystem system;
  
  void receive(PartialFunction<Object, BoxedUnit> pf) {
  }


  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("DistributedDataDocTest", 
        ConfigFactory.parseString(DistributedDataDocSpec.config()));
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  @Test
  public void demonstrateUpdate() {
    new JavaTestKit(system) {
      {
        //#update
        final Cluster node = Cluster.get(system);
        final ActorRef replicator = DistributedData.get(system).replicator();

        final Key<PNCounter> counter1Key = PNCounterKey.create("counter1");
        final Key<GSet<String>> set1Key = GSetKey.create("set1");
        final Key<ORSet<String>> set2Key = ORSetKey.create("set2");
        final Key<Flag> activeFlagKey = FlagKey.create("active");

        replicator.tell(new Replicator.Update<PNCounter>(counter1Key, PNCounter.create(),
            Replicator.writeLocal(), curr -> curr.increment(node, 1)), getTestActor());

        final WriteConsistency writeTo3 = new WriteTo(3, Duration.create(1, SECONDS));
        replicator.tell(new Replicator.Update<GSet<String>>(set1Key, GSet.create(),
            writeTo3, curr -> curr.add("hello")), getTestActor());

        final WriteConsistency writeMajority =
            new WriteMajority(Duration.create(5, SECONDS));
        replicator.tell(new Replicator.Update<ORSet<String>>(set2Key, ORSet.create(),
            writeMajority, curr -> curr.add(node, "hello")), getTestActor());

        final WriteConsistency writeAll = new WriteAll(Duration.create(5, SECONDS));
        replicator.tell(new Replicator.Update<Flag>(activeFlagKey, Flag.create(),
            writeAll, curr -> curr.switchOn()), getTestActor());
        //#update

        expectMsgClass(UpdateSuccess.class);
        //#update-response1
        receive(ReceiveBuilder.
            match(UpdateSuccess.class, a -> a.key().equals(counter1Key), a -> {
              // ok
            }).build());
        //#update-response1

        //#update-response2
        receive(ReceiveBuilder.
            match(UpdateSuccess.class, a -> a.key().equals(set1Key), a -> {
              // ok
            }).
            match(UpdateTimeout.class, a -> a.key().equals(set1Key), a -> {
              // write to 3 nodes failed within 1.second
            }).build());
        //#update-response2
      }};
  }

  @Test
  public void demonstrateUpdateWithRequestContext() {
    new JavaTestKit(system) {
      {

        //#update-request-context
        final Cluster node = Cluster.get(system);
        final ActorRef replicator = DistributedData.get(system).replicator();

        final WriteConsistency writeTwo = new WriteTo(2, Duration.create(3, SECONDS));
        final Key<PNCounter> counter1Key = PNCounterKey.create("counter1");

        receive(ReceiveBuilder.
            match(String.class, a -> a.equals("increment"), a -> {
              // incoming command to increase the counter
              Optional<Object> reqContext = Optional.of(getRef());
              Replicator.Update<PNCounter> upd = new Replicator.Update<PNCounter>(counter1Key,
                  PNCounter.create(), writeTwo, reqContext, curr -> curr.increment(node, 1));
              replicator.tell(upd, getTestActor());
            }).

            match(UpdateSuccess.class, a -> a.key().equals(counter1Key), a -> {
              ActorRef replyTo = (ActorRef) a.getRequest().get();
              replyTo.tell("ack", getTestActor());
            }).

            match(UpdateTimeout.class, a -> a.key().equals(counter1Key), a -> {
              ActorRef replyTo = (ActorRef) a.getRequest().get();
              replyTo.tell("nack", getTestActor());
            }).build());

        //#update-request-context
      }
    };
  }

  @SuppressWarnings({ "unused", "unchecked" })
  @Test
  public void demonstrateGet() {
    new JavaTestKit(system) {
      {

        //#get
        final ActorRef replicator = DistributedData.get(system).replicator();
        final Key<PNCounter> counter1Key = PNCounterKey.create("counter1");
        final Key<GSet<String>> set1Key = GSetKey.create("set1");
        final Key<ORSet<String>> set2Key = ORSetKey.create("set2");
        final Key<Flag> activeFlagKey = FlagKey.create("active");

        replicator.tell(new Replicator.Get<PNCounter>(counter1Key,
            Replicator.readLocal()), getTestActor());

        final ReadConsistency readFrom3 = new ReadFrom(3, Duration.create(1, SECONDS));
        replicator.tell(new Replicator.Get<GSet<String>>(set1Key,
            readFrom3), getTestActor());

        final ReadConsistency readMajority = new ReadMajority(Duration.create(5, SECONDS));
        replicator.tell(new Replicator.Get<ORSet<String>>(set2Key,
            readMajority), getTestActor());

        final ReadConsistency readAll = new ReadAll(Duration.create(5, SECONDS));
        replicator.tell(new Replicator.Get<Flag>(activeFlagKey,
            readAll), getTestActor());
        //#get

        //#get-response1
        receive(ReceiveBuilder.
            match(GetSuccess.class, a -> a.key().equals(counter1Key), a -> {
              GetSuccess<PNCounter> g = a;
              BigInteger value = g.dataValue().getValue();
            }).
            match(NotFound.class, a -> a.key().equals(counter1Key), a -> {
              // key counter1 does not exist
            }).build());
        //#get-response1

        //#get-response2
        receive(ReceiveBuilder.
            match(GetSuccess.class, a -> a.key().equals(set1Key), a -> {
              GetSuccess<GSet<String>> g = a;
              Set<String> value = g.dataValue().getElements();
            }).
            match(GetFailure.class, a -> a.key().equals(set1Key), a -> {
              // read from 3 nodes failed within 1.second
            }).
            match(NotFound.class, a -> a.key().equals(set1Key), a -> {
              // key set1 does not exist
            }).build());
        //#get-response2
      }
    };
  }

  @SuppressWarnings("unchecked")
  @Test
  public void demonstrateGetWithRequestContext() {
    new JavaTestKit(system) {
      {

        //#get-request-context
        final ActorRef replicator = DistributedData.get(system).replicator();
        final ReadConsistency readTwo = new ReadFrom(2, Duration.create(3, SECONDS));
        final Key<PNCounter> counter1Key = PNCounterKey.create("counter1");

        receive(ReceiveBuilder.
            match(String.class, a -> a.equals("get-count"), a -> {
              // incoming request to retrieve current value of the counter
              Optional<Object> reqContext = Optional.of(getTestActor());
              replicator.tell(new Replicator.Get<PNCounter>(counter1Key,
                  readTwo), getTestActor());
            }).

            match(GetSuccess.class, a -> a.key().equals(counter1Key), a -> {
              ActorRef replyTo = (ActorRef) a.getRequest().get();
              GetSuccess<PNCounter> g = a;
              long value = g.dataValue().getValue().longValue();
              replyTo.tell(value, getTestActor());
            }).

            match(GetFailure.class, a -> a.key().equals(counter1Key), a -> {
              ActorRef replyTo = (ActorRef) a.getRequest().get();
              replyTo.tell(-1L, getTestActor());
            }).

            match(NotFound.class, a -> a.key().equals(counter1Key), a -> {
              ActorRef replyTo = (ActorRef) a.getRequest().get();
              replyTo.tell(0L, getTestActor());
            }).build());
        //#get-request-context
      }
    };
  }

  @SuppressWarnings("unchecked")
  abstract class MyActor extends AbstractActor {
    //#subscribe
    final ActorRef replicator = DistributedData.get(system).replicator();
    final Key<PNCounter> counter1Key = PNCounterKey.create("counter1");
    
    BigInteger currentValue = BigInteger.valueOf(0);
    
    public MyActor() {
      receive(ReceiveBuilder.
        match(Changed.class, a -> a.key().equals(counter1Key), a -> {
          Changed<PNCounter> g = a;
          currentValue = g.dataValue().getValue();
        }).
          
        match(String.class, a -> a.equals("get-count"), a -> {
          // incoming request to retrieve current value of the counter
          sender().tell(currentValue, sender());
        }).build());
    }
    
    public void preStart() {
      // subscribe to changes of the Counter1Key value
      replicator.tell(new Subscribe<PNCounter>(counter1Key, self()), ActorRef.noSender());
    }

    //#subscribe
  }

  @Test
  public void demonstrateDelete() {
    new JavaTestKit(system) {
      {
        //#delete
        final ActorRef replicator = DistributedData.get(system).replicator();
        final Key<PNCounter> counter1Key = PNCounterKey.create("counter1");
        final Key<ORSet<String>> set2Key = ORSetKey.create("set2");

        replicator.tell(new Delete<PNCounter>(counter1Key,
            Replicator.writeLocal()), getTestActor());

        final WriteConsistency writeMajority =
            new WriteMajority(Duration.create(5, SECONDS));
        replicator.tell(new Delete<PNCounter>(counter1Key,
            writeMajority), getTestActor());
        //#delete
      }};
  }

  public void demonstratePNCounter() {
    //#pncounter
    final Cluster node = Cluster.get(system);
    final PNCounter c0 = PNCounter.create();
    final PNCounter c1 = c0.increment(node, 1);
    final PNCounter c2 = c1.increment(node, 7);
    final PNCounter c3 = c2.decrement(node, 2);
    System.out.println(c3.value()); // 6
    //#pncounter
  }

  public void demonstratePNCounterMap() {
    //#pncountermap
    final Cluster node = Cluster.get(system);
    final PNCounterMap m0 = PNCounterMap.create();
    final PNCounterMap m1 = m0.increment(node, "a", 7);
    final PNCounterMap m2 = m1.decrement(node, "a", 2);
    final PNCounterMap m3 = m2.increment(node, "b", 1);
    System.out.println(m3.get("a")); // 5
    System.out.println(m3.getEntries());
    //#pncountermap
  }

  public void demonstrateGSet() {
    //#gset
    final GSet<String> s0 = GSet.create();
    final GSet<String> s1 = s0.add("a");
    final GSet<String> s2 = s1.add("b").add("c");
    if (s2.contains("a"))
      System.out.println(s2.getElements());  // a, b, c
    //#gset
  }

  public void demonstrateORSet() {
    //#orset
    final Cluster node = Cluster.get(system);
    final ORSet<String> s0 = ORSet.create();
    final ORSet<String> s1 = s0.add(node, "a");
    final ORSet<String> s2 = s1.add(node, "b");
    final ORSet<String> s3 = s2.remove(node, "a");
    System.out.println(s3.getElements()); // b
    //#orset
  }

  public void demonstrateORMultiMap() {
    //#ormultimap
    final Cluster node = Cluster.get(system);
    final ORMultiMap<Integer> m0 = ORMultiMap.create();
    final ORMultiMap<Integer> m1 = m0.put(node, "a", 
        new HashSet<Integer>(Arrays.asList(1, 2, 3)));
    final ORMultiMap<Integer> m2 = m1.addBinding(node, "a", 4);
    final ORMultiMap<Integer> m3 = m2.removeBinding(node, "a", 2);
    final ORMultiMap<Integer> m4 = m3.addBinding(node, "b", 1);
    System.out.println(m4.getEntries());
    //#ormultimap
  }

  public void demonstrateFlag() {
    //#flag
    final Flag f0 = Flag.create();
    final Flag f1 = f0.switchOn();
    System.out.println(f1.enabled());
    //#flag
  }

  @Test
  public void demonstrateLWWRegister() {
    //#lwwregister
    final Cluster node = Cluster.get(system);
    final LWWRegister<String> r1 = LWWRegister.create(node, "Hello");
    final LWWRegister<String> r2 = r1.withValue(node, "Hi");
    System.out.println(r1.value() + " by " + r1.updatedBy() + " at " + r1.timestamp());
    //#lwwregister
    assertEquals("Hi", r2.value());
  }
  
  static
  //#lwwregister-custom-clock
  class Record {
    public final int version;
    public final String name;
    public final String address;

    public Record(int version, String name, String address) {
      this.version = version;
      this.name = name;
      this.address = address;
    }
  }
  
  //#lwwregister-custom-clock
  
  public void demonstrateLWWRegisterWithCustomClock() {
    //#lwwregister-custom-clock

    final Cluster node = Cluster.get(system);
    final LWWRegister.Clock<Record> recordClock = new LWWRegister.Clock<Record>() {
      @Override
      public long apply(long currentTimestamp, Record value) {
        return value.version;
      }
    };

    final Record record1 = new Record(1, "Alice", "Union Square");
    final LWWRegister<Record> r1 = LWWRegister.create(node, record1);

    final Record record2 = new Record(2, "Alice", "Madison Square");
    final LWWRegister<Record> r2 = LWWRegister.create(node, record2);

    final LWWRegister<Record> r3 = r1.merge(r2);
    System.out.println(r3.value());
    //#lwwregister-custom-clock

    assertEquals("Madison Square", r3.value().address);
  }

}
