package com.redbrickhealth.sbt.gatling

/**
  * @author akloss
  * @since 1.2
  */

object Plugin extends sbt.impl.DependencyBuilders {
	lazy val GatlingTest = sbt.config("gatling") extend(sbt.Test)
	lazy val gatlingSettings: Seq[sbt.Project.Setting[_]] = Seq(sbt.inConfig(GatlingTest)(sbt.Defaults.testSettings) : _*)
	lazy val settings = gatlingSettings ++ Seq(
		sbt.Keys.libraryDependencies += "com.excilys.ebi.gatling.highcharts" % "gatling-charts-highcharts" % "1.5.1" % "test",
		sbt.Keys.libraryDependencies += ("com.redbrickhealth" %% "sbt-gatling-support" % "0.1-SNAPSHOT" % "test" changing),
		sbt.Keys.sourceDirectory in GatlingTest <<= sbt.Keys.sourceDirectory { source =>
			import sbt.Path._
			source / "test/gatling"
		},
		sbt.Keys.parallelExecution in GatlingTest := false,
		sbt.Keys.managedClasspath in GatlingTest <<= sbt.Keys.managedClasspath in sbt.Test,
		sbt.Keys.testFrameworks in GatlingTest := Seq(new sbt.TestFramework("com.redbrickhealth.sbt.gatling.GatlingTest"))
	) 
}

