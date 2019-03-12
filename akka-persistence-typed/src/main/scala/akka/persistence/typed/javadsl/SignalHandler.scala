/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.javadsl

import akka.actor.typed.Signal
import akka.annotation.InternalApi
import akka.japi.function.Procedure
import akka.japi.function.{ Effect ⇒ JEffect }

object SignalHandler {
  val Empty: SignalHandler = new SignalHandler(PartialFunction.empty)
}

final class SignalHandler(_handler: PartialFunction[Signal, Unit]) {

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] def isEmpty: Boolean = _handler eq PartialFunction.empty

  /**
   * INTERNAL API
   */
  @InternalApi
  private[akka] def handler: PartialFunction[Signal, Unit] = _handler
}

/**
 * Mutable builder for handling signals in [[EventSourcedBehavior]]
 *
 * Not for user instantiation, use [[EventSourcedBehavior#newSignalHandlerBuilder()]] to get an instance.
 */
final class SignalHandlerBuilder {

  private var handler: PartialFunction[Signal, Unit] = PartialFunction.empty

  /**
   * If the behavior recieves a signal of type `T`, `callback` is invoked with the signal instance as input.
   */
  def onSignal[T <: Signal](signalType: Class[T], callback: Procedure[T]): SignalHandlerBuilder = {
    val newPF: PartialFunction[Signal, Unit] = {
      case t if signalType.isInstance(t) ⇒
        callback(t.asInstanceOf[T])
    }
    handler = newPF.orElse(handler)
    this
  }

  /**
   * If the behavior receives exactly the signal `signal`, `callback` is invoked.
   */
  def onSignal[T <: Signal](signal: T, callback: JEffect): SignalHandlerBuilder = {
    val newPF: PartialFunction[Signal, Unit] = {
      case `signal` ⇒
        callback()
    }
    handler = newPF.orElse(handler)
    this
  }

  def build: SignalHandler = new SignalHandler(handler)

}
