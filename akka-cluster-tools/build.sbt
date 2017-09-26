//import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi }
import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, OSGi, Protobuf } // FIXME

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.clusterTools
Dependencies.clusterTools
Protobuf.settings

//enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams) // FIXME
enablePlugins(MultiNode)
