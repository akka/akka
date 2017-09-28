import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.distributedData
Dependencies.distributedData

enablePlugins(akka.Unidoc, MultiNodeScalaTest)
