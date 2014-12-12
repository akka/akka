package sample.become;

import akka.actor.*;
import akka.japi.pf.ReceiveBuilder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

import static sample.become.Messages.*;
import static java.util.concurrent.TimeUnit.*;

// Akka adaptation of
// http://www.dalnefre.com/wp/2010/08/dining-philosophers-in-humus/

public class DiningHakkersOnBecome {

  /*
   * A Chopstick is an actor, it can be taken, and put back
   */
  public static class Chopstick extends AbstractActor {

    //When a Chopstick is taken by a hakker
    //It will refuse to be taken by other hakkers
    //But the owning hakker can put it back
    PartialFunction<Object, BoxedUnit> takenBy(ActorRef hakker) {
      return ReceiveBuilder.
        match(Take.class,
          t -> t.hakker.tell(new Busy(self()), self())).
        match(Put.class, p -> p.hakker == hakker,
          p -> context().become(available)).
        build();
    }

    //When a Chopstick is available, it can be taken by a hakker
    PartialFunction<Object, BoxedUnit> available = ReceiveBuilder.
      match(Take.class, t -> {
        context().become(takenBy(t.hakker));
        t.hakker.tell(new Taken(self()), self());
      }).build();

    //A Chopstick begins its existence as available
    public Chopstick() {
      receive(available);
    }
  }

  /*
   * A hakker is an awesome dude or dudette who either thinks about hacking or has to eat ;-)
   */
   public static class Hakker extends AbstractActor {
    private String name;
    private ActorRef left;
    private ActorRef right;

    public Hakker(String name, ActorRef left, ActorRef right) {
      this.name = name;
      this.left = left;
      this.right = right;
      //All hakkers start in a non-eating state
      receive(ReceiveBuilder.matchEquals(Think, m -> {
          System.out.println(String.format("%s starts to think", name));
          startThinking(Duration.create(5, SECONDS));
        }).build());
    }

    //When a hakker is eating, he can decide to start to think,
    //then he puts down his chopsticks and starts to think
    PartialFunction<Object, BoxedUnit> eating = ReceiveBuilder.
      matchEquals(Think, m -> {
        left.tell(new Put(self()), self());
        right.tell(new Put(self()), self());
        System.out.println(String.format("%s puts down his chopsticks and starts to think", name));
        startThinking(Duration.create(5, SECONDS));
      }).build();

    //When a hakker is waiting for the last chopstick it can either obtain it
    //and start eating, or the other chopstick was busy, and the hakker goes
    //back to think about how he should obtain his chopsticks :-)
    PartialFunction<Object, BoxedUnit> waitingFor(ActorRef chopstickToWaitFor, ActorRef otherChopstick) {
      return ReceiveBuilder.
        match(Taken.class, t -> t.chopstick == chopstickToWaitFor, t -> {
          System.out.println(String.format("%s has picked up %s and %s and starts to eat",
            name, left.path().name(), right.path().name()));
          context().become(eating);
          context().system().scheduler().scheduleOnce(Duration.create(5, SECONDS), self(), Think, context().system().dispatcher(), self());
        }).
        match(Busy.class, b -> {
          otherChopstick.tell(new Put(self()), self());
          startThinking(Duration.create(10, MILLISECONDS));
        }).
        build();
    }

    //When the results of the other grab comes back,
    //he needs to put it back if he got the other one.
    //Then go back and think and try to grab the chopsticks again
    PartialFunction<Object, BoxedUnit> deniedAChopstick = ReceiveBuilder.
      match(Taken.class, t -> {
        t.chopstick.tell(new Put(self()), self());
        startThinking(Duration.create(10, MILLISECONDS));
      }).
      match(Busy.class, b ->
        startThinking(Duration.create(10, MILLISECONDS))).
      build();

    //When a hakker is hungry it tries to pick up its chopsticks and eat
    //When it picks one up, it goes into wait for the other
    //If the hakkers first attempt at grabbing a chopstick fails,
    //it starts to wait for the response of the other grab
    PartialFunction<Object, BoxedUnit> hungry = ReceiveBuilder.
      match(Taken.class, t -> t.chopstick == left,
        t -> context().become(waitingFor(right, left))).
      match(Taken.class, t -> t.chopstick == right,
        t -> context().become(waitingFor(left, right))).
      match(Busy.class,
        b -> context().become(deniedAChopstick)).
      build();

    //When a hakker is thinking it can become hungry
    //and try to pick up its chopsticks and eat
    PartialFunction<Object, BoxedUnit> thinking = ReceiveBuilder.
      matchEquals(Eat, m -> {
        context().become(hungry);
        left.tell(new Take(self()), self());
        right.tell(new Take(self()), self());
      }).build();

    private void startThinking(FiniteDuration duration) {
      context().become(thinking);
      context().system().scheduler().scheduleOnce(duration, self(), Eat, context().system().dispatcher(), self());
    }
  }

  /*
  * Alright, here's our test-harness
  */
  public static void main(String[] args) {
    ActorSystem system = ActorSystem.create();
    //Create 5 chopsticks
    ActorRef[] chopsticks = new ActorRef[5];
    for (int i = 0; i < 5; i++)
      chopsticks[i] = system.actorOf(Props.create(Chopstick.class), "Chopstick" + i);

    //Create 5 awesome hakkers and assign them their left and right chopstick
    List<String> names = Arrays.asList("Ghosh", "Boner", "Klang", "Krasser", "Manie");
    List<ActorRef> hakkers = new ArrayList<>();
    int i = 0;
    for (String name: names) {
      hakkers.add(system.actorOf(Props.create(Hakker.class, name, chopsticks[i], chopsticks[(i + 1) % 5])));
      i++;
    }
    //Signal all hakkers that they should start thinking, and watch the show
    hakkers.stream().forEach(hakker -> hakker.tell(Think, ActorRef.noSender()));
  }
}
