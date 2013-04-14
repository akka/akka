/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import scala.concurrent.duration.Duration;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.PoisonPill;
import akka.actor.Props;
import akka.io.AbstractPipelineContext;
import akka.io.PipelineFactory;
import akka.io.PipelineInjector;
import akka.io.PipelineSink;
import akka.io.PipelineStage;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import akka.util.ByteString;

public class PipelineTest {

  //#message
  final Message msg = new Message(
      new Message.Person[] { 
          new Message.Person("Alice", "Gibbons"),
          new Message.Person("Bob", "Sparseley")
      },
      new double[] { 1.0, 3.0, 5.0 });
  //#message
  
  //#byteorder
  class Context extends AbstractPipelineContext implements HasByteOrder {

    @Override
    public ByteOrder byteOrder() {
      return java.nio.ByteOrder.BIG_ENDIAN;
    }
    
  }
  final Context ctx = new Context();
  //#byteorder
  
  static ActorSystem system = null;
  
  @BeforeClass
  public static void setup() {
    system = ActorSystem.create("PipelineTest");
  }
  
  @AfterClass
  public static void teardown() {
    system.shutdown();
  }

  @Test
  public void demonstratePipeline() throws Exception {
    final TestProbe probe = TestProbe.apply(system);
    final ActorRef commandHandler = probe.ref();
    final ActorRef eventHandler = probe.ref();
    //#build-sink
    final PipelineStage<Context, Message, ByteString, Message, ByteString> stages =
        PipelineStage.sequence(
            new MessageStage(),
            new LengthFieldFrame(10000)
        );
    
    final PipelineSink<ByteString, Message> sink =
        new PipelineSink<ByteString, Message>() {

          @Override
          public void onCommand(ByteString cmd) throws Throwable {
            commandHandler.tell(cmd, null);
          }

          @Override
          public void onEvent(Message evt) throws Throwable {
            eventHandler.tell(evt, null);
          }
        };
        
    final PipelineInjector<Message, ByteString> injector =
        PipelineFactory.buildWithSink(ctx, stages, sink);
    
    injector.injectCommand(msg);
    //#build-sink
    final ByteString encoded = probe.expectMsgClass(ByteString.class);
    injector.injectEvent(encoded);
    final Message decoded = probe.expectMsgClass(Message.class);
    assert msg == decoded;
  }
  
  static class SetTarget {
    final ActorRef ref;

    public SetTarget(ActorRef ref) {
      super();
      this.ref = ref;
    }

    public ActorRef getRef() {
      return ref;
    }
  }
  
  @Test
  public void testTick() {
    new JavaTestKit(system) {
      {
        class P extends Processor {
          public P(ActorRef cmds, ActorRef evts) throws Exception {
            super(cmds, evts);
          }

          @Override
          public void onReceive(Object obj) throws Exception {
            if (obj.equals("fail!")) {
              throw new RuntimeException("FAIL!");
            }
            super.onReceive(obj);
          }
          
        }
        
        final ActorRef proc = system.actorOf(Props.create(
            P.class, this, getRef(), getRef()), "processor");
        expectMsgClass(TickGenerator.Tick.class);
        proc.tell(msg, null);
        final ByteString encoded = expectMsgClass(ByteString.class);
        proc.tell(encoded, null);
        final Message decoded = expectMsgClass(Message.class);
        assert msg == decoded;

        new Within(Duration.create(1500, TimeUnit.MILLISECONDS),
            Duration.create(3, TimeUnit.SECONDS)) {
          protected void run() {
            expectMsgClass(TickGenerator.Tick.class);
            expectMsgClass(TickGenerator.Tick.class);
          }
        };
        proc.tell("fail!", null);
        new Within(Duration.create(1700, TimeUnit.MILLISECONDS),
            Duration.create(3, TimeUnit.SECONDS)) {
          protected void run() {
            expectMsgClass(TickGenerator.Tick.class);
            expectMsgClass(TickGenerator.Tick.class);
            proc.tell(PoisonPill.getInstance(), null);
            expectNoMsg();
          }
        };
      }
    };
  }

}
