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
		sbt.Keys.libraryDependencies ++= Seq(
			"io.gatling" % "gatling-bundle" % "2.0.0-M3" % "gatling",
			"com.redbrickhealth" %% "sbt-gatling-support" % "1.3" % "gatling"
		)
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

	def detectGatlingTests: sbt.Project.Initialize[sbt.Task[Seq[sbt.TestDefinition]]] = (sbt.Keys.streams, sbt.Keys.loadedTestFrameworks in GatlingTest, sbt.Keys.compile in GatlingTest, sbt.Keys.dependencyClasspath in GatlingTest) map { (streams, frameworkMap, analysis, libs) =>
		val fingerprint = new sbt.testing.SubclassFingerprint {
			def isModule(): Boolean = false
			def superclassName(): String = "com.excilys.ebi.gatling.core.scenario.configuration.Simulation"
			def requireNoArgConstructor() = true
		}
		val parentLoader = getClass().getClassLoader()
		val discoveredTests = sbt.Tests.discover(frameworkMap.values.toSeq, analysis, streams.log)._1.toList
		streams.log.debug("Discovered local tests are " + discoveredTests)
		val tests = List(
			discoveredTests,
			libs.flatMap { lib =>
				if (lib.data.getName().indexOf("-gatling-") > 0) {
					import java.util.zip.{ZipEntry, ZipFile, ZipInputStream}
					import scala.collection.JavaConversions._
					val source = lib.data.getAbsolutePath()
					streams.log.debug("checking %s for tests".format(source))
					val zip = new ZipFile(lib.data)
					val loader = new ClassLoader(parentLoader) {
						def checkForGatling(entry: ZipEntry): Option[String] = {
							try {
								val length = entry.getSize().asInstanceOf[Int]
								val buf = new Array[Byte](length)
								val stream = zip.getInputStream(entry)
								stream.read(buf, 0, length)
								val cl = defineClass(buf, 0, length)
								streams.log.debug("entry %s is not a gatling test".format(entry.getName()))
								None
							} catch {
								case e: NoClassDefFoundError if e.getMessage() == "com/excilys/ebi/gatling/core/scenario/configuration/Simulation" => {
									// We're using a classloader which doesn't have gatling in it, so 
									// we get this NoClassDefFoundError which indicates that this 
									// *is* a test.
									val name = entry.getName()
									val testName = name.substring(0, name.length()-6).replace("/", ".")
									streams.log.debug("found test " + testName)
									Some(testName)
								}
								case e: Throwable => {
									streams.log.debug("entry %s is not a gatling test: %s".format(entry.getName(), e))
									None
								}
							}
						}
					}
					zip.entries() flatMap { entry =>
						if (entry.getName().endsWith(".class")) {
							loader.checkForGatling(entry) map { name => 
								new sbt.TestDefinition(name, fingerprint, true, Array.empty) // AJK
							}
						} else {
							None
						}
					}
				} else {
					List.empty
				}
			}
		).flatten
		streams.log.debug("all discovered tests are " + tests)
		tests
	}
}


