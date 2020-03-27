/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import akka.actor.ActorSystem
import com.github.ghik.silencer.silent

@silent // 'early initializers' are deprecated on 2.13 and will be replaced with trait parameters on 2.14. https://github.com/akka/akka/issues/26753
abstract class ScalaVersionSpecificTestkit(_system: ActorSystem) extends { implicit val system: ActorSystem = _system }
with TestKitBase
