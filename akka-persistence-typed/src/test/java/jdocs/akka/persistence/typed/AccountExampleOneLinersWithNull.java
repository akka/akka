/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.persistence.typed;

import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.persistence.typed.PersistenceId;
import akka.persistence.typed.javadsl.*;

public class AccountExampleOneLinersWithNull extends EventSourcedBehavior<AccountExampleOneLinersWithNull.AccountCommand, AccountExampleOneLinersWithNull.AccountEvent, AccountExampleOneLinersWithNull.Account> {

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
    public static class ClosedAccount implements Account {}

    public static Behavior<AccountCommand> behavior(String accountNumber) {
        return Behaviors.setup(context -> new AccountExampleOneLinersWithNull(context, accountNumber));
    }

    public AccountExampleOneLinersWithNull(ActorContext<AccountCommand> context, String accountNumber) {
        super(new PersistenceId(accountNumber));
    }

    @Override
    public Account emptyState() {
        return null;
    }

    private Effect<AccountEvent, Account> createAccount() {
        return Effect().persist(new AccountCreated());
    }

    private Effect<AccountEvent, Account> depositCommand(Deposit deposit) {
        return Effect().persist(new Deposited(deposit.amount));
    }

    private Effect<AccountEvent, Account> withdrawCommand(OpenedAccount account, Withdraw withdraw) {
        if ((account.balance - withdraw.amount) < 0.0) {
            return Effect().unhandled(); // TODO replies are missing in this example
        } else {
            return Effect().persist(new Withdrawn(withdraw.amount))
                    .thenRun(acc2 -> {
                        // we know this cast is safe, but somewhat ugly
                        OpenedAccount openAccount = (OpenedAccount) acc2;
                        // do some side-effect using balance
                        System.out.println(openAccount.balance);
                    });
        }
    }

    private Effect<AccountEvent, Account> closeCommand(OpenedAccount account, CloseAccount cmd) {
        if (account.balance == 0.0)
            return Effect().persist(new AccountClosed());
        else
            return Effect().unhandled();
    }


    private CommandHandlerBuilderByState<AccountCommand, AccountEvent, Account, Account> initialHandler() {
        return newCommandHandlerBuilder().forNullState()
                .matchCommand(CreateAccount.class, this::createAccount);
    }

    private CommandHandlerBuilderByState<AccountCommand, AccountEvent, OpenedAccount, Account> openedAccountHandler() {
        return newCommandHandlerBuilder().forStateType(OpenedAccount.class)
                .matchCommand(Deposit.class, this::depositCommand)
                .matchCommand(Withdraw.class, this::withdrawCommand)
                .matchCommand(CloseAccount.class, this::closeCommand);
    }

    private CommandHandlerBuilderByState<AccountCommand, AccountEvent, ClosedAccount, Account> closedHandler() {
        return newCommandHandlerBuilder().forStateType(ClosedAccount.class)
                .matchAny(() -> Effect().unhandled());
    }

    @Override
    public CommandHandler<AccountCommand, AccountEvent, Account> commandHandler() {
        return initialHandler()
                .orElse(openedAccountHandler())
                .orElse(closedHandler())
                .build();
    }


    private OpenedAccount openAccount() {
        return new OpenedAccount(0.0);
    }

    private OpenedAccount makeDeposit(OpenedAccount acc, Deposited deposit) {
        return new OpenedAccount(acc.balance + deposit.amount);
    }

    private OpenedAccount makeWithdraw(OpenedAccount acc, Withdrawn withdrawn) {
        return new OpenedAccount(acc.balance - withdrawn.amount);
    }

    private ClosedAccount closeAccount() {
        return new ClosedAccount();
    }

    private EventHandlerBuilderByState<Account, Account, AccountEvent> initialEvtHandler() {
        return newEventHandlerBuilder()
                .forNullState()
                .matchEvent(AccountCreated.class, this::openAccount);
    }

    private EventHandlerBuilderByState<OpenedAccount, Account, AccountEvent> openedAccountEvtHandler() {
        return newEventHandlerBuilder()
                .forStateType(OpenedAccount.class)
                .matchEvent(Deposited.class, this::makeDeposit)
                .matchEvent(Withdrawn.class, this::makeWithdraw)
                .matchEvent(AccountClosed.class, ClosedAccount::new);
    }
    @Override
    public EventHandler<Account, AccountEvent> eventHandler() {
        return initialEvtHandler().orElse(openedAccountEvtHandler()).build();
    }


}

