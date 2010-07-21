/**
 * Copyright (C) 2010, Progress Software Corporation and/or its
 * subsidiaries or affiliates.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.scalablesolutions.akka.dispatch

import se.scalablesolutions.akka.dispatch.{MessageInvocation, MessageDispatcher}
import se.scalablesolutions.akka.actor.ActorRef
import org.fusesource.hawtdispatch.DispatchQueue
import org.fusesource.hawtdispatch.ScalaDispatch._
import actors.threadpool.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * <p>
 * An HawtDispatch based MessageDispatcher.
 * </p>
 *
 * @author <a href="http://hiramchirino.com">Hiram Chirino</a>
 */
class HawtDispatchEventDrivenDispatcher(val name: String) extends MessageDispatcher  {

  // a counter used to track if the dispatcher is in use.
  private val active = new AtomicBoolean()
  private val retained = new AtomicInteger

  def start = {
    val rc = active.compareAndSet(false, true)
    assert( rc )
    retained.incrementAndGet
  }

  def shutdown = {
    val rc = active.compareAndSet(true, false)
    assert( rc )
    retained.decrementAndGet
  }

  def isShutdown = {
    retained.get == 0
  }

  def dispatch(invocation: MessageInvocation) = if(active.get) {
    retained.incrementAndGet
    getMailbox(invocation.receiver) {
      try {
        invocation.invoke
      } finally {
        retained.decrementAndGet
      }
    }
  } else {
    log.warning("%s is shut down,\n\tignoring the the messages sent to\n\t%s", toString, invocation.receiver)
  }

  /**
   * @return the mailbox associated with the actor
   */
  private def getMailbox(receiver: ActorRef) = receiver.mailbox.asInstanceOf[DispatchQueue]

  // hawtdispatch does not have a way to get queue sizes, getting an accurate
  // size can cause extra contention.. is this really needed?
  // TODO: figure out if this can be optional in akka
  override def mailboxSize(actorRef: ActorRef) = 0

  override def register(actorRef: ActorRef) = {
    if( actorRef.mailbox == null ) {
      actorRef.mailbox = createQueue(actorRef.toString)
    }
    super.register(actorRef)
  }

  override def toString = "HawtDispatchEventDrivenDispatcher["+name+"]"

}