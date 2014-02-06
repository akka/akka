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
package akka.sample.osgi.command

import org.osgi.framework.{ ServiceEvent, BundleContext, BundleActivator }
import akka.sample.osgi.api.DiningHakkersService
import akka.actor.{ ActorRef, PoisonPill }
import org.osgi.util.tracker.ServiceTracker

class Activator extends BundleActivator {
  println("Command Activator created")
  var hakker: Option[ActorRef] = None

  def start(context: BundleContext) {
    val logServiceTracker = new ServiceTracker(context, classOf[DiningHakkersService].getName, null)
    logServiceTracker.open()
    val service = Option(logServiceTracker.getService.asInstanceOf[DiningHakkersService])
    service.foreach(startHakker(_, context.getBundle.getSymbolicName + ":" + context.getBundle.getBundleId))
  }

  def startHakker(service: DiningHakkersService, name: String) {
    hakker = Some(service.getHakker(name, 4))
  }

  def stop(context: BundleContext) {
    hakker.foreach(_ ! PoisonPill)
  }
}
