package com.redbrickhealth.sbt.gatling

/**
  * @author akloss
  * @since 1.2
  */

import org.scalatools.testing._
class GatlingTest extends Framework {
	def name(): String = {
		"Gatling"
	}

	def tests(): Array[Fingerprint] = {
		Array(
			new SubclassFingerprint {
				def isModule(): Boolean = false
				def superClassName(): String = "com.excilys.ebi.gatling.core.scenario.configuration.Simulation"
			}
		)
	}

	def testRunner(cl: ClassLoader, loggers: Array[Logger]): Runner = {
		import com.excilys.ebi.gatling.core.config.GatlingConfiguration
		GatlingConfiguration.setUp(new java.util.HashMap())
		val sourceDir = new java.io.File("src/test/gatling").getAbsolutePath()
		val targetDir = new java.io.File("target/gatling-reports").getAbsolutePath()
		GatlingConfiguration.configuration = GatlingConfiguration.configuration.copy(
			core = GatlingConfiguration.configuration.core.copy(
				outputDirectoryBaseName = Some(targetDir),
				directory = GatlingConfiguration.configuration.core.directory.copy(
					data = targetDir + "/data",
					requestBodies = targetDir + "/requests",
					sources = sourceDir + "/scala",
					results = targetDir
				)
			),
			data = GatlingConfiguration.configuration.data.copy(
				dataWriterClasses = GatlingConfiguration.configuration.data.dataWriterClasses ++ Seq("com.redbrickhealth.sbt.gatling.GatlingDataWriter")
			)
		)
		return new GatlingRunner(cl, loggers)
	}
}

object GatlingTest {
	import com.excilys.ebi.gatling.core.result.message.RequestStatus
	import com.excilys.ebi.gatling.core.result.message.RunRecord
	var simulationHandlers: Map[String,EventHandler] = Map.empty
	
	def clear(runRecord: RunRecord) {
		this.synchronized {
			simulationHandlers = simulationHandlers - runRecord.simulationId
		}
	}

	def finish(data: GatlingDataWriter) {
		import java.io.File
		val handler = simulationHandlers(data.runRecord.simulationId) 
		val failures = data.requestStats.map ({ case (name, stats) =>
			if (stats.failed > 0) 1 else 0
		}).foldLeft(0) { (count, state) => count + state }
		if (failures > 0) {
			handler.handle(new org.scalatools.testing.Event {
				def testName() = testName
				def description() = testName
				def result(): Result = Result.Error
				def error(): Throwable = null
			})
		} else {
			handler.handle(new org.scalatools.testing.Event {
				def testName() = testName
				def description() = testName
				def result(): Result = Result.Success
				def error(): Throwable = null
			})
		}
		val suiteName = data.runRecord.runDescription
		val hostname = "unknown"
		val time: Double = 1.0
		val errors = 0
		val xmlHeader = """<testsuite tests="1" skipped="0" name="%s" hostname="%s" failures="%d" time="%f" errors="%d">""".format(suiteName, hostname, failures, time, errors)
		val properties = """<properties></properties>"""
		val testcasePattern = """<testcase name="%s" time="%f" classname="%s">%s</testcase>"""
		val output = """<system-out></system-out> <system-err></system-err> """
		val xmlFooter = "</testsuite>"
		val xml = new StringBuilder()
		xml.append(xmlHeader).append(properties)
		data.requestStats map { case (name, stats) =>
			val testTime: Double = stats.duration / stats.count.toDouble 
			val failureText = if (stats.failed > 0) {
				"""<failure message="KO">%d/%d requests failed</failure>""".format(stats.failed, stats.requests)
			} else {
				""
			}
			xml.append(testcasePattern.format(name, testTime, suiteName, failureText))
		}
		xml.append(output).append(xmlFooter)
		sbt.IO.createDirectory(new File("target/gatling-reports/xml"))
		sbt.IO.write(new File("target/gatling-reports/xml/%s.xml".format(suiteName)), xml.toString())
		clear(data.runRecord)
	}
}

