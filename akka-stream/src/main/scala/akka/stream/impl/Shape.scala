/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.stream.{ Inlet, Outlet }

/**
 * An output port of a StreamLayout.Module.
 * It is also used in the Java DSL for “untyped Inlets” as a work-around
 * for otherwise unreasonable existential types.
 */
abstract class InPort { self: Inlet[_] ⇒
  final override def hashCode: Int = super.hashCode
  final override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]

  /**
   * INTERNAL API
   */
  @volatile private[stream] var id: Int = -1

  /**
   * INTERNAL API
   */
  @volatile private[stream] var mappedTo: InPort = this

  /**
   * INTERNAL API
   */
  private[stream] def inlet: Inlet[_] = this
}
/**
 * An output port of a StreamLayout.Module.
 * It is also used in the Java DSL for “untyped Outlets” as a work-around
 * for otherwise unreasonable existential types.
 */
abstract class OutPort { self: Outlet[_] ⇒
  final override def hashCode: Int = super.hashCode
  final override def equals(that: Any): Boolean = this eq that.asInstanceOf[AnyRef]

  /**
   * INTERNAL API
   */
  @volatile private[stream] var id: Int = -1

  /**
   * INTERNAL API
   */
  @volatile private[stream] var mappedTo: OutPort = this

  /**
   * INTERNAL API
   */
  private[stream] def outlet: Outlet[_] = this
}
