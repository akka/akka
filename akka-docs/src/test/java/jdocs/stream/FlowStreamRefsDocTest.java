/**
 * Copyright (C) 2015-2018 Lightbend Inc. <http://www.lightbend.com/>
 */
package jdocs.stream;

import akka.NotUsed;
import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.remote.WireFormats;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.TestProbe;
import akka.testkit.javadsl.TestKit;
import jdocs.AbstractJavaTest;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class FlowStreamRefsDocTest extends AbstractJavaTest {

  static ActorSystem system = null;
  static Materializer mat = null;

  @Test
  public void compileOnlySpec() {
    // do nothing
  }

  //#offer-source
  static class RequestLogs {
    public final long streamId;

    public RequestLogs(long streamId) {
      this.streamId = streamId;
    }
  }

  static class LogsOffer {
    final SourceRef<String> sourceRef;

    public LogsOffer(SourceRef<String> sourceRef) {
      this.sourceRef = sourceRef;
    }
  }

  static class DataSource extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(RequestLogs.class, this::handleRequestLogs)
          .build();
    }

    private void handleRequestLogs(RequestLogs requestLogs) {
      Source<String, NotUsed> logs = streamLogs(requestLogs.streamId);
      SourceRef<String> logsRef = logs.runWith(Sink.sourceRef(), mat);
      LogsOffer offer = new LogsOffer(logsRef);
      sender().tell(offer, self());
    }

    private Source<String, NotUsed> streamLogs(long streamId) {
      return Source.repeat("[INFO] some interesting logs here (for id: " + streamId + ")");
    }
  }
  //#offer-source

  public void offerSource() {
    new TestKit(system) {{

      //#offer-source-use
      ActorRef sourceActor = system.actorOf(Props.create(DataSource.class), "dataSource");

      sourceActor.tell(new RequestLogs(1337), getTestActor());
      LogsOffer offer = expectMsgClass(LogsOffer.class);

      offer.sourceRef.getSource()
          .runWith(Sink.foreach(log -> System.out.println(log)), mat);

      //#offer-source-use
    }};
  }

  //#offer-sink
  class PrepareUpload {
    final String id;

    public PrepareUpload(String id) {
      this.id = id;
    }
  }
  class MeasurementsSinkReady {
    final String id;
    final SinkRef<String> sinkRef;

    public PrepareUpload(String id, SinkRef<String> ref) {
      this.id = id;
      this.sinkRef = ref;
    }
  }

  static class DataReceiver extends AbstractActor {
    @Override
    public Receive createReceive() {
      return receiveBuilder()
          .match(PrepareUpload.class, prepare -> {
            Sink<String, NotUsed> sink = logsSinkFor(prepare.id);
            SinkRef<String> sinkRef = Source.sinkRef().to(sink).run(mat);

            sender().tell(new MeasurementsSinkReady(sinkRef), self());
          })
          .create();
    }

    private Sink<String, NotUsed> logsSinkFor(String id) {
      return Sink.ignore(); // would be actual useful Sink in reality
    }
  }
  //#offer-sink

  public void offerSink() {
    new TestKit(system) {{

      //#offer-sink-use
      ActorRef receiver = system.actorOf(Props.create(DataReceiver.class), "dataReceiver");

      receiver.tell(new PrepareUpload("system-42-tmp"), getTestActor());
      MeasurementsSinkReady ready = expectMsgClass(LogsOffer.class);

      Source.repeat("hello")
          .runWith(ready.sinkRef, mat);
      //#offer-sink-use
    }};
  }

  public void configureTimeouts() {
    new TestKit(system) {{

      //#attr-sub-timeout
      FiniteDuration timeout = FiniteDuration.create(5, TimeUnit.SECONDS);
      Attributes timeoutAttributes = StreamRefAttributes.subscriptionTimeout(timeout);

      // configuring Sink.sourceRef (notice that we apply the attributes to the Sink!):
      Source.repeat("hello")
          .runWith(Sink.sourceRef().addAttributes(timeoutAttributes), mat);

      // configuring SinkRef.source:
      Source.sinkRef().addAttributes(timeoutAttributes)
          .runWith(Sink.ignore(), mat); // not very interesting sink, just an example

      //#attr-sub-timeout
    }};
  }

}
