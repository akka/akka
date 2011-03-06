package akka.testkit

import akka.actor.dispatch.ActorModelSpec

class CallingThreadDispatcherModelSpec extends ActorModelSpec {
  import ActorModelSpec._
  def newInterceptedDispatcher = new CallingThreadDispatcher with MessageDispatcherInterceptor
  override def dispatcherShouldProcessMessagesInParallel {}
}

// vim: set ts=4 sw=4 et:
