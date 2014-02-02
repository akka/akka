/*
Copyright 2013 Crossing-Tech

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
   limitations under the License.
 */
package akka.sample.osgi.service

import akka.sample.osgi.api.DiningHakkersService
import akka.actor.{ Props, ActorSystem }
import akka.actor.ActorRef
import akka.sample.osgi.internal.Hakker
import akka.sample.osgi.internal.HakkerTracker

class DiningHakkersServiceImpl(system: ActorSystem) extends DiningHakkersService {
  def getHakker(name: String, chairNumber: Int): ActorRef =
    system.actorOf(Props(classOf[Hakker], name, chairNumber))

  def getTracker(): ActorRef =
    system.actorOf(Props[HakkerTracker], "tracker")
}
