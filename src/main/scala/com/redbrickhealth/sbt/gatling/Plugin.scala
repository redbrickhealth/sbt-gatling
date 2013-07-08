package com.redbrickhealth.sbt.gatling

/**
  * @author akloss
  * @since 1.2
  */

object Plugin extends sbt.impl.DependencyBuilders {
	def buildArtifact(artifactNameSuffix: String = "") = {
		sbt.addArtifact(
			sbt.Keys.moduleName(
				n => new sbt.Artifact(n + artifactNameSuffix, "jar", "jar", Some("gatling"), Set(GatlingTest), None, Map.empty)
			),
			artifactTask in GatlingTest
		) ++ 
		(artifactTask in GatlingTest <<= artifactTask in GatlingTest dependsOn(sbt.Keys.compile in Plugin.GatlingTest))
	}
	val artifactTask = sbt.TaskKey[java.io.File]("gatling-artifact")
	lazy val GatlingTest = sbt.config("gatling") extend(sbt.Test)
	lazy val settings: Seq[sbt.Project.Setting[_]] = Seq(
		sbt.Keys.ivyConfigurations += GatlingTest,
		sbt.Keys.libraryDependencies += "com.excilys.ebi.gatling.highcharts" % "gatling-charts-highcharts" % "1.5.1" % "gatling",
		sbt.Keys.libraryDependencies += ("com.redbrickhealth" %% "sbt-gatling-support" % "1.2" % "gatling" changing)
	) ++ sbt.inConfig(GatlingTest)(gatlingSettings)
	lazy val gatlingSettings = sbt.Defaults.testSettings ++ Seq(
		sbt.Keys.sourceDirectory in GatlingTest <<= (sbt.Keys.sourceDirectory in sbt.Configurations.Default) { source =>
			import sbt.Path._
			source / "test/gatling"
		},
		sbt.Keys.parallelExecution in GatlingTest := false,
		sbt.Keys.testFrameworks in GatlingTest := Seq(new sbt.TestFramework("com.redbrickhealth.sbt.gatling.GatlingTest")),
		sbt.Keys.definedTests in GatlingTest <<= detectGatlingTests,
		artifactTask in GatlingTest <<= (sbt.Keys.crossTarget in sbt.Compile, sbt.Keys.classDirectory in GatlingTest, sbt.Keys.resourceDirectory in GatlingTest) map { (targetDir, classesDir, resourcesDir) =>
			import java.io.File
			import sbt.Path._
			def normalize(dir: File, file: File): Option[Tuple2[File,String ]]= {
				if (file.isDirectory()) return None
				val filePath = file.getPath()
				val index: Int = filePath.lastIndexOf(dir.getPath())
				Some(file -> filePath.substring(index + dir.getPath().length + 1))
			}
			val sources = (classesDir ** sbt.GlobFilter("*.class") get).flatMap(normalize(classesDir, _)) ++
				(resourcesDir ** sbt.GlobFilter("*") get).flatMap(normalize(resourcesDir, _))

			val outputJar = new java.io.File(targetDir, "gatling.jar")
			sbt.IO.zip(sources, outputJar)
			outputJar
		}
	) 

	def detectGatlingTests: sbt.Project.Initialize[sbt.Task[Seq[sbt.TestDefinition]]] = (sbt.Keys.streams, sbt.Keys.loadedTestFrameworks in GatlingTest, sbt.Keys.compile in GatlingTest) map { (streams, frameworkMap, analysis) =>
		val fingerprint = new org.scalatools.testing.SubclassFingerprint {
			def isModule(): Boolean = false
			def superClassName(): String = "com.excilys.ebi.gatling.core.scenario.configuration.Simulation"
		}
		sbt.Tests.discover(frameworkMap.values.toSeq, analysis, streams.log)._1.toList
	}
}


