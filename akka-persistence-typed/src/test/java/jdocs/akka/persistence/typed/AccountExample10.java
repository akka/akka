/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.CommandHandlerBuilder;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventHandlerBuilder;
import akka.persistence.typed.javadsl.PersistentBehavior10;

public class AccountExample10 extends PersistentBehavior10<AccountExample10.AccountCommand, AccountExample10.AccountEvent, AccountExample10.Account, AccountExample10.AccountSnapshot> {

  interface AccountCommand {}
  public static class CreateAccount implements AccountCommand {}
  public static class Deposit implements AccountCommand {
    public final double amount;

    public Deposit(double amount) {
      this.amount = amount;
    }
  }
  public static class Withdraw implements AccountCommand {
    public final double amount;

    public Withdraw(double amount) {
      this.amount = amount;
    }
  }
  public static class CloseAccount implements AccountCommand {}

  interface AccountEvent {}
  public static class AccountCreated implements AccountEvent {}
  public static class Deposited implements AccountEvent {
    public final double amount;

    Deposited(double amount) {
      this.amount = amount;
    }
  }
  public static class Withdrawn implements AccountEvent {
    public final double amount;

    Withdrawn(double amount) {
      this.amount = amount;
    }
  }
  public static class AccountClosed implements AccountEvent {}

  interface Account {

    Account applyEvent(AccountEvent event);

    AccountSnapshot toSnapshot();

    static Account fromSnapshot(AccountSnapshot snapshot) {
      switch (snapshot.lifecycle) {
        case EMPTY: return new EmptyAccount();
        case OPEN: return new OpenedAccount(snapshot.balance);
        case CLOSED: return new ClosedAccount();
        default: throw new IllegalStateException();
      }
    }
  }
  public static class EmptyAccount implements Account {
    @Override
    public Account applyEvent(AccountEvent event) {
      if (event instanceof AccountCreated)
        return new OpenedAccount(0.0);
      else throw new IllegalStateException();
    }

    @Override
    public AccountSnapshot toSnapshot() {
      return new AccountSnapshot(0.0, AccountLifecycle.EMPTY);
    }
  }

  public static class OpenedAccount implements Account {
    private double balance;

    private final EventHandler<Account, AccountEvent> eventHandler =
        EventHandlerBuilder.<Account, AccountEvent>builder()
            .matchEvent(Deposited.class, evt -> {
              setBalance(getBalance() + evt.amount);
              return OpenedAccount.this;
            })
            .matchEvent(AccountClosed.class, evt -> {
                return new ClosedAccount();
            })
            .matchAny(evt -> {
              throw new IllegalStateException();
            });

    OpenedAccount(double balance) {
      this.balance = balance;
    }

    public double getBalance() {
      return balance;
    }

    private void setBalance(double balance) {
      this.balance = balance;
    }

    @Override
    public Account applyEvent(AccountEvent event) {
      return eventHandler.apply(this, event);
    }

    @Override
    public AccountSnapshot toSnapshot() {
      return new AccountSnapshot(balance, AccountLifecycle.OPEN);
    }
  }

  public static class ClosedAccount implements Account {
    @Override
    public Account applyEvent(AccountEvent event) {
      throw new IllegalStateException();
    }

    @Override
    public AccountSnapshot toSnapshot() {
      return new AccountSnapshot(0.0, AccountLifecycle.CLOSED);
    }
  }

  public static Behavior<AccountCommand> behavior(String accountNumber) {
    return Behaviors.setup(context -> new AccountExample10(context, accountNumber));
  }

  enum AccountLifecycle {
    EMPTY, OPEN, CLOSED
  }

  public static class AccountSnapshot {
    public final double balance;
    public final AccountLifecycle lifecycle;

    AccountSnapshot(double balance, AccountLifecycle lifecycle) {
      this.balance = balance;
      this.lifecycle = lifecycle;
    }
  }

  public AccountExample10(ActorContext<AccountCommand> context, String accountNumber) {
    super(new PersistenceId(accountNumber));
  }

  @Override
  public Account emptyState() {
    return new EmptyAccount();
  }

  @Override
  public AccountSnapshot toSnapshot(Account state) {
    return state.toSnapshot();
  }

  @Override
  public Account fromSnapshot(AccountSnapshot snapshot) {
    return Account.fromSnapshot(snapshot);
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, EmptyAccount, Account> initialHandler() {
    return commandHandlerBuilder(EmptyAccount.class)
      .matchCommand(CreateAccount.class, (__, cmd) -> Effect().persist(new AccountCreated()));
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, OpenedAccount, Account> openedAccountHandler() {
    return commandHandlerBuilder(OpenedAccount.class)
      .matchCommand(Deposit.class, (__, cmd) -> Effect().persist(new Deposited(cmd.amount)))
      .matchCommand(Withdraw.class, (acc, cmd) -> {
        if ((acc.getBalance() - cmd.amount) < 0.0) {
          return Effect().unhandled(); // TODO replies are missing in this example
        } else {
          return Effect().persist(new Withdrawn(cmd.amount))
            .andThen(acc2 -> { // FIXME in scaladsl it's named thenRun, change javadsl also?
              // we know this cast is safe, but somewhat ugly
              OpenedAccount openAccount = (OpenedAccount) acc2;
              // do some side-effect using balance
              System.out.println(openAccount.getBalance());
            });
        }
      })
      .matchCommand(CloseAccount.class, (acc, cmd) -> {
        if (acc.getBalance() == 0.0)
          return Effect().persist(new AccountClosed());
        else
          return Effect().unhandled();
        });
  }

  private CommandHandlerBuilder<AccountCommand, AccountEvent, ClosedAccount, Account> closedHandler() {
    return commandHandlerBuilder(ClosedAccount.class)
        .matchCommand(AccountCommand.class, (__, ___) -> Effect().unhandled());
  }

  @Override
  public CommandHandler<AccountCommand, AccountEvent, Account> commandHandler() {
    return initialHandler()
      .orElse(openedAccountHandler())
      .orElse(closedHandler())
      .build();
  }

  @Override
  public EventHandler<Account, AccountEvent> eventHandler() {
    return Account::applyEvent;
  }




}
