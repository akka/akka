import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, ScaladocNoVerificationOfDiagrams }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.agent
Dependencies.agent

enablePlugins(ScaladocNoVerificationOfDiagrams)
