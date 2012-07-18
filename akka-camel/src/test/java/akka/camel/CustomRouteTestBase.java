package akka.camel;

import akka.actor.*;
import akka.camel.internal.component.CamelPath;
import akka.camel.javaapi.UntypedConsumerActor;
import akka.camel.javaapi.UntypedProducerActor;
import akka.util.FiniteDuration;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

public class CustomRouteTestBase {

  private static Camel camel;
  private static ActorSystem system;

  @BeforeClass
  public static void setUpBeforeClass() {
    system = ActorSystem.create("test");
    camel = (Camel) CamelExtension.get(system);
  }

  @AfterClass
  public static void cleanup() {
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
  public void testCustomConsumerRoute() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockConsumer", MockEndpoint.class);
    ActorRef consumer = system.actorOf(new Props(TestConsumer.class), "testConsumer");
    camel.awaitActivation(consumer,new FiniteDuration(10, TimeUnit.SECONDS));
    camel.context().addRoutes(new CustomRouteBuilder("direct:testConsumer",consumer));
    camel.template().sendBody("direct:testConsumer", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(consumer);
  }

  @Test
  public void testCustomAckConsumerRoute() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockAck", MockEndpoint.class);
    ActorRef consumer = system.actorOf(new Props( new UntypedActorFactory(){
      public Actor create() {
        return new TestAckConsumer("mock:mockAck");
      }
    }), "testConsumerAck");
    camel.awaitActivation(consumer,new FiniteDuration(10, TimeUnit.SECONDS));
    camel.context().addRoutes(new CustomRouteBuilder("direct:testAck", consumer, false, new FiniteDuration(10, TimeUnit.SECONDS)));
    camel.template().sendBody("direct:testAck", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(consumer);
  }

  @Test
  public void testCustomAckConsumerRouteFromUri() throws Exception {
    MockEndpoint mockEndpoint = camel.context().getEndpoint("mock:mockAckUri", MockEndpoint.class);
    ActorRef consumer = system.actorOf(new Props( new UntypedActorFactory(){
      public Actor create() {
        return new TestAckConsumer("mock:mockAckUri");
      }
    }), "testConsumerAckUri");
    camel.awaitActivation(consumer,new FiniteDuration(10, TimeUnit.SECONDS));
    camel.context().addRoutes(new CustomRouteBuilder("direct:testAckFromUri","akka://test/user/testConsumerAckUri?autoAck=false"));
    camel.template().sendBody("direct:testAckFromUri", "test");
    assertMockEndpoint(mockEndpoint);
    system.stop(consumer);
  }

  @Test(expected=CamelExecutionException.class)
  public void testCustomTimeoutConsumerRoute() throws Exception {
    ActorRef consumer = system.actorOf(new Props( new UntypedActorFactory(){
      public Actor create() {
        return new TestAckConsumer("mock:mockAckUri");
      }
    }), "testConsumerException");
    camel.awaitActivation(consumer, new FiniteDuration(10, TimeUnit.SECONDS));
    camel.context().addRoutes(new CustomRouteBuilder("direct:testException", consumer, false, new FiniteDuration(0, TimeUnit.SECONDS)));
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

    public CustomRouteBuilder(String from, ActorRef actor, boolean autoAck, FiniteDuration replyTimeout) {
      fromUri = from;
      uri = CamelPath.toUri(actor, autoAck, replyTimeout);
    }

    @Override
    public void configure() throws Exception {
      from(fromUri).to(uri);
    }
  }

  public static class TestAckConsumer extends UntypedConsumerActor {

    String endpoint;

    public TestAckConsumer(String to){
      endpoint = to;
    }

    @Override
    public String getEndpointUri() {
      return "direct:testconsumer";
    }

    @Override
    public void onReceive(Object message) {
      this.getProducerTemplate().sendBody(endpoint, "test");
      getSender().tell(Ack.getInstance());
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

  public static class MockEndpointProducer extends UntypedProducerActor {
    public String getEndpointUri() {
      return "mock:mockProducer";
    }

    @Override
    public boolean isOneway() {
      return true;
    }
  }
}
