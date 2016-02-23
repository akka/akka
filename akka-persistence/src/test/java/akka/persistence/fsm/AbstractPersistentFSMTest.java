/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.persistence.fsm;

import akka.actor.*;
import akka.japi.Option;
import akka.persistence.PersistenceSpec;
import akka.testkit.AkkaJUnitActorSystemResource;
import akka.testkit.JavaTestKit;
import akka.testkit.TestProbe;
import org.junit.ClassRule;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import akka.persistence.fsm.PersistentFSM.CurrentState;
import org.junit.Test;
import scala.concurrent.duration.Duration;

import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.UserState;
import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.ShoppingCart;
import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.Item;

import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.GetCurrentCart;
import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.AddItem;
import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.Buy;
import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.Leave;

import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.PurchaseWasMade;
import static akka.persistence.fsm.AbstractPersistentFSMTest.WebStoreCustomerFSM.ShoppingCardDiscarded;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.matchers.JUnitMatchers.hasItems;

public class AbstractPersistentFSMTest {
    private static Option<String> none = Option.none();

    @ClassRule
    public static AkkaJUnitActorSystemResource actorSystemResource =
            new AkkaJUnitActorSystemResource("PersistentFSMJavaTest", PersistenceSpec.config(
                    "leveldb", "AbstractPersistentFSMTest", "off", none.asScala()));

    private final ActorSystem system = actorSystemResource.getSystem();

    //Dummy report actor, for tests that don't need it
    private final ActorRef dummyReportActorRef = new TestProbe(system).ref();

    @Test
    public void fsmFunctionalTest() throws Exception {
        new JavaTestKit(system) {{
            String persistenceId = generateId();
            ActorRef fsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, dummyReportActorRef));

            watch(fsmRef);
            fsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            Item shirt = new Item("1", "Shirt", 59.99F);
            Item shoes = new Item("2", "Shoes", 89.99F);
            Item coat = new Item("3", "Coat", 119.99F);

            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            fsmRef.tell(new AddItem(shirt), getRef());
            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            fsmRef.tell(new AddItem(shoes), getRef());
            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            fsmRef.tell(new AddItem(coat), getRef());
            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            fsmRef.tell(Buy.INSTANCE, getRef());
            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            fsmRef.tell(Leave.INSTANCE, getRef());

            CurrentState currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.LOOKING_AROUND);

            ShoppingCart shoppingCart = expectMsgClass(ShoppingCart.class);
            assertTrue(shoppingCart.getItems().isEmpty());

            PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.LOOKING_AROUND, UserState.SHOPPING);

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt));

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt, shoes));

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt, shoes, coat));

            stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.SHOPPING, UserState.PAID);

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt, shoes, coat));

            Terminated terminated = expectMsgClass(Terminated.class);
            assertEquals(fsmRef, terminated.getActor());
        }};
    }

    @Test
    public void fsmTimeoutTest() throws Exception {
        new JavaTestKit(system) {{
            String persistenceId = generateId();
            ActorRef fsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, dummyReportActorRef));

            watch(fsmRef);
            fsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            Item shirt = new Item("1", "Shirt", 59.99F);

            fsmRef.tell(new AddItem(shirt), getRef());

            CurrentState currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.LOOKING_AROUND);

            PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.LOOKING_AROUND, UserState.SHOPPING);

            new Within(duration("0.9 seconds"), duration("1.9 seconds")) {
                @Override
                protected void run() {
                    PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
                    assertTransition(stateTransition, fsmRef, UserState.SHOPPING, UserState.INACTIVE);
                }
            };

            new Within(duration("1.9 seconds"), duration("2.9 seconds")) {
                @Override
                protected void run() {
                    expectTerminated(fsmRef);
                }
            };
        }};
    }

    @Test
    public void testSuccessfulRecoveryWithCorrectStateData() {
        new JavaTestKit(system) {{
            String persistenceId = generateId();
            ActorRef fsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, dummyReportActorRef));

            watch(fsmRef);
            fsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            Item shirt = new Item("1", "Shirt", 59.99F);
            Item shoes = new Item("2", "Shoes", 89.99F);
            Item coat = new Item("3", "Coat", 119.99F);

            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            fsmRef.tell(new AddItem(shirt), getRef());
            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            fsmRef.tell(new AddItem(shoes), getRef());
            fsmRef.tell(GetCurrentCart.INSTANCE, getRef());

            CurrentState currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.LOOKING_AROUND);

            ShoppingCart shoppingCart = expectMsgClass(ShoppingCart.class);
            assertTrue(shoppingCart.getItems().isEmpty());

            PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.LOOKING_AROUND, UserState.SHOPPING);

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt));

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt, shoes));

            fsmRef.tell(PoisonPill.getInstance(), ActorRef.noSender());
            expectTerminated(fsmRef);

            ActorRef recoveredFsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, dummyReportActorRef));
            watch(recoveredFsmRef);
            recoveredFsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            recoveredFsmRef.tell(GetCurrentCart.INSTANCE, getRef());

            recoveredFsmRef.tell(new AddItem(coat), getRef());
            recoveredFsmRef.tell(GetCurrentCart.INSTANCE, getRef());

            recoveredFsmRef.tell(Buy.INSTANCE, getRef());
            recoveredFsmRef.tell(GetCurrentCart.INSTANCE, getRef());
            recoveredFsmRef.tell(Leave.INSTANCE, getRef());

            currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.SHOPPING);

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt, shoes));

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt, shoes, coat));

            stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, recoveredFsmRef, UserState.SHOPPING, UserState.PAID);

            shoppingCart = expectMsgClass(ShoppingCart.class);
            assertThat(shoppingCart.getItems(), hasItems(shirt, shoes, coat));

            expectTerminated(recoveredFsmRef);
        }};
    }

    @Test
    public void testExecutionOfDefinedActionsFollowingSuccessfulPersistence() {
        new JavaTestKit(system) {{
            String persistenceId = generateId();

            TestProbe reportActorProbe = new TestProbe(system);
            ActorRef fsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, reportActorProbe.ref()));

            watch(fsmRef);
            fsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            Item shirt = new Item("1", "Shirt", 59.99F);
            Item shoes = new Item("2", "Shoes", 89.99F);
            Item coat = new Item("3", "Coat", 119.99F);

            fsmRef.tell(new AddItem(shirt), getRef());
            fsmRef.tell(new AddItem(shoes), getRef());
            fsmRef.tell(new AddItem(coat), getRef());
            fsmRef.tell(Buy.INSTANCE, getRef());
            fsmRef.tell(Leave.INSTANCE, getRef());

            CurrentState currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.LOOKING_AROUND);

            PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.LOOKING_AROUND, UserState.SHOPPING);

            stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.SHOPPING, UserState.PAID);

            PurchaseWasMade purchaseWasMade = reportActorProbe.expectMsgClass(PurchaseWasMade.class);
            assertThat(purchaseWasMade.getItems(), hasItems(shirt, shoes, coat));

            expectTerminated(fsmRef);
        }};
    }

    @Test
    public void testExecutionOfDefinedActionsFollowingSuccessfulPersistenceOfFSMStop() {
        new JavaTestKit(system) {{
            String persistenceId = generateId();

            TestProbe reportActorProbe = new TestProbe(system);
            ActorRef fsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, reportActorProbe.ref()));

            watch(fsmRef);
            fsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            Item shirt = new Item("1", "Shirt", 59.99F);
            Item shoes = new Item("2", "Shoes", 89.99F);
            Item coat = new Item("3", "Coat", 119.99F);

            fsmRef.tell(new AddItem(shirt), getRef());
            fsmRef.tell(new AddItem(shoes), getRef());
            fsmRef.tell(new AddItem(coat), getRef());
            fsmRef.tell(Leave.INSTANCE, getRef());

            CurrentState currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.LOOKING_AROUND);

            PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.LOOKING_AROUND, UserState.SHOPPING);

            reportActorProbe.expectMsgClass(ShoppingCardDiscarded.class);

            expectTerminated(fsmRef);
        }};
    }

    @Test
    public void testCorrectStateTimeoutFollowingRecovery() {
        new JavaTestKit(system) {{
            String persistenceId = generateId();
            ActorRef fsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, dummyReportActorRef));

            watch(fsmRef);
            fsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            Item shirt = new Item("1", "Shirt", 59.99F);

            fsmRef.tell(new AddItem(shirt), getRef());

            CurrentState currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.LOOKING_AROUND);

            PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
            assertTransition(stateTransition, fsmRef, UserState.LOOKING_AROUND, UserState.SHOPPING);

            expectNoMsg(duration("0.6seconds")); //randomly chosen delay, less than the timeout, before stopping the FSM
            fsmRef.tell(PoisonPill.getInstance(), ActorRef.noSender());
            expectTerminated(fsmRef);

            final ActorRef recoveredFsmRef = system.actorOf(WebStoreCustomerFSM.props(persistenceId, dummyReportActorRef));
            watch(recoveredFsmRef);
            recoveredFsmRef.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());


            currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.SHOPPING);

            new Within(duration("0.9 seconds"), duration("1.9 seconds")) {
                @Override
                protected void run() {
                    PersistentFSM.Transition stateTransition = expectMsgClass(PersistentFSM.Transition.class);
                    assertTransition(stateTransition, recoveredFsmRef, UserState.SHOPPING, UserState.INACTIVE);
                }
            };

            expectNoMsg(duration("0.9 seconds")); //randomly chosen delay, less than the timeout, before stopping the FSM
            recoveredFsmRef.tell(PoisonPill.getInstance(), ActorRef.noSender());
            expectTerminated(recoveredFsmRef);

            final ActorRef recoveredFsmRef2 = system.actorOf(WebStoreCustomerFSM.props(persistenceId, dummyReportActorRef));
            watch(recoveredFsmRef2);
            recoveredFsmRef2.tell(new PersistentFSM.SubscribeTransitionCallBack(getRef()), getRef());

            currentState = expectMsgClass(akka.persistence.fsm.PersistentFSM.CurrentState.class);
            assertEquals(currentState.state(), UserState.INACTIVE);

            new Within(duration("1.9 seconds"), duration("2.9 seconds")) {
                @Override
                protected void run() {
                    expectTerminated(recoveredFsmRef2);
                }
            };
        }};
    }


    private static <State, From extends State, To extends State> void assertTransition(PersistentFSM.Transition transition, ActorRef ref, From from, To to) {
        assertEquals(ref, transition.fsmRef());
        assertEquals(from, transition.from());
        assertEquals(to, transition.to());
    }

    private static String generateId() {
        return UUID.randomUUID().toString();
    }


    public static class WebStoreCustomerFSM extends AbstractPersistentFSM<WebStoreCustomerFSM.UserState, WebStoreCustomerFSM.ShoppingCart, WebStoreCustomerFSM.DomainEvent> {

        //State name
        //#customer-states
        enum UserState implements PersistentFSM.FSMState {
            LOOKING_AROUND("Looking Around"),
            SHOPPING("Shopping"),
            INACTIVE("Inactive"),
            PAID("Paid");

            private final String stateIdentifier;

            UserState(String stateIdentifier) {
                this.stateIdentifier = stateIdentifier;
            }

            @Override
            public String identifier() {
                return stateIdentifier;
            }
        }
        //#customer-states

        //#customer-states-data
        static class ShoppingCart {
            private final List<Item> items = new ArrayList<>();

            public List<Item> getItems() {
                return Collections.unmodifiableList(items);
            }

            void addItem(Item item) {
                items.add(item);
            }

            void empty() {
                items.clear();
            }
        }

        static class Item implements Serializable {
            private final String id;
            private final String name;
            private final float price;

            Item(String id, String name, float price) {
                this.id = id;
                this.name = name;
                this.price = price;
            }

            public String getId() {
                return id;
            }

            public float getPrice() {
                return price;
            }

            public String getName() {
                return name;
            }

            @Override
            public String toString() {
                return String.format("Item{id=%s, name=%s, price=%s}", id, price, name);
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;

                Item item = (Item) o;

                return item.price == price && id.equals(item.id) && name.equals(item.name);
            }
        }
        //#customer-states-data

        public interface Command {
        }

        //#customer-commands
        public static final class AddItem implements Command {
            private final Item item;

            public AddItem(Item item) {
                this.item = item;
            }

            public Item getItem() {
                return item;
            }
        }

        public enum Buy implements Command {INSTANCE}

        public enum Leave implements Command {INSTANCE}

        public enum GetCurrentCart implements Command {INSTANCE}
        //#customer-commands

        interface DomainEvent extends Serializable {
        }

        //#customer-domain-events
        public static final class ItemAdded implements DomainEvent {
            private final Item item;

            public ItemAdded(Item item) {
                this.item = item;
            }

            public Item getItem() {
                return item;
            }
        }

        public enum OrderExecuted implements DomainEvent {INSTANCE}

        public enum OrderDiscarded implements DomainEvent {INSTANCE}
        //#customer-domain-events


        //Side effects - report events to be sent to some "Report Actor"
        public interface ReportEvent {
        }

        public static final class PurchaseWasMade implements ReportEvent {
            private final List<Item> items;

            public PurchaseWasMade(List<Item> items) {
                this.items = Collections.unmodifiableList(items);
            }

            public List<Item> getItems() {
                return items;
            }
        }

        public enum ShoppingCardDiscarded implements ReportEvent {INSTANCE}

        final private String persistenceId;

        @Override
        public Class<DomainEvent> domainEventClass() {
            return DomainEvent.class;
        }

        @Override
        public String persistenceId() {
            return persistenceId;
        }

        public static Props props(String persistenceId, ActorRef reportActor) {
            return Props.create(WebStoreCustomerFSM.class, persistenceId, reportActor);
        }

        public WebStoreCustomerFSM(String persistenceId, ActorRef reportActor) {
            this.persistenceId = persistenceId;

            //#customer-fsm-body
            startWith(UserState.LOOKING_AROUND, new ShoppingCart());

            when(UserState.LOOKING_AROUND,
                matchEvent(AddItem.class,
                    (event, data) ->
                        goTo(UserState.SHOPPING).applying(new ItemAdded(event.getItem()))
                            .forMax(Duration.create(1, TimeUnit.SECONDS))
                )
                .event(GetCurrentCart.class, (event, data) -> stay().replying(data))
            );

            when(UserState.SHOPPING,
                matchEvent(AddItem.class,
                    (event, data) ->
                        stay().applying(new ItemAdded(event.getItem()))
                           .forMax(Duration.create(1, TimeUnit.SECONDS)))
                .event(Buy.class,
                    (event, data) ->
                        goTo(UserState.PAID).applying(OrderExecuted.INSTANCE)
                            .andThen(exec(cart ->
                                reportActor.tell(new PurchaseWasMade(cart.getItems()), self()))
                            ))
                .event(Leave.class,
                    (event, data) ->
                        stop().applying(OrderDiscarded.INSTANCE)
                            .andThen(exec(cart ->
                               reportActor.tell(ShoppingCardDiscarded.INSTANCE, self())
                            )))
                .event(GetCurrentCart.class, (event, data) -> stay().replying(data))
                .event(StateTimeout$.class,
                    (event, data) ->
                        goTo(UserState.INACTIVE).forMax(Duration.create(2, TimeUnit.SECONDS)))
            );


            when(UserState.INACTIVE,
                matchEvent(AddItem.class,
                    (event, data) ->
                        goTo(UserState.SHOPPING).applying(new ItemAdded(event.getItem()))
                            .forMax(Duration.create(1, TimeUnit.SECONDS)))
                .event(GetCurrentCart.class, (event, data) -> stay().replying(data))
                .event(StateTimeout$.class,
                    (event, data) ->
                        stop().applying(OrderDiscarded.INSTANCE)
                            .andThen(exec(cart ->
                                reportActor.tell(ShoppingCardDiscarded.INSTANCE, self())
                            )))
            );

            when(UserState.PAID,
                matchEvent(Leave.class, (event, data) -> stop())
                .event(GetCurrentCart.class, (event, data) -> stay().replying(data))
            );

            initialize();
            //#customer-fsm-body
        }

        /**
         * Override this handler to define the action on Domain Event during recovery
         *
         * @param event       domain event to apply
         * @param currentData state data of the previous state
         */
        //#customer-apply-event
        @Override
        public ShoppingCart applyEvent(DomainEvent event, ShoppingCart currentData) {
            if (event instanceof ItemAdded) {
                currentData.addItem(((ItemAdded) event).getItem());
                return currentData;
            } else if (event instanceof OrderExecuted) {
                return currentData;
            } else if (event instanceof OrderDiscarded) {
                currentData.empty();
                return currentData;
            }
            throw new RuntimeException("Unhandled");
        }
        //#customer-apply-event
    }
}
