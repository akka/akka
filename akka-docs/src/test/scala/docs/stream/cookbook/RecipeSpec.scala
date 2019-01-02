/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.cookbook

import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec

trait RecipeSpec extends AkkaSpec {

  implicit val m = ActorMaterializer()
  type Message = String

}
