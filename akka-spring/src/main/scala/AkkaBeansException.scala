package se.scalablesolutions.akka.spring

import org.springframework.beans.BeansException

/**
* Exception to use when something goes wrong during bean creation
@author <a href="johan.rask@jayway.com">Johan Rask</a>
*/
class AkkaBeansException(errorMsg:String,t:Throwable) extends BeansException(errorMsg,t) {

        def this(errorMsg:String) = {
           this(errorMsg,null)
        }
}
