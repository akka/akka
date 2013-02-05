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
package akka.osgi.sample.activation

import akka.osgi.ActorSystemActivator
import akka.actor.{Props, ActorSystem}
import akka.osgi.sample.internal.Table
import akka.osgi.sample.service.DiningHakkersServiceImpl
import akka.osgi.sample.api.DiningHakkersService
import akka.event.{LogSource, Logging}
import org.osgi.framework.{ServiceRegistration, BundleContext}
import scala.collection.mutable.ListBuffer

class Activator extends ActorSystemActivator {

  import Activator._

  val services: ListBuffer[ServiceRegistration[_]] = ListBuffer()

  def configure(context: BundleContext, system: ActorSystem) {
    val log = Logging(system, this)
    log.info("Core bundle configured")
    system.actorOf(Props[Table], "table")
    registerHakkersService(context, system)
    log.info("Hakker service registred")
  }

  def registerHakkersService(context: BundleContext, system: ActorSystem) {

    val hakkersService = new DiningHakkersServiceImpl(system)

    services += context.registerService(classOf[DiningHakkersService], hakkersService, null)
    services += context.registerService(classOf[ActorSystem], system, null)

  }

  override def stop(context: BundleContext) {
    unregisterServices(context)
    println("Hakker service unregistred")
    super.stop(context)
  }

  def unregisterServices(context: BundleContext) {
    services foreach (_.unregister())
  }

  override def getActorSystemName(context: BundleContext): String = "akka-osgi-sample"
}

object Activator {
  implicit val logSource: LogSource[AnyRef] = new LogSource[AnyRef] {
    def genString(o: AnyRef): String = o.getClass.getName
    override def getClazz(o: AnyRef): Class[_] = o.getClass
  }
}
