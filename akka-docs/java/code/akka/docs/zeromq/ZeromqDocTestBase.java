/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.zeromq;

//#pub-socket
import akka.zeromq.Bind;
import akka.zeromq.ZeroMQExtension;

//#pub-socket
//#sub-socket
import akka.zeromq.Connect;
import akka.zeromq.Listener;
import akka.zeromq.Subscribe;

//#sub-socket
//#unsub-topic-socket
import akka.zeromq.Unsubscribe;

//#unsub-topic-socket
//#pub-topic
import akka.zeromq.Frame;
import akka.zeromq.ZMQMessage;

//#pub-topic

import akka.zeromq.HighWatermark;
import akka.zeromq.SocketOption;
import akka.zeromq.ZeroMQVersion;

//#health
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.util.Duration;
import akka.serialization.SerializationExtension;
import akka.serialization.Serialization;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
//#health

import com.typesafe.config.ConfigFactory;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.concurrent.TimeUnit;
import java.util.Date;
import java.text.SimpleDateFormat;

import akka.actor.ActorSystem;
import akka.testkit.AkkaSpec;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.Assume;

import akka.zeromq.SocketType;

public class ZeromqDocTestBase {

  ActorSystem system;

  @Before
  public void setUp() {
    system = ActorSystem.create("ZeromqDocTest",
        ConfigFactory.parseString("akka.loglevel=INFO").withFallback(AkkaSpec.testConf()));
  }

  @After
  public void tearDown() {
    system.shutdown();
  }

  @Test
  public void demonstrateCreateSocket() {
    Assume.assumeTrue(checkZeroMQInstallation());

    //#pub-socket
    ActorRef pubSocket = ZeroMQExtension.get(system).newPubSocket(new Bind("tcp://127.0.0.1:1233"));
    //#pub-socket

    //#sub-socket
    ActorRef listener = system.actorOf(new Props(ListenerActor.class));
    ActorRef subSocket = ZeroMQExtension.get(system).newSubSocket(new Connect("tcp://127.0.0.1:1233"),
        new Listener(listener), Subscribe.all());
    //#sub-socket

    //#sub-topic-socket
    ActorRef subTopicSocket = ZeroMQExtension.get(system).newSubSocket(new Connect("tcp://127.0.0.1:1233"),
        new Listener(listener), new Subscribe("foo.bar"));
    //#sub-topic-socket

    //#unsub-topic-socket
    subTopicSocket.tell(new Unsubscribe("foo.bar"));
    //#unsub-topic-socket

    byte[] payload = new byte[0];
    //#pub-topic
    pubSocket.tell(new ZMQMessage(new Frame("foo.bar"), new Frame(payload)));
    //#pub-topic

    //#high-watermark
    ActorRef highWatermarkSocket = ZeroMQExtension.get(system).newRouterSocket(
        new SocketOption[] { new Listener(listener), new Bind("tcp://127.0.0.1:1233"), new HighWatermark(50000) });
    //#high-watermark
  }

  @Test
  public void demonstratePubSub() throws Exception {
    Assume.assumeTrue(checkZeroMQInstallation());

    //#health2

    system.actorOf(new Props(HealthProbe.class), "health");
    //#health2

    //#logger2

    system.actorOf(new Props(Logger.class), "logger");
    //#logger2

    //#alerter2

    system.actorOf(new Props(HeapAlerter.class), "alerter");
    //#alerter2

    Thread.sleep(3000L);
  }

  private boolean checkZeroMQInstallation() {
    try {
      ZeroMQVersion v = ZeroMQExtension.get(system).version();
      return (v.major() == 2 && v.minor() == 1);
    } catch (LinkageError e) {
      return false;
    }
  }

