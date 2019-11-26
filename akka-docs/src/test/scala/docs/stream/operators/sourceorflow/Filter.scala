/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.sourceorflow

import akka.NotUsed
import akka.stream.scaladsl.Source

object Filter {
  def someText(): Source[String, NotUsed] =
    Source(
      ("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt " +
      "ut labore et dolore magna aliqua.").split(" ").toList)

  def filterExample(): Unit = {
    // #filter
    val words: Source[String, NotUsed] = someText()
    val longWords: Source[String, NotUsed] = words.filter(_.length > 6)
    // #filter
  }

  def filterNotExample(): Unit = {
    // #filterNot
    val words: Source[String, NotUsed] = someText()
    val longWords: Source[String, NotUsed] = words.filterNot(_.length <= 5)
    // #filterNot
  }
}
