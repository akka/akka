package se.scalablesolutions.akka.spring

/**
* Represents a property element
* @author <a href="johan.rask@jayway.com">Johan Rask</a>
*/
class PropertyEntry {

        var name:String = _
        var value:String = null
        var ref:String = null


        override def  toString(): String = {
                format("name = %s,value =  %s, ref = %s", name,value,ref)
        }
}
