package com.redbrickhealth.sbt.gatling

/**
  * @author akloss
  * @since 1.2
  */

import org.scalatools.testing._

import io.gatling.core.result.message.{GroupMessage, KO, RequestMessage, RunMessage, ScenarioMessage, Status}

class GatlingTest extends Framework {
	def name(): String = {
		"Gatling"
	}

	def tests(): Array[Fingerprint] = {
		Array(
			new SubclassFingerprint {
				def isModule(): Boolean = false
				val className = classOf[io.gatling.core.scenario.Simulation].getName()
				def superClassName(): String = className
			}
		)
	}

	def testRunner(cl: ClassLoader, loggers: Array[Logger]): Runner = {
		import io.gatling.core.config.GatlingConfiguration
		GatlingConfiguration.setUp(collection.mutable.Map.empty)
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
	var simulationHandlers: Map[String,EventHandler] = Map.empty
	
	def clear(runMessage: RunMessage) {
		this.synchronized {
			simulationHandlers = simulationHandlers - runMessage.simulationId
		}
	}

	def finish(data: GatlingDataWriter) {
		import java.io.File
		val handler = simulationHandlers(data.runMessage.simulationId) 
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
		val suiteName = data.runMessage.runDescription
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
			val testTime: Double = stats.duration / stats.count.toDouble / 1000
			val failureText = if (stats.failed > 0) {
				"""<failure message="KO">%d/%d requests failed</failure>""".format(stats.failed, stats.count)
			} else {
				""
			}
			xml.append(testcasePattern.format(name, testTime, suiteName, failureText))
		}
		xml.append(output).append(xmlFooter)
		sbt.IO.createDirectory(new File("target/gatling-reports/xml"))
		sbt.IO.write(new File("target/gatling-reports/xml/%s.xml".format(suiteName)), xml.toString())
		clear(data.runMessage)
	}
}

