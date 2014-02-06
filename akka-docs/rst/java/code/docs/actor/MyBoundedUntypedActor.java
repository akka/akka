/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.actor;

//#my-bounded-untyped-actor
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

public class MyBoundedUntypedActor extends MyUntypedActor
  implements RequiresMessageQueue<BoundedMessageQueueSemantics> {
}
//#my-bounded-untyped-actor
