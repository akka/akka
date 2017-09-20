import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, Unidoc, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.distributedData
Dependencies.distributedData

enablePlugins(MultiNodeScalaTest)
