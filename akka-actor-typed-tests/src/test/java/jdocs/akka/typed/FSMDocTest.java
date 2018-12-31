/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.Behaviors;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

public class FSMDocTest {

    //#simple-events
    interface Event { }
    //#simple-events

    static
    //#simple-events
    public final class SetTarget implements Event {
        private final ActorRef<Batch> ref;

        public SetTarget(ActorRef<Batch> ref) {
            this.ref = ref;
        }

        public ActorRef<Batch> getRef() {
            return ref;
        }
    }
    //#simple-events

    static
    //#simple-events
    final class Timeout implements Event {}
    final static Timeout TIMEOUT = new Timeout();
    //#simple-events

    //#simple-events
    public enum Flush implements Event {
        FLUSH
    }
    //#simple-events

    static
    //#simple-events
    public final class Queue implements Event {
        private final Object obj;

        public Queue(Object obj) {
            this.obj = obj;
        }

        public Object getObj() {
            return obj;
        }
    }

    //#simple-events
    static public final class Batch {
        private final List<Object> list;

        public Batch(List<Object> list) {
            this.list = list;
        }

        public List<Object> getList() {
            return list;
        }
        //#boilerplate

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Batch batch = (Batch) o;

            return list.equals(batch.list);
        }

        @Override
        public int hashCode() {
            return list.hashCode();
        }

        @Override
        public String toString() {
            final StringBuilder builder = new StringBuilder();
            builder.append("Batch{list=");
            list.stream().forEachOrdered(e -> {
                builder.append(e);
                builder.append(",");
            });
            int len = builder.length();
            builder.replace(len, len, "}");
            return builder.toString();
        }
        //#boilerplate
    }

    //#storing-state
    interface Data {
    }
    //#storing-state

    static
    //#storing-state
    final class Todo implements Data {
        private final ActorRef<Batch> target;
        private final List<Object> queue;

        public Todo(ActorRef<Batch> target, List<Object> queue) {
            this.target = target;
            this.queue = queue;
        }

        public ActorRef<Batch> getTarget() {
            return target;
        }

        public List<Object> getQueue() {
            return queue;
        }
        //#boilerplate

        @Override
        public String toString() {
            return "Todo{" +
                    "target=" + target +
                    ", queue=" + queue +
                    '}';
        }

        public Todo addElement(Object element) {
            List<Object> nQueue = new LinkedList<>(queue);
            nQueue.add(element);
            return new Todo(this.target, nQueue);
        }

        public Todo copy(List<Object> queue) {
            return new Todo(this.target, queue);
        }

        public Todo copy(ActorRef<Batch> target) {
            return new Todo(target, this.queue);
        }
        //#boilerplate
    }
    //#storing-state

    //#simple-state
    // FSM states represented as behaviors
    private static Behavior<Event> uninitialized() {
        return Behaviors.receive(Event.class)
                .onMessage(SetTarget.class, (context, message) -> idle(new Todo(message.getRef(), Collections.emptyList())))
                .build();
    }

    private static Behavior<Event> idle(Todo data) {
        return Behaviors.receive(Event.class)
                .onMessage(Queue.class, (context, message) -> active(data.addElement(message)))
                .build();
    }

    private static Behavior<Event> active(Todo data) {
        return Behaviors.withTimers(timers -> {
            // State timeouts done with withTimers
            timers.startSingleTimer("Timeout", TIMEOUT, Duration.ofSeconds(1));
            return Behaviors.receive(Event.class)
                    .onMessage(Queue.class, (context, message) -> active(data.addElement(message)))
                    .onMessage(Flush.class, (context, message) -> {
                        data.getTarget().tell(new Batch(data.queue));
                       return idle(data.copy(new ArrayList<>()));
                    })
                    .onMessage(Timeout.class, (context, message) -> {
                        data.getTarget().tell(new Batch(data.queue));
                        return idle(data.copy(new ArrayList<>()));
                    }).build();
        });
    }
    //#simple-state

}
