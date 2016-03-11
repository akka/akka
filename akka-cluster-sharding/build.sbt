import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.clusterSharding
Dependencies.clusterSharding

enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)
