/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.event.slf4j

import akka.event.Logging.Warning

@deprecated("use akka.event.slf4j.Slf4jLogger)", "2.2")
class Slf4jEventHandler extends Slf4jLogger {

  self ! Warning(getClass.getName, getClass,
    s"[${getClass.getName}] is deprecated, use [${classOf[Slf4jLogger].getName}] instead")

}