class GatlingRunner(cl: ClassLoader, loggers: Array[Logger]) 
extends Runner2 
with com.excilys.ebi.gatling.core.action.AkkaDefaults {
	val singleTestOutput = true
	def run(testClassName: String, fingerprint: Fingerprint, eventHandler: EventHandler, args: Array[String]) {
		val testName = testClassName
		val testId = if (singleTestOutput) {
			"%s".format(testClassName)
		} else {
			"%s-%d".format(testClassName, System.currentTimeMillis())
		}
		GatlingTest.synchronized {
			GatlingTest.simulationHandlers = GatlingTest.simulationHandlers + (testId -> eventHandler)
		}
		import com.excilys.ebi.gatling.core.scenario.configuration.Simulation
		val selection = com.excilys.ebi.gatling.core.runner.Selection(
			cl.loadClass(testClassName).asInstanceOf[Class[Simulation]],
			testId, testName
		)
		
		import java.util.concurrent.CountDownLatch
		import java.util.concurrent.TimeUnit.SECONDS
		import akka.dispatch.Await
		import com.excilys.ebi.gatling.core.config.GatlingConfiguration.configuration
		import com.excilys.ebi.gatling.core.result.message.RunRecord
		import com.excilys.ebi.gatling.core.result.terminator.Terminator
		import com.excilys.ebi.gatling.core.result.writer.DataWriter
		import org.joda.time.DateTime.now
		// Borrowed from com.excilys.ebi.gatling.core.runner.Runner.scala
		// to avoid system.shutdown.
		val simulationClass = selection.simulationClass
		println("Simulation " + simulationClass.getName + " started...")

		val runRecord = RunRecord(now, selection.simulationId, selection.description)

		val simulation = try { simulationClass.newInstance }
		catch {
			case e => {
				val handler = GatlingTest.simulationHandlers(runRecord.simulationId) 
				handler.handle(new org.scalatools.testing.Event {
					def testName() = testName
					def description() = testName
					def result(): Result = Result.Failure
					def error(): Throwable = null
				})
				return
			}
		}
		val scenarios = simulation.scenarios

		require(!scenarios.isEmpty, simulationClass.getName + " returned an empty scenario list. Did you forget to migrate your Simulations?")
		val scenarioNames = scenarios.map(_.name)
		require(scenarioNames.toSet.size == scenarioNames.size, "Scenario names must be unique but found " + scenarioNames)

		val totalNumberOfUsers = scenarios.map(_.configuration.users).sum
		// info("Total number of users : " + totalNumberOfUsers)

		val terminatorLatch = new CountDownLatch(1)
		val init = Terminator
			.askInit(terminatorLatch, totalNumberOfUsers)
			.flatMap { _: Any => DataWriter.askInit(runRecord, scenarios) }

		Await.result(init, defaultTimeOut.duration)

		// debug("Launching All Scenarios")

		scenarios.foldLeft(0) { (i, scenario) =>
			scenario.run(i)
			i + scenario.configuration.users
		}
		// debug("Finished Launching scenarios executions")

		terminatorLatch.await(configuration.core.timeOut.simulation, SECONDS)
		println("Simulation finished.")

		// And now we generate the HTML reports
		val dataReader = com.excilys.ebi.gatling.core.result.reader.DataReader.newInstance(runRecord.runId)
		val indexFile = com.excilys.ebi.gatling.charts.report.ReportsGenerator.generateFor(
			testId,
			dataReader
		)
	}
}

class RequestStats {
	var count: Long = 0
	var duration: Long = 1
	var failed: Long = 0
	var requests: Long = 0
	import com.excilys.ebi.gatling.core.result.message.{RequestRecord, RequestStatus}
	def update(record: RequestRecord) {
		this.synchronized {
			count = count + 1
			duration = duration + record.executionEndDate - record.executionStartDate
			if (record.requestStatus == RequestStatus.KO) failed = failed + 1
		}
	}
}

class GatlingDataWriter extends com.excilys.ebi.gatling.core.result.writer.DataWriter {
	import com.excilys.ebi.gatling.core.result.message.{GroupRecord, RequestRecord, RequestStatus, RunRecord, ScenarioRecord, ShortScenarioDescription}
	var runRecord: RunRecord = null
	var failed: Boolean = false
	var requestStats: Map[String,RequestStats] = Map.empty

	def onFlushDataWriter {
		GatlingTest.finish(this)
		runRecord = null
		failed = false
		requestStats = Map.empty
	}
	def onInitializeDataWriter(runRecord: RunRecord, scenarios: Seq[ShortScenarioDescription]) {
		this.runRecord = runRecord
	}
	def onRequestRecord(requestRecord: RequestRecord) {
		if (requestRecord.requestStatus == RequestStatus.KO) failed = true
		val stats = requestStats.get(requestRecord.requestName) getOrElse {
			this.synchronized {
				requestStats.get(requestRecord.requestName) getOrElse {
					val r = requestRecord.requestName -> new RequestStats()
					requestStats = requestStats + r
					r._2
				}
			}
		}
		stats.update(requestRecord)
	}
	def onGroupRecord(groupRecord: GroupRecord) {
	}
	def onScenarioRecord(scenarioRecord: ScenarioRecord) {
	}
}
