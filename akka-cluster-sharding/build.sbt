// import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi } // FIXME
import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, OSGi, Protobuf }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.clusterSharding
Dependencies.clusterSharding
Protobuf.settings

//enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams) // FIXME
enablePlugins(MultiNode)
