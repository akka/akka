/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka.dispatch

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
class ForkJoinBasedEventDrivenDispatcher(val name: String) extends MessageDispatcher {
  @volatile private var active: Boolean = false

  private val scheduler = new scala.actors.FJTaskScheduler2

  // FIXME: add name "event-driven:fork-join:dispatcher" + name
  def dispatch(invocation: MessageInvocation) = {
    scheduler.execute(new Runnable() {
      def run = {
        invocation.invoke
      }
    })
  }

  def start = if (!active) {
    active = true
  }

  def canBeShutDown = true

  def shutdown = if (active) {
    active = false
  }

  def registerHandler(key: AnyRef, handler: MessageInvoker) = {}
  def unregisterHandler(key: AnyRef) = {}
}