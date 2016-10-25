/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.stream;

import akka.NotUsed;
import akka.actor.*;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.*;
import akka.stream.javadsl.*;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import docs.AbstractJavaTest;
import docs.stream.TwitterStreamQuickstartDocTest.Model.Author;
import docs.stream.TwitterStreamQuickstartDocTest.Model.Tweet;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import static akka.pattern.PatternsCS.ask;
import static docs.stream.TwitterStreamQuickstartDocTest.Model.AKKA;
import static docs.stream.TwitterStreamQuickstartDocTest.Model.tweets;
import static junit.framework.TestCase.assertTrue;

public class IntegrationDocTest extends AbstractJavaTest {

  private static final SilenceSystemOut.System System = SilenceSystemOut.get();

  static ActorSystem system;
  static Materializer mat;

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
    mat = ActorMaterializer.create(system);
  }

  @AfterClass
  public static void tearDown() {
    JavaTestKit.shutdownActorSystem(system);
    system = null;
    mat = null;
  }


  class AddressSystem {
    //#email-address-lookup
    public CompletionStage<Optional<String>> lookupEmail(String handle)
    //#email-address-lookup
    {
      return CompletableFuture.completedFuture(Optional.of(handle + "@somewhere.com"));
    }

    //#phone-lookup
    public CompletionStage<Optional<String>> lookupPhoneNumber(String handle)
    //#phone-lookup
    {
      return CompletableFuture.completedFuture(Optional.of("" + handle.hashCode()));
    }
  }

  class AddressSystem2 {
    //#email-address-lookup2
    public CompletionStage<String> lookupEmail(String handle)
    //#email-address-lookup2
    {
      return CompletableFuture.completedFuture(handle + "@somewhere.com");
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
    public CompletionStage<Email> send(Email email) {
      // ...
      //#email-server-send
      probe.tell(email.to, ActorRef.noSender());
      return CompletableFuture.completedFuture(email);
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
    private final Executor ec;
    
    public SometimesSlowService(Executor ec) {
      this.ec = ec;
    }
    
    private final AtomicInteger runningCount = new AtomicInteger();

    public CompletionStage<String> convert(String s) {
      System.out.println("running: " + s + "(" + runningCount.incrementAndGet() + ")");
      return CompletableFuture.supplyAsync(() -> {
        if (!s.isEmpty() && Character.isLowerCase(s.charAt(0)))
          try { Thread.sleep(500); } catch (InterruptedException e) {}
        else
          try { Thread.sleep(20); } catch (InterruptedException e) {}
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
        final Source<Author, NotUsed> authors = tweets
          .filter(t -> t.hashtags().contains(AKKA))
          .map(t -> t.author);

        //#tweet-authors

        //#email-addresses-mapAsync
        final Source<String, NotUsed> emailAddresses = authors
          .mapAsync(4, author -> addressSystem.lookupEmail(author.handle))
          .filter(o -> o.isPresent())
          .map(o -> o.get());

        //#email-addresses-mapAsync

        //#send-emails
        final RunnableGraph<NotUsed> sendEmails = emailAddresses
          .mapAsync(4, address ->
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
  @SuppressWarnings("unused")
  public void callingExternalServiceWithMapAsyncAndSupervision() throws Exception {
    new JavaTestKit(system) {
      final AddressSystem2 addressSystem = new AddressSystem2();

      {
        final Source<Author, NotUsed> authors = tweets
          .filter(t -> t.hashtags().contains(AKKA))
          .map(t -> t.author);

        //#email-addresses-mapAsync-supervision
        final Attributes resumeAttrib =
          ActorAttributes.withSupervisionStrategy(Supervision.getResumingDecider());
        final Flow<Author, String, NotUsed> lookupEmail =
            Flow.of(Author.class)
            .mapAsync(4, author -> addressSystem.lookupEmail(author.handle))
            .withAttributes(resumeAttrib);
        final Source<String, NotUsed> emailAddresses = authors.via(lookupEmail);

        //#email-addresses-mapAsync-supervision
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
        final Source<Author, NotUsed> authors =
          tweets
            .filter(t -> t.hashtags().contains(AKKA))
            .map(t -> t.author);

        final Source<String, NotUsed> emailAddresses =
          authors
            .mapAsyncUnordered(4, author -> addressSystem.lookupEmail(author.handle))
            .filter(o -> o.isPresent())
            .map(o -> o.get());

        final RunnableGraph<NotUsed> sendEmails =
          emailAddresses
            .mapAsyncUnordered(4, address ->
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
      final AddressSystem addressSystem = new AddressSystem();
      final EmailServer emailServer = new EmailServer(getRef());
      final SmsServer smsServer = new SmsServer(getRef());

      {
        final Source<Author, NotUsed> authors =
          tweets
            .filter(t -> t.hashtags().contains(AKKA))
            .map(t -> t.author);

        final Source<String, NotUsed> phoneNumbers = authors.mapAsync(4, author -> addressSystem.lookupPhoneNumber(author.handle))
          .filter(o -> o.isPresent())
          .map(o -> o.get());

        //#blocking-mapAsync
        final Executor blockingEc = system.dispatchers().lookup("blocking-dispatcher");

        final RunnableGraph<NotUsed> sendTextMessages =
          phoneNumbers
            .mapAsync(4, phoneNo -> CompletableFuture.supplyAsync(() ->
              smsServer.send(new TextMessage(phoneNo, "I like your tweet")), blockingEc))
            .to(Sink.ignore());

        sendTextMessages.run(mat);
        //#blocking-mapAsync

        final Object[] got = receiveN(7);
        final Set<Object> set = new HashSet<>(Arrays.asList(got));

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
        final Source<Author, NotUsed> authors =
          tweets
            .filter(t -> t.hashtags().contains(AKKA))
            .map(t -> t.author);

        final Source<String, NotUsed> phoneNumbers = authors.mapAsync(4, author -> addressSystem.lookupPhoneNumber(author.handle))
          .filter(o -> o.isPresent())
          .map(o -> o.get());

        //#blocking-map
        final Flow<String, Boolean, NotUsed> send =
          Flow.of(String.class)
          .map(phoneNo -> smsServer.send(new TextMessage(phoneNo, "I like your tweet")))
          .withAttributes(ActorAttributes.dispatcher("blocking-dispatcher"));
        final RunnableGraph<?> sendTextMessages =
          phoneNumbers.via(send).to(Sink.ignore());

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
        final Source<Tweet, NotUsed> akkaTweets = tweets.filter(t -> t.hashtags().contains(AKKA));

        final RunnableGraph<NotUsed> saveTweets =
          akkaTweets
            .mapAsync(4, tweet -> ask(database, new Save(tweet), 300))
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

      class MockSystem {
        class Println {
          public <T> void println(T s) {
            if (s.toString().startsWith("after:"))
              probe.ref().tell(s, ActorRef.noSender());
          }
        }

        public final Println out = new Println();
      }
      private final MockSystem System = new MockSystem();

      {
        //#sometimes-slow-mapAsync
        final Executor blockingEc = system.dispatchers().lookup("blocking-dispatcher");
        final SometimesSlowService service = new SometimesSlowService(blockingEc);

        final ActorMaterializer mat = ActorMaterializer.create(
          ActorMaterializerSettings.create(system).withInputBuffer(4, 4), system);

        Source.from(Arrays.asList("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
          .map(elem -> { System.out.println("before: " + elem); return elem; })
          .mapAsync(4, service::convert)
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
      final EmailServer emailServer = new EmailServer(getRef());

      class MockSystem {
        class Println {
          public <T> void println(T s) {
            if (s.toString().startsWith("after:"))
              getRef().tell(s, ActorRef.noSender());
          }
        }

        public final Println out = new Println();
      }
      private final MockSystem System = new MockSystem();

      {
        //#sometimes-slow-mapAsyncUnordered
        final Executor blockingEc = system.dispatchers().lookup("blocking-dispatcher");
        final SometimesSlowService service = new SometimesSlowService(blockingEc);

        final ActorMaterializer mat = ActorMaterializer.create(
          ActorMaterializerSettings.create(system).withInputBuffer(4, 4), system);

        Source.from(Arrays.asList("a", "B", "C", "D", "e", "F", "g", "H", "i", "J"))
          .map(elem -> { System.out.println("before: " + elem); return elem; })
          .mapAsyncUnordered(4, service::convert)
          .runForeach(elem -> System.out.println("after: " + elem), mat);
        //#sometimes-slow-mapAsyncUnordered

        final Object[] got = receiveN(10);
        final Set<Object> set = new HashSet<>(Arrays.asList(got));

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
