// import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi } // FIXME
import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.clusterSharding
Dependencies.clusterSharding

//enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams) // FIXME
enablePlugins(MultiNode)
