/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.actor;

// #my-bounded-untyped-actor
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

public class MyBoundedActor extends MyActor
    implements RequiresMessageQueue<BoundedMessageQueueSemantics> {}
// #my-bounded-untyped-actor
