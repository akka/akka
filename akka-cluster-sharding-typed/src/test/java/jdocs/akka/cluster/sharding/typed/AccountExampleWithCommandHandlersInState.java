/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.cluster.sharding.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;
import akka.cluster.sharding.typed.javadsl.EntityTypeKey;
import akka.cluster.sharding.typed.javadsl.EventSourcedEntityWithEnforcedReplies;
import akka.persistence.typed.ExpectingReply;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.CommandHandlerWithReply;
import akka.persistence.typed.javadsl.CommandHandlerWithReplyBuilder;
import akka.persistence.typed.javadsl.EventHandler;
import akka.persistence.typed.javadsl.EventHandlerBuilder;
import akka.persistence.typed.javadsl.ReplyEffect;

import java.math.BigDecimal;

/**
 * Bank account example illustrating: - different state classes representing the lifecycle of the
 * account - event handlers that delegate to methods in the state classes - command handlers that
 * delegate to methods in the state classes - replies of various types, using ExpectingReply and
 * EventSourcedBehaviorWithEnforcedReplies
 */
public interface AccountExampleWithCommandHandlersInState {

  // #account-entity
  public class AccountEntity
      extends EventSourcedEntityWithEnforcedReplies<
          AccountEntity.AccountCommand, AccountEntity.AccountEvent, AccountEntity.Account> {

    public static final EntityTypeKey<AccountCommand> ENTITY_TYPE_KEY =
        EntityTypeKey.create(AccountCommand.class, "Account");

    // Command
    interface AccountCommand<Reply> extends ExpectingReply<Reply> {}

    public static class CreateAccount implements AccountCommand<OperationResult> {
      private final ActorRef<OperationResult> replyTo;

      public CreateAccount(ActorRef<OperationResult> replyTo) {
        this.replyTo = replyTo;
      }

      @Override
      public ActorRef<OperationResult> replyTo() {
        return replyTo;
      }
    }

    public static class Deposit implements AccountCommand<OperationResult> {
      public final BigDecimal amount;
      private final ActorRef<OperationResult> replyTo;

      public Deposit(BigDecimal amount, ActorRef<OperationResult> replyTo) {
        this.replyTo = replyTo;
        this.amount = amount;
      }

      @Override
      public ActorRef<OperationResult> replyTo() {
        return replyTo;
      }
    }

    public static class Withdraw implements AccountCommand<OperationResult> {
      public final BigDecimal amount;
      private final ActorRef<OperationResult> replyTo;

      public Withdraw(BigDecimal amount, ActorRef<OperationResult> replyTo) {
        this.amount = amount;
        this.replyTo = replyTo;
      }

      @Override
      public ActorRef<OperationResult> replyTo() {
        return replyTo;
      }
    }

    public static class GetBalance implements AccountCommand<CurrentBalance> {
      private final ActorRef<CurrentBalance> replyTo;

      public GetBalance(ActorRef<CurrentBalance> replyTo) {
        this.replyTo = replyTo;
      }

      @Override
      public ActorRef<CurrentBalance> replyTo() {
        return replyTo;
      }
    }

    public static class CloseAccount implements AccountCommand<OperationResult> {
      private final ActorRef<OperationResult> replyTo;

      public CloseAccount(ActorRef<OperationResult> replyTo) {
        this.replyTo = replyTo;
      }

      @Override
      public ActorRef<OperationResult> replyTo() {
        return replyTo;
      }
    }

    // Reply
    interface AccountCommandReply {}

    interface OperationResult extends AccountCommandReply {}

    enum Confirmed implements OperationResult {
      INSTANCE
    }

    public static class Rejected implements OperationResult {
      public final String reason;

      public Rejected(String reason) {
        this.reason = reason;
      }
    }

    public static class CurrentBalance implements AccountCommandReply {
      public final BigDecimal balance;

      public CurrentBalance(BigDecimal balance) {
        this.balance = balance;
      }
    }

    // Event
    interface AccountEvent {}

    public static class AccountCreated implements AccountEvent {}

    public static class Deposited implements AccountEvent {
      public final BigDecimal amount;

      Deposited(BigDecimal amount) {
        this.amount = amount;
      }
    }

    public static class Withdrawn implements AccountEvent {
      public final BigDecimal amount;

      Withdrawn(BigDecimal amount) {
        this.amount = amount;
      }
    }

    public static class AccountClosed implements AccountEvent {}

    // State
    interface Account {}

    public class EmptyAccount implements Account {
      ReplyEffect<AccountEvent, Account> createAccount(CreateAccount command) {
        return Effect()
            .persist(new AccountCreated())
            .thenReply(command, account2 -> Confirmed.INSTANCE);
      }

      OpenedAccount openedAccount() {
        return new OpenedAccount(BigDecimal.ZERO);
      }
    }

    public class OpenedAccount implements Account {
      public final BigDecimal balance;

      public OpenedAccount(BigDecimal balance) {
        this.balance = balance;
      }

      ReplyEffect<AccountEvent, Account> deposit(Deposit command) {
        return Effect()
            .persist(new Deposited(command.amount))
            .thenReply(command, account2 -> Confirmed.INSTANCE);
      }

      ReplyEffect<AccountEvent, Account> withdraw(Withdraw command) {
        if (!canWithdraw(command.amount)) {
          return Effect()
              .reply(command, new Rejected("not enough funds to withdraw " + command.amount));
        } else {
          return Effect()
              .persist(new Withdrawn(command.amount))
              .thenReply(command, account2 -> Confirmed.INSTANCE);
        }
      }

      ReplyEffect<AccountEvent, Account> getBalance(GetBalance command) {
        return Effect().reply(command, new CurrentBalance(balance));
      }

      ReplyEffect<AccountEvent, Account> closeAccount(CloseAccount command) {
        if (balance.equals(BigDecimal.ZERO)) {
          return Effect()
              .persist(new AccountClosed())
              .thenReply(command, account2 -> Confirmed.INSTANCE);
        } else {
          return Effect().reply(command, new Rejected("balance must be zero for closing account"));
        }
      }

      OpenedAccount makeDeposit(BigDecimal amount) {
        return new OpenedAccount(balance.add(amount));
      }

      boolean canWithdraw(BigDecimal amount) {
        return (balance.subtract(amount).compareTo(BigDecimal.ZERO) >= 0);
      }

      OpenedAccount makeWithdraw(BigDecimal amount) {
        if (!canWithdraw(amount))
          throw new IllegalStateException("Account balance can't be negative");
        return new OpenedAccount(balance.subtract(amount));
      }

      ClosedAccount closedAccount() {
        return new ClosedAccount();
      }
    }

    public static class ClosedAccount implements Account {}

    public static AccountEntity create(String accountNumber) {
      return new AccountEntity(accountNumber);
    }

    public AccountEntity(String accountNumber) {
      super(ENTITY_TYPE_KEY, accountNumber);
    }

    @Override
    public Account emptyState() {
      return new EmptyAccount();
    }

    @Override
    public CommandHandlerWithReply<AccountCommand, AccountEvent, Account> commandHandler() {
      CommandHandlerWithReplyBuilder<AccountCommand, AccountEvent, Account> builder =
          newCommandHandlerWithReplyBuilder();

      builder
          .forStateType(EmptyAccount.class)
          .onCommand(CreateAccount.class, EmptyAccount::createAccount);

      builder
          .forStateType(OpenedAccount.class)
          .onCommand(Deposit.class, OpenedAccount::deposit)
          .onCommand(Withdraw.class, OpenedAccount::withdraw)
          .onCommand(GetBalance.class, OpenedAccount::getBalance)
          .onCommand(CloseAccount.class, OpenedAccount::closeAccount);

      builder
          .forStateType(ClosedAccount.class)
          .onAnyCommand(() -> Effect().unhandled().thenNoReply());

      return builder.build();
    }

    @Override
    public EventHandler<Account, AccountEvent> eventHandler() {
      EventHandlerBuilder<Account, AccountEvent> builder = newEventHandlerBuilder();

      builder
          .forStateType(EmptyAccount.class)
          .onEvent(AccountCreated.class, (account, event) -> account.openedAccount());

      builder
          .forStateType(OpenedAccount.class)
          .onEvent(
              Deposited.class,
              (account, deposited) -> {
                account.makeDeposit(deposited.amount);
                return account;
              })
          .onEvent(
              Withdrawn.class,
              (account, withdrawn) -> {
                account.makeWithdraw(withdrawn.amount);
                return account;
              })
          .onEvent(AccountClosed.class, (account, closed) -> account.closedAccount());

      return builder.build();
    }
  }

  // #account-entity
}
