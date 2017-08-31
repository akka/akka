//import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi }
import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, OSGi } // FIXME

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.clusterTools
Dependencies.clusterTools

//enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams) // FIXME
enablePlugins(MultiNode)
