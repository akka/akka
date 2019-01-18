/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;

public class AccountExampleOneLinersInModelWithNull
    extends EventSourcedBehavior<
        AccountExampleOneLinersInModelWithNull.AccountCommand,
        AccountExampleOneLinersInModelWithNull.AccountEvent,
        AccountExampleOneLinersInModelWithNull.Account> {

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

  public class OpenedAccount implements Account {
    public final double balance;

    OpenedAccount(double balance) {
      this.balance = balance;
    }

    Effect<AccountEvent, Account> depositCommand(Deposit deposit) {
      return Effect().persist(new Deposited(deposit.amount));
    }

    Effect<AccountEvent, Account> withdrawCommand(Withdraw withdraw) {
      if ((balance - withdraw.amount) < 0.0) {
        return Effect().unhandled(); // TODO replies are missing in this example
      } else {
        return Effect()
            .persist(new Withdrawn(withdraw.amount))
            .thenRun(
                acc2 -> {
                  // we know this cast is safe, but somewhat ugly
                  OpenedAccount openAccount = (OpenedAccount) acc2;
                  // do some side-effect using balance
                  System.out.println(openAccount.balance);
                });
      }
    }

    Effect<AccountEvent, Account> closeCommand(CloseAccount cmd) {
      if (balance == 0.0) return Effect().persist(new AccountClosed());
      else return Effect().unhandled();
    }

    OpenedAccount makeDeposit(Deposited deposit) {
      return new OpenedAccount(balance + deposit.amount);
    }

    OpenedAccount makeWithdraw(Withdrawn withdrawn) {
      return new OpenedAccount(balance - withdrawn.amount);
    }

    ClosedAccount closeAccount(AccountClosed cmd) {
      return new ClosedAccount();
    }
  }

  public class ClosedAccount implements Account {}

  public static Behavior<AccountCommand> behavior(String accountNumber) {
    return Behaviors.setup(
        context -> new AccountExampleOneLinersInModelWithNull(context, accountNumber));
  }

  public AccountExampleOneLinersInModelWithNull(
      ActorContext<AccountCommand> context, String accountNumber) {
    super(new PersistenceId(accountNumber));
  }

  @Override
  public Account emptyState() {
    return null;
  }

  private Effect<AccountEvent, Account> createAccount(CreateAccount cmd) {
    return Effect().persist(new AccountCreated());
  }

  private OpenedAccount openAccount() {
    return new OpenedAccount(0.0);
  }

  @Override
  public CommandHandler<AccountCommand, AccountEvent, Account> commandHandler() {

    CommandHandlerBuilder<AccountCommand, AccountEvent, Account> builder =
        newCommandHandlerBuilder();

    builder.forNullState().matchCommand(CreateAccount.class, this::createAccount);

    builder
        .forStateType(OpenedAccount.class)
        .matchCommand(Deposit.class, OpenedAccount::depositCommand)
        .matchCommand(Withdraw.class, OpenedAccount::withdrawCommand)
        .matchCommand(CloseAccount.class, OpenedAccount::closeCommand);

    builder.forStateType(ClosedAccount.class).matchAny(() -> Effect().unhandled());

    return builder.build();
  }

  @Override
  public EventHandler<Account, AccountEvent> eventHandler() {

    EventHandlerBuilder<Account, AccountEvent> builder = newEventHandlerBuilder();

    builder.forNullState().matchEvent(AccountCreated.class, this::openAccount);

    builder
        .forStateType(OpenedAccount.class)
        .matchEvent(Deposited.class, OpenedAccount::makeDeposit)
        .matchEvent(Withdrawn.class, OpenedAccount::makeWithdraw)
        .matchEvent(AccountClosed.class, OpenedAccount::closeAccount);

    return builder.build();
  }
}