class GatlingRunner(cl: ClassLoader, loggers: Array[Logger]) 
extends Runner2 
with io.gatling.core.action.AkkaDefaults {
	import io.gatling.core.action.system
	import io.gatling.core.action.system.dispatcher

	val singleTestOutput = true
	val summaryScript = """
		 var totalSimulations = 0
		 var totalTestCases = 0
		 var failedTestCases = 0
		 for (var i = 0; i < testReports.length; i++) {
		   totalSimulations += 1
		    new function() {
			 	var reportName = testReports[i]
			  var table = document.getElementById('test_results')
			  var rowHTML = '<th style="text-align:left;"><a href="' + reportName + '/index.html">' + reportName + '</a></th>'
			  var row = document.createElement('tr')
			  row.innerHTML = rowHTML
			  table.appendChild(row)
			  var req = new XMLHttpRequest()
			  req.onreadystatechange = function() {
				if (req.readyState == 4) {
					var td1 = document.createElement('td')
					var td2 = document.createElement('td')
					var td3 = document.createElement('td')
					var cases = null
					try {
						cases = this.responseXML.getElementsByTagName("testcase")
					} catch(e) {
						td1.innerHTML = '<span style="font-weight:bold;">Unable to load results</span>'
						td1.setAttribute('colspan', '3')
						td1.setAttribute('style', 'text-align:center')
						row.appendChild(td1)
						return
					}
					var total = cases.length
					var failed = 0
					var duration = 0
					for (var j = 0; j < total; j++) {
						var testCase = cases.item(j)
						var failure = testCase.getElementsByTagName("failure")
						if (failure.length > 0) failed += 1
						var durationValue = testCase.getAttribute('time')
						duration += Number(durationValue)
					}
					totalTestCases += total
					document.getElementById('total_testcases').innerHTML = totalTestCases
					failedTestCases += failed
					document.getElementById('total_failed_testcases').innerHTML = failedTestCases
					td1.innerHTML = total + ' tests'
					td2.innerHTML = failed + ' failed'
					var avg = Math.round(duration * 1000 / total)/1000 + ' s avg'
					td3.innerHTML = ''+avg + 's'
					row.appendChild(td1)
					row.appendChild(td2)
					row.appendChild(td3)
				   }
				 }
				   req.open('GET', 'xml/' + reportName + '.xml', true)
				   req.send()
					} ()
				}
		 document.getElementById('test_title').innerHTML = 'Gatling Test Results'
		 document.getElementById('total_simulations').innerHTML = ''+totalSimulations
		 """.stripMargin

	def updateIndex() {
		import java.io.File
		import scala.collection.JavaConversions._
		val dirName = "target/gatling-reports/xml"
		val dir = new File(dirName)
		if (dir.isDirectory()) {
			val buffer = new StringBuilder()
			buffer.append("""<html>
			|<head>
			| <style>
			|  body {
			|   font-family: helvetica neue, arial, sans-serif;
			|  }
			|  th {
			|   text-align:right;
			|   padding-right: .2em;
			|  }
			|  td {
			|   text-align:right;
			|   padding: .1ex .5em 0ex .5em;
			|  }
			| </style>
			| <title>Gatling Test Overview</title>
			| <script src="index.js"></script>
			| <script>
			|  var onload = function() {
			|  %s
			|  }
			| </script>
			|</head>
			|<body onload="onload()">
			| <div style="font-size:125%%;font-weight:bold;margin-bottom:1ex;" id="test_title"></div>
			| <table>
			|  <tr><th>Simulations</th><td id="total_simulations"></td></tr>
			|  <tr><th>Test Cases</th><td id="total_testcases"></td></tr>
			|  <tr><th>Failed Test Cases</th><td id="total_failed_testcases"></td></tr>
			| </table>
			| <table><tbody id="test_results">
			|  <tr><th style="text-align:center;">Test Name</th><th style="text-align:center;" colspan="4">Status</th></tr>
			| </tbody></table>
			|</body>
			|</html>
			|""".stripMargin.format(summaryScript))
			sbt.IO.write(new File("target/gatling-reports/index.html"), buffer.toString())
			val jsBuffer = new StringBuffer()
			jsBuffer.append("var testReports = [")
			dir.listFiles().foldLeft("")( { (prefix: String, file: File) =>
				if (file.isFile()) {
					val relativeName = file.getPath().substring(dirName.length+1)
					jsBuffer.append("%s\n   '%s'".format(prefix, 
						relativeName.substring(0, relativeName.length-4)))
					","
				} else {
					prefix
				}
			})
			jsBuffer.append("\n]\n")
			sbt.IO.write(new File("target/gatling-reports/index.js"), jsBuffer.toString())
		} else {
			println("[WARN] %s is not a directory".format(dirName))
		}
	}

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
		import io.gatling.core.scenario.Simulation
		val selection = io.gatling.core.runner.Selection(
			cl.loadClass(testClassName).asInstanceOf[Class[Simulation]],
			testId, testName
		)
		
		import java.util.concurrent.CountDownLatch
		import java.util.concurrent.TimeUnit.SECONDS
		import scala.concurrent.Await
		import io.gatling.core.config.GatlingConfiguration.configuration
		import io.gatling.core.result.terminator.Terminator
		import io.gatling.core.result.writer.DataWriter
		import org.joda.time.DateTime.now
		// Borrowed from io.gatling.core.runner.Runner.scala
		// to avoid system.shutdown.
		val simulationClass = selection.simulationClass
		println("[info] Simulation " + simulationClass.getName + " started...")

		val runMessage = RunMessage(now, selection.simulationId, selection.description)

		val simulation = try { simulationClass.newInstance }
		catch {
			case e => {
				println("Unable to initialize test %s".format(testName))
				e.printStackTrace()
				val handler = GatlingTest.simulationHandlers(runMessage.simulationId) 
				handler.handle(new org.scalatools.testing.Event {
					def testName() = testName
					def description() = testName
					def result(): Result = Result.Failure
					def error(): Throwable = e
				})
				val suiteName = runMessage.runDescription
				sbt.IO.createDirectory(new java.io.File("target/gatling-reports/" + suiteName))
				val html = """
				Test failed to start: %s
				""".format(e.getMessage())
				sbt.IO.write(new java.io.File("target/gatling-reports/%s/index.html".format(suiteName)), html.toString())
				sbt.IO.createDirectory(new java.io.File("target/gatling-reports/xml"))
				sbt.IO.write(new java.io.File("target/gatling-reports/xml/%s.xml".format(suiteName)), "")
				updateIndex()
				return
			}
		}
		val scenarios = simulation.scenarios

		try {
		require(!scenarios.isEmpty, simulationClass.getName + " returned an empty scenario list. Did you forget to migrate your Simulations?")
		val scenarioNames = scenarios.map(_.name)
		require(scenarioNames.toSet.size == scenarioNames.size, "Scenario names must be unique but found " + scenarioNames)

		val totalNumberOfUsers = scenarios.map(_.injectionProfile.users).sum
		// info("Total number of users : " + totalNumberOfUsers)

		val terminatorLatch = new CountDownLatch(1)
		val init = Terminator
			.askInit(terminatorLatch, totalNumberOfUsers)
			.flatMap { _: Any => DataWriter.askInit(runMessage, scenarios) }

		Await.result(init, defaultTimeOut.duration)

		// debug("Launching All Scenarios")

		scenarios.foldLeft(0) { (i, scenario) =>
			scenario.run(i)
			i + scenario.injectionProfile.users
		}
		// debug("Finished Launching scenarios executions")

		terminatorLatch.await(configuration.core.timeOut.simulation, SECONDS)
		} catch {
			case e => {
				println("[error] %s: %s".format(testName, e.getMessage))
			}
		}
		println("[info] Simulation finished.")

		// And now we generate the HTML reports
		val dataReader = io.gatling.core.result.reader.DataReader.newInstance(runMessage.runId)
		val indexFile = io.gatling.charts.report.ReportsGenerator.generateFor(
			testId,
			dataReader
		)

		updateIndex()
	}
}

class RequestStats {
	var count: Long = 0
	var duration: Long = 1
	var failed: Long = 0
	def update(record: RequestMessage) {
		this.synchronized {
			count = count + 1
			duration = duration + record.requestEndDate - record.requestStartDate
			if (record.status == KO) failed = failed + 1
		}
	}
}

class GatlingDataWriter extends io.gatling.core.result.writer.DataWriter {
	import io.gatling.core.result.message.{ShortScenarioDescription}
	var runMessage: RunMessage = null
	var failed: Boolean = false
	var requestStats: Map[String,RequestStats] = Map.empty

	def onFlushDataWriter() {
		GatlingTest.finish(this)
		runMessage = null
		failed = false
		requestStats = Map.empty
	}
	def onInitializeDataWriter(runMessage: RunMessage, scenarios: Seq[ShortScenarioDescription]) {
		this.runMessage = runMessage
	}
	def onRequestMessage(requestMessage: RequestMessage) {
		if (requestMessage.status == KO) failed = true
		val stats = requestStats.get(requestMessage.name) getOrElse {
			this.synchronized {
				requestStats.get(requestMessage.name) getOrElse {
					val r = requestMessage.name -> new RequestStats()
					requestStats = requestStats + r
					r._2
				}
			}
		}
		stats.update(requestMessage)
	}

	def onGroupMessage(groupMessage: GroupMessage) {
	}

	def onScenarioMessage(scenarioMessage: ScenarioMessage) {
	}
}