  //#listener-actor
  public static class ListenerActor extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      //...
    }
  }

  //#listener-actor

  //#health

  public static final Object TICK = "TICK";

  public static class Heap implements Serializable {
    public final long timestamp;
    public final long used;
    public final long max;

    public Heap(long timestamp, long used, long max) {
      this.timestamp = timestamp;
      this.used = used;
      this.max = max;
    }
  }

  public static class Load implements Serializable {
    public final long timestamp;
    public final double loadAverage;

    public Load(long timestamp, double loadAverage) {
      this.timestamp = timestamp;
      this.loadAverage = loadAverage;
    }
  }

  public static class HealthProbe extends UntypedActor {

    ActorRef pubSocket = ZeroMQExtension.get(getContext().system()).newPubSocket(new Bind("tcp://127.0.0.1:1237"));
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    Serialization ser = SerializationExtension.get(getContext().system());

    @Override
    public void preStart() {
      getContext().system().scheduler()
          .schedule(Duration.parse("1 second"), Duration.parse("1 second"), getSelf(), TICK);
    }

    @Override
    public void postRestart(Throwable reason) {
      // don't call preStart
    }

    @Override
    public void onReceive(Object message) {
      if (message.equals(TICK)) {
        MemoryUsage currentHeap = memory.getHeapMemoryUsage();
        long timestamp = System.currentTimeMillis();

        // use akka SerializationExtension to convert to bytes
        byte[] heapPayload = ser.serializerFor(Heap.class).toBinary(
            new Heap(timestamp, currentHeap.getUsed(), currentHeap.getMax()));
        // the first frame is the topic, second is the message
        pubSocket.tell(new ZMQMessage(new Frame("health.heap"), new Frame(heapPayload)));

        // use akka SerializationExtension to convert to bytes
        byte[] loadPayload = ser.serializerFor(Load.class).toBinary(new Load(timestamp, os.getSystemLoadAverage()));
        // the first frame is the topic, second is the message
        pubSocket.tell(new ZMQMessage(new Frame("health.load"), new Frame(loadPayload)));
      } else {
        unhandled(message);
      }
    }

  }

  //#health

  //#logger
  public static class Logger extends UntypedActor {

    ActorRef subSocket = ZeroMQExtension.get(getContext().system()).newSubSocket(new Connect("tcp://127.0.0.1:1237"),
        new Listener(getSelf()), new Subscribe("health"));
    Serialization ser = SerializationExtension.get(getContext().system());
    SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    @Override
    public void onReceive(Object message) {
      if (message instanceof ZMQMessage) {
        ZMQMessage m = (ZMQMessage) message;
        // the first frame is the topic, second is the message
        if (m.firstFrameAsString().equals("health.heap")) {
          Heap heap = (Heap) ser.serializerFor(Heap.class).fromBinary(m.payload(1));
          log.info("Used heap {} bytes, at {}", heap.used, timestampFormat.format(new Date(heap.timestamp)));
        } else if (m.firstFrameAsString().equals("health.load")) {
          Load load = (Load) ser.serializerFor(Load.class).fromBinary(m.payload(1));
          log.info("Load average {}, at {}", load.loadAverage, timestampFormat.format(new Date(load.timestamp)));
        }
      } else {
        unhandled(message);
      }
    }

  }

  //#logger

  //#alerter
  public static class HeapAlerter extends UntypedActor {

    ActorRef subSocket = ZeroMQExtension.get(getContext().system()).newSubSocket(new Connect("tcp://127.0.0.1:1237"),
        new Listener(getSelf()), new Subscribe("health.heap"));
    Serialization ser = SerializationExtension.get(getContext().system());
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    int count = 0;

    @Override
    public void onReceive(Object message) {
      if (message instanceof ZMQMessage) {
        ZMQMessage m = (ZMQMessage) message;
        // the first frame is the topic, second is the message
        if (m.firstFrameAsString().equals("health.heap")) {
          Heap heap = (Heap) ser.serializerFor(Heap.class).fromBinary(m.payload(1));
          if (((double) heap.used / heap.max) > 0.9) {
            count += 1;
          } else {
            count = 0;
          }
          if (count > 10) {
            log.warning("Need more memory, using {} %", (100.0 * heap.used / heap.max));
          }
        }
      } else {
        unhandled(message);
      }
    }

  }
  //#alerter

}
