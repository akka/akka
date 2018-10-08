/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.CommandHandlerBuilder;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * Based on
 * https://github.com/fun-cqrs/fun-cqrs/blob/develop/samples/raffle/src/main/scala/raffle/domain/model/Raffle.scala
 *
 * but in Java with a mutable domain object (state) that is independent of Akka
 */
public class RaffleExample {

  public static class RaffleId {}

  interface Event {}
  public static final class RaffleStarted implements Event { }
  public static final class ParticipantAdded implements Event {
    public final String name;
    public ParticipantAdded(String name) {
      this.name = name;
    }
  }
  public static final class ParticipantRemoved implements Event {
    public final String name;
    public ParticipantRemoved(String name) {
      this.name = name;
    }
  }
  public static final class RaffleCompleted implements Event {}

  interface Command {}
  public static final class StartRaffle implements Command { }

  public static final class AddParticipant implements Command {
    public final String name;
    public AddParticipant(String name) {
      this.name = name;
    }
  }

  public static final class RemoveParticipant implements Command {
    public final String name;
    public RemoveParticipant(String name) {
      this.name = name;
    }
  }
  public static final class CompleteRaffle implements Command { }

  // TODO replies are not implemented in this example yet

  private enum RaffleState {
    NOT_STARTED,
    RUNNING,
    COMPLETED
  }

  // mutable domain model impl, completely free from Akka stuff
  // requires https://github.com/akka/akka/issues/25740
  static class Raffle {
    private RaffleState state = RaffleState.NOT_STARTED;
    private boolean finished = false;
    private List<String> participants = new ArrayList<>();
    private Optional<String> winner = Optional.empty();

    RaffleState getState() {
      return state;
    }

    boolean containsParticipant(String name) {
      return participants.contains(name);
    }

    void start() {
      state = RaffleState.RUNNING;
    }

    void addParticipant(String name) {
      participants.add(name);
    }

    void removeParticipant(String name) {
      participants.remove(name);
    }

    void complete() {
      state = RaffleState.COMPLETED;
      int winnderIndex = new Random().nextInt(participants.size() - 1);
      winner = Optional.of(participants.get(winnderIndex));
    }

  };


  public static Behavior<Command> behavior(String raffleId) {
    return new PersistentBehavior<Command, Event, Raffle>(new PersistenceId("Raffle|" + raffleId)) {

      @Override
      public Raffle emptyState() {
        return new Raffle();
      }

      @Override
      public CommandHandler<Command, Event, Raffle> commandHandler() {
        CommandHandlerBuilder<Command, Event, Raffle, Raffle> notStartedHandler =
            commandHandlerBuilder(raffle -> raffle.getState() == RaffleState.NOT_STARTED)
                .matchCommand(StartRaffle.class, (raffle, cmd) -> Effect().persist(new RaffleStarted()));

        CommandHandlerBuilder<Command, Event, Raffle, Raffle> runningHandler =
            commandHandlerBuilder(raffle -> raffle.getState() == RaffleState.RUNNING)
                .matchCommand(AddParticipant.class, (this::addParticipant))
                .matchCommand(RemoveParticipant.class, (this::removeParticipant))
                .matchCommand(CompleteRaffle.class, ((raffle, cmd) -> completeRaffle()));

        CommandHandlerBuilder<Command, Event, Raffle, Raffle> completedHandler =
            commandHandlerBuilder(raffle -> raffle.getState() == RaffleState.COMPLETED);

        return notStartedHandler.orElse(runningHandler).orElse(completedHandler).build();
      }

      private Effect<Event, Raffle> addParticipant(Raffle raffle, AddParticipant cmd) {
        if (!raffle.containsParticipant(cmd.name))
          return Effect().persist(new ParticipantAdded(cmd.name));
        else
          return Effect().none();
      }

      private Effect<Event, Raffle> removeParticipant(Raffle raffle, RemoveParticipant cmd) {
        if (raffle.containsParticipant(cmd.name))
          return Effect().persist(new ParticipantRemoved(cmd.name));
        else
          return Effect().none();
      }

      private Effect<Event, Raffle> completeRaffle() {
        return Effect().persist(new RaffleCompleted())
            .andThen((completedRaffle) -> {
              System.out.println("The winner is: " + completedRaffle.winner.get());
            });
      }


      @Override
      public EventHandler<Raffle, Event> eventHandler() {
        return eventHandlerBuilder()
            .matchEvent(RaffleStarted.class, (raffle, evt) -> {
              raffle.start();
              return raffle;
            })
            .matchEvent(ParticipantAdded.class, (raffle, evt) -> {
              raffle.addParticipant(evt.name);
              return raffle;
            })
            .matchEvent(ParticipantRemoved.class, (raffle, evt) -> {
              raffle.removeParticipant(evt.name);
              return raffle;
            })
            .matchEvent(RaffleCompleted.class, (raffle, evt) -> {
              raffle.complete();
              return raffle;
            })
            .build();
      }
    };
  }

  public static void main(String[] args) {
    ActorSystem<Command> system = ActorSystem.create(behavior("id-1"), "raffle");

    system.tell(new AddParticipant("konrad")); // not started yet
    system.tell(new StartRaffle());
    system.tell(new AddParticipant("patrik"));
    system.tell(new AddParticipant("chris"));
    system.tell(new AddParticipant("johan"));
    system.tell(new StartRaffle()); // not possible
    system.tell(new CompleteRaffle());
  }


}
