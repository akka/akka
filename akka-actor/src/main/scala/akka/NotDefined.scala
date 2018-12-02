/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import akka.annotation.DoNotInherit

@DoNotInherit
sealed abstract class NotDefined private[akka] () extends Serializable

final case object NotDefined extends NotDefined {
  /**
   * Java API
   */
  final def getInstance(): NotDefined = this

  final def notDefined(): NotDefined = this
}
