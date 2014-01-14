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
package akka.sample.osgi.activation

import akka.osgi.ActorSystemActivator
import akka.actor.{ Props, ActorSystem }
import akka.sample.osgi.internal.Table
import akka.sample.osgi.service.DiningHakkersServiceImpl
import akka.sample.osgi.api.DiningHakkersService
import akka.event.{ LogSource, Logging }
import org.osgi.framework.{ ServiceRegistration, BundleContext }

class Activator extends ActorSystemActivator {

  import Activator._

  var diningHakkerService: Option[ServiceRegistration[_]] = None

  def configure(context: BundleContext, system: ActorSystem) {
    val log = Logging(system, this)
    log.info("Core bundle configured")
    system.actorOf(Props[Table], "table")
    registerService(context, system)
    registerHakkersService(context, system)
    log.info("Hakker service registered")
  }

  /**
   * registers the DinningHakkerService as a Service to be tracked and find by other OSGi bundles.
   * in other words, this instance may be used in other bundles which listen or track the OSGi Service
   * @param context  OSGi BundleContext
   * @param system   ActorSystem
   */
  def registerHakkersService(context: BundleContext, system: ActorSystem) {

    val hakkersService = new DiningHakkersServiceImpl(system)

    diningHakkerService = Some(context.registerService(classOf[DiningHakkersService].getName(), hakkersService, null))

  }

  override def stop(context: BundleContext) {
    unregisterServices(context)
    println("Hakker service unregistred")
    super.stop(context)
  }

  def unregisterServices(context: BundleContext) {
    diningHakkerService foreach (_.unregister())
  }

  override def getActorSystemName(context: BundleContext): String = "akka-osgi-sample"
}

object Activator {
  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
}
