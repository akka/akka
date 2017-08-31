//import akka.{ AkkaBuild, Formatting, OSGi, Unidoc, Dependencies } // FIXME
import akka.{ AkkaBuild, Formatting, OSGi, Dependencies }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.protobuf

//enablePlugins(ScaladocNoVerificationOfDiagrams) // FIXME
disablePlugins(MimaPlugin)
