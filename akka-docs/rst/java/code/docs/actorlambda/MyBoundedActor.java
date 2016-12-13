/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda;

//#my-bounded-untyped-actor
import akka.dispatch.BoundedMessageQueueSemantics;
import akka.dispatch.RequiresMessageQueue;

public class MyBoundedActor extends MyActor 
  implements RequiresMessageQueue<BoundedMessageQueueSemantics> {
}
//#my-bounded-untyped-actor
