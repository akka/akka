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

}
