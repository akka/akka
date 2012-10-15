package akka.camel;

import akka.actor.*;
import akka.camel.internal.component.CamelPath;
import akka.camel.javaapi.UntypedConsumerActor;
import akka.camel.javaapi.UntypedProducerActor;
import akka.util.Timeout;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class CustomRouteTestBase {
  private static Camel camel;
  private static ActorSystem system;

  @Before
  public void before() {
    system = ActorSystem.create("test");
    camel = (Camel) CamelExtension.get(system);
  }

  @After
  public void after() {
    system.shutdown();
  }

  @Test
  public void testCustomProducerRoute() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockProducer", MockEndpoint.class);
    ActorRef producer = system.actorOf(new Props(MockEndpointProducer.class), "mockEndpoint");
    camel.context().addRoutes(new CustomRouteBuilder("direct:test",producer));
    camel.template().sendBody("direct:test", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(producer);
  }

  @Test
  public void testCustomProducerUriRoute() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockProducerUri", MockEndpoint.class);
    ActorRef producer = system.actorOf(new Props(new UntypedActorFactory(){
      public Actor create() {
        return new EndpointProducer("mock:mockProducerUri");
      }
    }), "mockEndpointUri");
    camel.context().addRoutes(new CustomRouteBuilder("direct:test",producer));
    camel.template().sendBody("direct:test", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(producer);
  }

  @Test
  public void testCustomConsumerRoute() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockConsumer", MockEndpoint.class);
    FiniteDuration duration = Duration.create(10, TimeUnit.SECONDS);
    Timeout timeout = new Timeout(duration);
    ExecutionContext executionContext = system.dispatcher();
    ActorRef consumer = Await.result(
            camel.activationFutureFor(system.actorOf(new Props(TestConsumer.class), "testConsumer"), timeout, executionContext),
            duration);
    camel.context().addRoutes(new CustomRouteBuilder("direct:testRouteConsumer",consumer));
    camel.template().sendBody("direct:testRouteConsumer", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(consumer);
  }

  @Test
  public void testCustomAckConsumerRoute() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockAck", MockEndpoint.class);
    FiniteDuration duration = Duration.create(10, TimeUnit.SECONDS);
    Timeout timeout = new Timeout(duration);
    ExecutionContext executionContext = system.dispatcher();
    ActorRef consumer = Await.result(
      camel.activationFutureFor(
        system.actorOf(
          new Props( new UntypedActorFactory(){ public Actor create() { return new TestAckConsumer("direct:testConsumerAck","mock:mockAck"); } }), "testConsumerAck"),
      timeout, executionContext),
    duration);
    camel.context().addRoutes(new CustomRouteBuilder("direct:testAck", consumer, false, duration));
    camel.template().sendBody("direct:testAck", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(consumer);
  }

  @Test
  public void testCustomAckConsumerRouteFromUri() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockAckUri", MockEndpoint.class);
      ExecutionContext executionContext = system.dispatcher();
    FiniteDuration duration = Duration.create(10, TimeUnit.SECONDS);
    Timeout timeout = new Timeout(duration);
    ActorRef consumer = Await.result(
      camel.activationFutureFor(system.actorOf(new Props( new UntypedActorFactory(){ public Actor create() { return new TestAckConsumer("direct:testConsumerAckFromUri","mock:mockAckUri"); } }), "testConsumerAckUri"),
      timeout, executionContext),
    duration);
    camel.context().addRoutes(new CustomRouteBuilder("direct:testAckFromUri","akka://test/user/testConsumerAckUri?autoAck=false"));
    camel.template().sendBody("direct:testAckFromUri", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(consumer);
  }

  @Test(expected=CamelExecutionException.class)
  public void testCustomTimeoutConsumerRoute() throws Exception {
    FiniteDuration duration = Duration.create(10, TimeUnit.SECONDS);
    Timeout timeout = new Timeout(duration);
    ExecutionContext executionContext = system.dispatcher();
    ActorRef consumer = Await.result(
      camel.activationFutureFor(system.actorOf(new Props( new UntypedActorFactory(){ public Actor create() { return new TestAckConsumer("direct:testConsumerException","mock:mockException"); } }), "testConsumerException"),
      timeout, executionContext),
    duration);
    camel.context().addRoutes(new CustomRouteBuilder("direct:testException", consumer, false, Duration.create(0, TimeUnit.SECONDS)));
    camel.template().sendBody("direct:testException", "test");
  }

  private void assertMockEndpoint(MockEndpoint mockEndpoint) throws InterruptedException {
    mockEndpoint.expectedMessageCount(1);
    mockEndpoint.expectedMessagesMatches(new Predicate() {
      public boolean matches(Exchange exchange) {
        return exchange.getIn().getBody().equals("test");
      }
    });
    mockEndpoint.assertIsSatisfied();
  }

  public static class CustomRouteBuilder extends RouteBuilder {
    private String uri;
    private String fromUri;

    public CustomRouteBuilder(String from, String to) {
      fromUri = from;
      uri = to;
    }

    public CustomRouteBuilder(String from, ActorRef actor) {
      fromUri = from;
      uri = CamelPath.toUri(actor);
    }

    public CustomRouteBuilder(String from, ActorRef actor, boolean autoAck, Duration replyTimeout) {
      fromUri = from;
      uri = CamelPath.toUri(actor, autoAck, replyTimeout);
    }

    @Override
    public void configure() throws Exception {
      from(fromUri).to(uri);
    }
  }


  public static class TestConsumer extends UntypedConsumerActor {
    @Override
    public String getEndpointUri() {
      return "direct:testconsumer";
    }

    @Override
    public void onReceive(Object message) {
      this.getProducerTemplate().sendBody("mock:mockConsumer","test");
    }
  }

  public static class EndpointProducer extends UntypedProducerActor {
    private String uri;

    public EndpointProducer(String uri) {
      this.uri = uri;
    }

    public String getEndpointUri() {
      return uri;
    }

    @Override
    public boolean isOneway() {
      return true;
    }
  }

  public static class MockEndpointProducer extends UntypedProducerActor {
    public String getEndpointUri() {
      return "mock:mockProducer";
    }

    @Override
    public boolean isOneway() {
      return true;
    }
  }

  public static class TestAckConsumer extends UntypedConsumerActor {
    private String myuri;
    private String to;

    public TestAckConsumer(String uri, String to){
      myuri = uri;
      this.to = to;
    }

    @Override
    public String getEndpointUri() {
      return myuri;
    }

    @Override
    public void onReceive(Object message) {
      this.getProducerTemplate().sendBody(to, "test");
      getSender().tell(Ack.getInstance(), getSelf());
    }
  }
}
