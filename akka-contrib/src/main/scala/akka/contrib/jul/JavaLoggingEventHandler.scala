/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.jul

import akka.event.Logging.Warning

@deprecated("use akka.contrib.jul.JavaLogger)", "2.2")
class JavaLoggingEventHandler extends JavaLogger {

  self ! Warning(getClass.getName, getClass,
    s"[${getClass.getName}] is deprecated, use [${classOf[JavaLogger].getName}] instead")

}