/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.zeromq;

//#import-pub-socket
import akka.zeromq.Bind;
import akka.zeromq.ZeroMQExtension;
//#import-pub-socket
//#import-sub-socket
import akka.zeromq.Connect;
import akka.zeromq.Listener;
import akka.zeromq.Subscribe;
//#import-sub-socket
//#import-unsub-topic-socket
import akka.zeromq.Unsubscribe;
//#import-unsub-topic-socket
//#import-pub-topic
import akka.util.ByteString;
import akka.zeromq.ZMQMessage;
//#import-pub-topic

import akka.zeromq.HighWatermark;
import akka.zeromq.SocketOption;
import akka.zeromq.ZeroMQVersion;

//#import-health
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import org.junit.*;
import scala.concurrent.duration.Duration;
import akka.serialization.SerializationExtension;
import akka.serialization.Serialization;
import java.io.Serializable;
import java.lang.management.ManagementFactory;
//#import-health

import com.typesafe.config.ConfigFactory;

import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.util.Date;
import java.text.SimpleDateFormat;

import akka.actor.ActorSystem;
import akka.testkit.AkkaSpec;
import akka.testkit.AkkaJUnitActorSystemResource;

public class ZeromqDocTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("ZeromqDocTest",
      ConfigFactory.parseString("akka.loglevel=INFO").withFallback(AkkaSpec.testConf()));

  private final ActorSystem system = actorSystemResource.getSystem();

  @SuppressWarnings("unused")
  @Test
  public void demonstrateCreateSocket() {
    Assume.assumeTrue(checkZeroMQInstallation());

    //#pub-socket
    ActorRef pubSocket = ZeroMQExtension.get(system).newPubSocket(
      new Bind("tcp://127.0.0.1:1233"));
    //#pub-socket

    //#sub-socket
    ActorRef listener = system.actorOf(Props.create(ListenerActor.class));
    ActorRef subSocket = ZeroMQExtension.get(system).newSubSocket(
      new Connect("tcp://127.0.0.1:1233"),
      new Listener(listener), Subscribe.all());
    //#sub-socket

    //#sub-topic-socket
    ActorRef subTopicSocket = ZeroMQExtension.get(system).newSubSocket(
      new Connect("tcp://127.0.0.1:1233"),
      new Listener(listener), new Subscribe("foo.bar"));
    //#sub-topic-socket

    //#unsub-topic-socket
    subTopicSocket.tell(new Unsubscribe("foo.bar"), null);
    //#unsub-topic-socket

    byte[] payload = new byte[0];
    //#pub-topic
    pubSocket.tell(ZMQMessage.withFrames(ByteString.fromString("foo.bar"), ByteString.fromArray(payload)), null);
    //#pub-topic

    system.stop(subSocket);
    system.stop(subTopicSocket);

    //#high-watermark
    ActorRef highWatermarkSocket = ZeroMQExtension.get(system).newRouterSocket(
        new SocketOption[] { new Listener(listener),
          new Bind("tcp://127.0.0.1:1233"), new HighWatermark(50000) });
    //#high-watermark
  }

  @Test
  public void demonstratePubSub() throws Exception {
    Assume.assumeTrue(checkZeroMQInstallation());

    //#health2

    system.actorOf(Props.create(HealthProbe.class), "health");
    //#health2

    //#logger2

    system.actorOf(Props.create(Logger.class), "logger");
    //#logger2

    //#alerter2

    system.actorOf(Props.create(HeapAlerter.class), "alerter");
    //#alerter2

    // Let it run for a while to see some output.
    // Don't do like this in real tests, this is only doc demonstration.
    Thread.sleep(3000L);
  }

  private boolean checkZeroMQInstallation() {
    try {
      ZeroMQVersion v = ZeroMQExtension.get(system).version();
      return (v.major() >= 3 || (v.major() >= 2 && v.minor() >= 1));
    } catch (LinkageError e) {
      return false;
    }
  }

  static
  //#listener-actor
  public class ListenerActor extends UntypedActor {
    public void onReceive(Object message) throws Exception {
      //...
    }
  }
  //#listener-actor

  static
  //#health
  public final Object TICK = "TICK";

  //#health
  static
  //#health
  public class Heap implements Serializable {
    private static final long serialVersionUID = 1L;
    public final long timestamp;
    public final long used;
    public final long max;

    public Heap(long timestamp, long used, long max) {
      this.timestamp = timestamp;
      this.used = used;
      this.max = max;
    }
  }

  //#health
  static
  //#health
  public class Load implements Serializable {
    private static final long serialVersionUID = 1L;
    public final long timestamp;
    public final double loadAverage;

    public Load(long timestamp, double loadAverage) {
      this.timestamp = timestamp;
      this.loadAverage = loadAverage;
    }
  }

  //#health
  static
  //#health
  public class HealthProbe extends UntypedActor {

    ActorRef pubSocket = ZeroMQExtension.get(getContext().system()).newPubSocket(
      new Bind("tcp://127.0.0.1:1237"));
    MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    Serialization ser = SerializationExtension.get(getContext().system());

    @Override
    public void preStart() {
      getContext().system().scheduler()
          .schedule(Duration.create(1, "second"), Duration.create(1, "second"),
            getSelf(), TICK, getContext().dispatcher(), null);
    }

    @Override
    public void postRestart(Throwable reason) {
      // don't call preStart, only schedule once
    }

    @Override
    public void onReceive(Object message) {
      if (message.equals(TICK)) {
        MemoryUsage currentHeap = memory.getHeapMemoryUsage();
        long timestamp = System.currentTimeMillis();

        // use akka SerializationExtension to convert to bytes
        ByteString heapTopic = ByteString.fromString("health.heap", "UTF-8");
        ByteString heapPayload = ByteString.fromArray(
                ser.serialize(
                    new Heap(timestamp,
                          currentHeap.getUsed(),
                          currentHeap.getMax())
                ).get());
        // the first frame is the topic, second is the message
        pubSocket.tell(ZMQMessage.withFrames(heapTopic, heapPayload), getSelf());

        // use akka SerializationExtension to convert to bytes
        ByteString loadTopic = ByteString.fromString("health.load", "UTF-8");
        ByteString loadPayload = ByteString.fromArray(
                ser.serialize(new Load(timestamp, os.getSystemLoadAverage())).get()
        );
        // the first frame is the topic, second is the message
        pubSocket.tell(ZMQMessage.withFrames(loadTopic, loadPayload), getSelf());
      } else {
        unhandled(message);
      }
    }

  }
  //#health

  static
  //#logger
  public class Logger extends UntypedActor {

    ActorRef subSocket = ZeroMQExtension.get(getContext().system()).newSubSocket(
      new Connect("tcp://127.0.0.1:1237"),
        new Listener(getSelf()), new Subscribe("health"));
    Serialization ser = SerializationExtension.get(getContext().system());
    SimpleDateFormat timestampFormat = new SimpleDateFormat("HH:mm:ss.SSS");
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    @Override
    public void onReceive(Object message) {
      if (message instanceof ZMQMessage) {
        ZMQMessage m = (ZMQMessage) message;
        String topic = m.frame(0).utf8String();
        // the first frame is the topic, second is the message
        if ("health.heap".equals(topic)) {
          Heap heap = ser.deserialize(m.frame(1).toArray(), Heap.class).get();
          log.info("Used heap {} bytes, at {}", heap.used,
            timestampFormat.format(new Date(heap.timestamp)));
        } else if ("health.load".equals(topic)) {
          Load load = ser.deserialize(m.frame(1).toArray(), Load.class).get();
          log.info("Load average {}, at {}", load.loadAverage,
            timestampFormat.format(new Date(load.timestamp)));
        }
      } else {
        unhandled(message);
      }
    }

  }

  //#logger

  static
  //#alerter
  public class HeapAlerter extends UntypedActor {

    ActorRef subSocket = ZeroMQExtension.get(getContext().system()).newSubSocket(
      new Connect("tcp://127.0.0.1:1237"),
      new Listener(getSelf()), new Subscribe("health.heap"));
    Serialization ser = SerializationExtension.get(getContext().system());
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);
    int count = 0;

    @Override
    public void onReceive(Object message) {
      if (message instanceof ZMQMessage) {
        ZMQMessage m = (ZMQMessage) message;
        String topic = m.frame(0).utf8String();
        // the first frame is the topic, second is the message
        if ("health.heap".equals(topic)) {
          Heap heap = ser.<Heap>deserialize(m.frame(1).toArray(), Heap.class).get();
          if (((double) heap.used / heap.max) > 0.9) {
            count += 1;
          } else {
            count = 0;
          }
          if (count > 10) {
            log.warning("Need more memory, using {} %",
              (100.0 * heap.used / heap.max));
          }
        }
      } else {
        unhandled(message);
      }
    }

  }
  //#alerter

}
