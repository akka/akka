/**
 * Copyright (C) 2013 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.io.japi;

import java.nio.ByteOrder;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorContext;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.io.AbstractPipelineContext;
import akka.io.PipelineFactory;
import akka.io.PipelineInjector;
import akka.io.PipelineSink;
import akka.io.PipelineStage;
import akka.util.ByteString;
import scala.concurrent.duration.*;

//#actor
public class Processor extends UntypedActor {

  private class Context extends AbstractPipelineContext 
      implements HasByteOrder, HasActorContext {

    @Override
    public ActorContext getContext() {
      return Processor.this.getContext();
    }

    @Override
    public ByteOrder byteOrder() {
      return java.nio.ByteOrder.BIG_ENDIAN;
    }

  }

  final Context ctx = new Context();

  final FiniteDuration interval = Duration.apply(1, TimeUnit.SECONDS);

  final PipelineStage<Context, Message, ByteString, Message, ByteString> stages =
    PipelineStage.sequence(
        // Java 7 can infer these types, Java 6 cannot
    PipelineStage.<Context, Message, Message, ByteString, Message, Message, 
        ByteString> sequence( //
      new TickGenerator<Message, Message>(interval), //
      new MessageStage()), //
      new LengthFieldFrame(10000));

  private final ActorRef evts;
  private final ActorRef cmds;

  final PipelineInjector<Message, ByteString> injector = PipelineFactory
      .buildWithSink(ctx, stages, new PipelineSink<ByteString, Message>() {

        @Override
        public void onCommand(ByteString cmd) {
          cmds.tell(cmd, getSelf());
        }

        @Override
        public void onEvent(Message evt) {
          evts.tell(evt, getSelf());
        }
      });

  public Processor(ActorRef cmds, ActorRef evts) throws Exception {
    this.cmds = cmds;
    this.evts = evts;
  }
  
  //#omitted
  @Override
  public void preStart() throws Exception {
    injector.managementCommand(new PipelineTest.SetTarget(cmds));
  }
  //#omitted
  
  @Override
  public void onReceive(Object obj) throws Exception {
    if (obj instanceof Message) {
      injector.injectCommand((Message) obj);
    } else if (obj instanceof ByteString) {
      injector.injectEvent((ByteString) obj);
    } else if (obj instanceof TickGenerator.Trigger) {
      injector.managementCommand(obj);
    }
  }

}
//#actor

