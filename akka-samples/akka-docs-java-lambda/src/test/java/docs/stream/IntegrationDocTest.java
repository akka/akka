/*
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.stream;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.dispatch.Futures;
import akka.dispatch.MessageDispatcher;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.ActorFlowMaterializer;
import akka.stream.ActorFlowMaterializerSettings;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.OperationAttributes;
import akka.stream.javadsl.RunnableFlow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import docs.stream.TwitterStreamQuickstartDocTest.Model.Author;
import docs.stream.TwitterStreamQuickstartDocTest.Model.Tweet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import scala.collection.immutable.Seq;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static akka.pattern.Patterns.ask;
import static docs.stream.TwitterStreamQuickstartDocTest.Model.AKKA;
import static docs.stream.TwitterStreamQuickstartDocTest.Model.tweets;
import static junit.framework.TestCase.assertTrue;

public class IntegrationDocTest {

  static ActorSystem system;

  @BeforeClass
  public static void setup() {
    final Config config = ConfigFactory.parseString("" +
                                                "blocking-dispatcher {                  \n" +
                                                "  executor = thread-pool-executor      \n" +
                                                "  thread-pool-executor {               \n" +
                                                "    core-pool-size-min = 10            \n" +
                                                "    core-pool-size-max = 10            \n" +
                                                "  }                                    \n" +
                                                "}                                      \n" +
                                                "akka.actor.default-mailbox.mailbox-type = akka.dispatch.UnboundedMailbox\n");

    system = ActorSystem.create("ActorPublisherDocTest", config);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
  }

  final FlowMaterializer mat = ActorFlowMaterializer.create(system);

  class AddressSystem {
    //#email-address-lookup
    public Future<Optional<String>> lookupEmail(String handle)
    //#email-address-lookup
    {
      return Futures.successful(Optional.of(handle + "@somewhere.com"));
    }

    //#phone-lookup
    public Future<Optional<String>> lookupPhoneNumber(String handle)
    //#phone-lookup
    {
      return Futures.successful(Optional.of("" + handle.hashCode()));
    }
  }
  
  static class Email {
    public final String to;
    public final String title;
    public final String body;

    public Email(String to, String title, String body) {
      this.to = to;
      this.title = title;
      this.body = body;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Email email = (Email) o;

      if (body != null ? !body.equals(email.body) : email.body != null) {
        return false;
      }
      if (title != null ? !title.equals(email.title) : email.title != null) {
        return false;
      }
      if (to != null ? !to.equals(email.to) : email.to != null) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      int result = to != null ? to.hashCode() : 0;
      result = 31 * result + (title != null ? title.hashCode() : 0);
      result = 31 * result + (body != null ? body.hashCode() : 0);
      return result;
    }
  }
  
  static class TextMessage {
    public final String to;
    public final String body;

    TextMessage(String to, String body) {
      this.to = to;
      this.body = body;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      TextMessage that = (TextMessage) o;

      if (body != null ? !body.equals(that.body) : that.body != null) {
        return false;
      }
      if (to != null ? !to.equals(that.to) : that.to != null) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      int result = to != null ? to.hashCode() : 0;
      result = 31 * result + (body != null ? body.hashCode() : 0);
      return result;
    }
  }
  
  static class EmailServer {
    public final ActorRef probe;

    public EmailServer(ActorRef probe) {
      this.probe = probe;
    }
    
    //#email-server-send
    public Future<Void> send(Email email) {
      // ... 
      //#email-server-send
      probe.tell(email.to, ActorRef.noSender());
      return Futures.successful(null);
      //#email-server-send
    }
    //#email-server-send
  }
  
  
  static class SmsServer {
    public final ActorRef probe;

    public SmsServer(ActorRef probe) {
      this.probe = probe;
    }
    
    //#sms-server-send
    public boolean send(TextMessage text) {
      // ... 
      //#sms-server-send
      probe.tell(text.to, ActorRef.noSender());
      //#sms-server-send
      return true;
    }
    //#sms-server-send
  }
  
  static class Save {
    public final Tweet tweet;

    Save(Tweet tweet) {
      this.tweet = tweet;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Save save = (Save) o;

      if (tweet != null ? !tweet.equals(save.tweet) : save.tweet != null) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      return tweet != null ? tweet.hashCode() : 0;
    }
  } 
  static class SaveDone {
    public static SaveDone INSTANCE = new SaveDone();
    private SaveDone() {
    }
  } 
  
  
  static class DatabaseService extends AbstractActor {
    public final ActorRef probe;

    DatabaseService(ActorRef probe) {
      this.probe = probe;
      
      receive(ReceiveBuilder.match(Save.class, s -> {
        probe.tell(s.tweet.author.handle, ActorRef.noSender());
        sender().tell(SaveDone.INSTANCE, self());
      }).build());
    }
  }
  
  //#sometimes-slow-service
  static class SometimesSlowService {
    private final ExecutionContext ec;

    public SometimesSlowService(ExecutionContext ec) {
      this.ec = ec;
    }

    private final AtomicInteger runningCount = new AtomicInteger();
    
    public Future<String> convert(String s) {
      System.out.println("running: " + s + "(" + runningCount.incrementAndGet() + ")");
      return Futures.future(() -> {
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0)))
          Thread.sleep(500);
        else 
          Thread.sleep(20);
        System.out.println("completed: " + s + "(" + runningCount.decrementAndGet() + ")");
        return s.toUpperCase();
      }, ec);
    }
  }
  //#sometimes-slow-service


  @Test
  public void callingExternalServiceWithMapAsync() throws Exception {
    new JavaTestKit(system) {
      final TestProbe probe = new TestProbe(system);
      final AddressSystem addressSystem = new AddressSystem();
      final EmailServer emailServer = new EmailServer(probe.ref());

      {
        //#tweet-authors
        final Source<Author> authors = tweets
          .filter(t -> t.hashtags().contains(AKKA))
          .map(t -> t.author);
        
        //#tweet-authors
        
        //#email-addresses-mapAsync
        final Source<String> emailAddresses = authors
          .mapAsync(author -> addressSystem.lookupEmail(author.handle))
          .filter(o -> o.isPresent())
          .map(o -> o.get());
        
        //#email-addresses-mapAsync

        //#send-emails
        final RunnableFlow sendEmails = emailAddresses
          .mapAsync(address ->
            emailServer.send(new Email(address, "Akka", "I like your tweet")))
          .to(Sink.ignore());
        
        sendEmails.run(mat);
        //#send-emails
        
        probe.expectMsg("rolandkuhn@somewhere.com");
        probe.expectMsg("patriknw@somewhere.com");
        probe.expectMsg("bantonsson@somewhere.com");
        probe.expectMsg("drewhk@somewhere.com");
        probe.expectMsg("ktosopl@somewhere.com");
        probe.expectMsg("mmartynas@somewhere.com");
        probe.expectMsg("akkateam@somewhere.com");
      }
    };
  }
  
  @Test
  public void callingExternalServiceWithMapAsyncUnordered() throws Exception {
    new JavaTestKit(system) {
      final TestProbe probe = new TestProbe(system);
      final AddressSystem addressSystem = new AddressSystem();
      final EmailServer emailServer = new EmailServer(probe.ref());

      {
        //#external-service-mapAsyncUnordered
        final Source<Author> authors =
          tweets
            .filter(t -> t.hashtags().contains(AKKA))
            .map(t -> t.author);

        final Source<String> emailAddresses =
          authors
            .mapAsyncUnordered(author -> addressSystem.lookupEmail(author.handle))
            .filter(o -> o.isPresent())
            .map(o -> o.get());

        final RunnableFlow sendEmails =
          emailAddresses
            .mapAsyncUnordered( address ->
              emailServer.send(new Email(address, "Akka", "I like your tweet")))
            .to(Sink.ignore());
  
        sendEmails.run(mat);
        //#external-service-mapAsyncUnordered
      }
    };
  }
  
  @Test
  public void carefulManagedBlockingWithMapAsync() throws Exception {
    new JavaTestKit(system) {
      final TestProbe probe = new TestProbe(system);
      final AddressSystem addressSystem = new AddressSystem();
      final EmailServer emailServer = new EmailServer(probe.ref());
      final SmsServer smsServer = new SmsServer(probe.ref());

      {
        final Source<Author> authors =
          tweets
            .filter(t -> t.hashtags().contains(AKKA))
            .map(t -> t.author);
        
        final Source<String> phoneNumbers = authors.mapAsync(author -> addressSystem.lookupPhoneNumber(author.handle))
          .filter(o -> o.isPresent())
          .map(o -> o.get());

        //#blocking-mapAsync
        final MessageDispatcher blockingEc = system.dispatchers().lookup("blocking-dispatcher");

        final RunnableFlow sendTextMessages =
          phoneNumbers
            .mapAsync( phoneNo  -> 
              Futures.future(() -> 
                smsServer.send(new TextMessage(phoneNo, "I like your tweet")), 
                blockingEc)
            )
            .to(Sink.ignore());
          
        sendTextMessages.run(mat);
        //#blocking-mapAsync
        
        Seq<Object> got = probe.receiveN(7);
          Set<Object> set = new HashSet<>(got.size());
          for (int i = 0; i < got.size(); i++) {
            set.add(got.apply(i));
          }

        assertTrue(set.contains(String.valueOf("rolandkuhn".hashCode())));
        assertTrue(set.contains(String.valueOf("patriknw".hashCode())));
        assertTrue(set.contains(String.valueOf("bantonsson".hashCode())));
        assertTrue(set.contains(String.valueOf("drewhk".hashCode())));
        assertTrue(set.contains(String.valueOf("ktosopl".hashCode())));
        assertTrue(set.contains(String.valueOf("mmartynas".hashCode())));
        assertTrue(set.contains(String.valueOf("akkateam".hashCode())));
      }
    };
  }
  
  @Test
  public void carefulManagedBlockingWithMap() throws Exception {
    new JavaTestKit(system) {
      final TestProbe probe = new TestProbe(system);
      final AddressSystem addressSystem = new AddressSystem();
      final EmailServer emailServer = new EmailServer(probe.ref());
      final SmsServer smsServer = new SmsServer(probe.ref());

      {
        final Source<Author> authors =
          tweets
            .filter(t -> t.hashtags().contains(AKKA))
            .map(t -> t.author);
        
        final Source<String> phoneNumbers = authors.mapAsync(author -> addressSystem.lookupPhoneNumber(author.handle))
          .filter(o -> o.isPresent())
          .map(o -> o.get());

        //#blocking-map
        final RunnableFlow sendTextMessages =
          phoneNumbers
            .section(OperationAttributes.dispatcher("blocking-dispatcher"), blockingSection ->
              blockingSection
                .map(phoneNo -> smsServer.send(new TextMessage(phoneNo, "I like your tweet"))))
            .to(Sink.ignore());
                  
        sendTextMessages.run(mat);
        //#blocking-map
          
        probe.expectMsg(String.valueOf("rolandkuhn".hashCode()));
        probe.expectMsg(String.valueOf("patriknw".hashCode()));
        probe.expectMsg(String.valueOf("bantonsson".hashCode()));
        probe.expectMsg(String.valueOf("drewhk".hashCode()));
        probe.expectMsg(String.valueOf("ktosopl".hashCode()));
        probe.expectMsg(String.valueOf("mmartynas".hashCode()));
        probe.expectMsg(String.valueOf("akkateam".hashCode()));
      }
    };
  }
  
  @Test
  public void callingActorServiceWithMapAsync() throws Exception {
    new JavaTestKit(system) {
      final TestProbe probe = new TestProbe(system);
      final EmailServer emailServer = new EmailServer(probe.ref());

      final ActorRef database = system.actorOf(Props.create(DatabaseService.class, probe.ref()), "db");

      {
        //#save-tweets
        final Source<Tweet> akkaTweets = tweets.filter(t -> t.hashtags().contains(AKKA));

        final RunnableFlow saveTweets =
          akkaTweets
            .mapAsync(tweet -> ask(database, new Save(tweet), 300))
            .to(Sink.ignore());
        //#save-tweets

        saveTweets.run(mat);

        probe.expectMsg("rolandkuhn");
        probe.expectMsg("patriknw");
        probe.expectMsg("bantonsson");
        probe.expectMsg("drewhk");
        probe.expectMsg("ktosopl");
        probe.expectMsg("mmartynas");
        probe.expectMsg("akkateam");
      }
    };
  }
  
  @Test
  public void illustrateOrderingAndParallelismOfMapAsync() throws Exception {
    new JavaTestKit(system) {
      final TestProbe probe = new TestProbe(system);
      final EmailServer emailServer = new EmailServer(probe.ref());

      final SilenceSystemOut.System System = SilenceSystemOut.get((String m) -> m.startsWith("after"), probe.ref());
      
      {
        //#sometimes-slow-mapAsync
        final MessageDispatcher blockingEc = system.dispatchers().lookup("blocking-dispatcher");
        final SometimesSlowService service = new SometimesSlowService(blockingEc);

        final ActorFlowMaterializer mat = ActorFlowMaterializer.create(
          ActorFlowMaterializerSettings.create(system).withInputBuffer(4, 4), system);

        Source.from(Arrays.asList("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
          .map(elem -> { System.out.println("before: " + elem); return elem; })
          .mapAsync(service::convert)
          .runForeach(elem -> System.out.println("after: " + elem), mat);
        //#sometimes-slow-mapAsync

        probe.expectMsg("after: A");
        probe.expectMsg("after: B");
        probe.expectMsg("after: C");
        probe.expectMsg("after: D");
        probe.expectMsg("after: E");
        probe.expectMsg("after: F");
        probe.expectMsg("after: G");
        probe.expectMsg("after: H");
        probe.expectMsg("after: I");
        probe.expectMsg("after: J");
      }
    };
  }
  
  @Test
  public void illustrateOrderingAndParallelismOfMapAsyncUnordered() throws Exception {
    new JavaTestKit(system) {
      final TestProbe probe = new TestProbe(system);
      final EmailServer emailServer = new EmailServer(probe.ref());

      final SilenceSystemOut.System System = SilenceSystemOut.get((String m) -> m.startsWith("after"), probe.ref());
      
      {
        //#sometimes-slow-mapAsyncUnordered
        final MessageDispatcher blockingEc = system.dispatchers().lookup("blocking-dispatcher");
        final SometimesSlowService service = new SometimesSlowService(blockingEc);

        final ActorFlowMaterializer mat = ActorFlowMaterializer.create(
          ActorFlowMaterializerSettings.create(system).withInputBuffer(4, 4), system);

        Source.from(Arrays.asList("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
          .map(elem -> { System.out.println("before: " + elem); return elem; })
          .mapAsyncUnordered(service::convert)
          .runForeach(elem -> System.out.println("after: " + elem), mat);
        //#sometimes-slow-mapAsyncUnordered

        Seq<Object> got = probe.receiveN(10);
        Set<Object> set = new HashSet<>(got.size());
        for (int i = 0; i < got.size(); i++) {
          set.add(got.apply(i));
        }
        assertTrue(set.contains("after: A"));
        assertTrue(set.contains("after: B"));
        assertTrue(set.contains("after: C"));
        assertTrue(set.contains("after: D"));
        assertTrue(set.contains("after: E"));
        assertTrue(set.contains("after: F"));
        assertTrue(set.contains("after: G"));
        assertTrue(set.contains("after: H"));
        assertTrue(set.contains("after: I"));
        assertTrue(set.contains("after: J"));
      }
    };
  }
  
    
}