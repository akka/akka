/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandler;
import akka.persistence.typed.javadsl.Effect;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.PersistentBehavior;

public class AccountExample21 {

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

  interface Account {}
  public static class OpenedAccount implements Account {
    public final double balance;

    OpenedAccount(double balance) {
      this.balance = balance;
    }
  }
  public static class ClosedAccount implements Account {
  }

  public static class AccountEntity extends PersistentBehavior<AccountCommand, AccountEvent, Account> {

    public AccountEntity(String accountNumber) {
      super(new PersistenceId("Account|" + accountNumber));
    }

    @Override
    public Account emptyState() {
      return null; // starting with null state instead of EmptyAccount
    }

    @CmdHandler
    public Effect<AccountEvent, Account> handleCommand(CreateAccount cmd) {
      // note that no state parameter, can be used for null initial state
      return Effect().persist(new AccountCreated());
    }

    @CmdHandler
    public Effect<AccountEvent, Account> handleCommand(ClosedAccount state, AccountCommand cmd) {
      // not necessary to have this handler, default is also unhandled, unless we want to warn about missing handlers
      return Effect().unhandled();
    }

    @CmdHandler
    public Effect<AccountEvent, Account> handleCommand(OpenedAccount state, Deposit cmd) {
      return Effect().persist(new Deposited(cmd.amount));
    }

    @CmdHandler
    public Effect<AccountEvent, Account> handleCommand(OpenedAccount state, Withdraw cmd) {
      if ((state.balance - cmd.amount) < 0.0) {
        return Effect().unhandled(); // TODO replies are missing in this example
      } else {
        // Type inference doesn't work well here, Effects.<AccountEvent, Account> persist(
        return Effect().persist(new Withdrawn(cmd.amount))
            .andThen(acc2 -> {
              // we know this cast is safe, but somewhat ugly
              OpenedAccount openAccount = (OpenedAccount) acc2;
              // do some side-effect using balance
              System.out.println(openAccount.balance);
            });
      }
    }

    @CmdHandler
    public Effect<AccountEvent, Account> handleCommand(OpenedAccount state, CloseAccount cmd) {
      if (state.balance == 0.0)
        return Effect().persist(new AccountClosed());
      else
        return Effect().unhandled();
    }

    @EvtHandler
    public Account handleEvent(OpenedAccount state, Deposited evt) {
      return new OpenedAccount(state.balance + evt.amount);
    }

    @EvtHandler
    public Account handleEvent(OpenedAccount state, Withdrawn evt) {
      return new OpenedAccount(state.balance - evt.amount);
    }

    @EvtHandler
    public Account handleEvent(OpenedAccount state, AccountClosed evt) {
      return new ClosedAccount();
    }

    @Override
    public CommandHandler<AccountCommand, AccountEvent, Account> commandHandler() {
      // FIXME this will not be needed when using annotations
      return null;
    }

    @Override
    public EventHandler<Account, AccountEvent> eventHandler() {
      // FIXME this will not be needed when using annotations
      return null;
    }
  }


}
