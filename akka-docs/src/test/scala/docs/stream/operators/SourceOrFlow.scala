/**
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators

import akka.stream.scaladsl._

//

object SourceOrFlow {

  def logExample(): Unit = {
    //#log
    import akka.stream.Attributes

    //#log

    Flow[String]
      //#log
      .log(name = "myStream")
      .addAttributes(Attributes.logLevels(
        onElement = Attributes.LogLevels.Off,
        onFailure = Attributes.LogLevels.Error,
        onFinish = Attributes.LogLevels.Info))
    //#log
  }

  def conflateExample(): Unit = {
    //#conflate
    Source(List(1, 10, 100))
      .conflate((acc, el) ⇒ acc + el) // acc: Int, el: Int
    //#conflate
  }

  def conflateWithSeedExample(): Unit = {
    //#conflateWithSeed
    case class NewType(i: Int) {
      def sum(other: NewType) = NewType(this.i + other.i)
    }

    Source(List(1, 10, 100))
      .conflateWithSeed(el ⇒ NewType(el))((acc, el) ⇒ acc sum NewType(el)) // (NewType, Int) => NewType
    //#conflateWithSeed
  }

}
