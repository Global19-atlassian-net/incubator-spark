/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.deploy.worker

import java.io._

import akka.actor.ActorRef

import com.google.common.base.Charsets
import com.google.common.io.Files

import org.apache.spark.Logging
import org.apache.spark.deploy.{ExecutorState, ApplicationDescription, Command}
import org.apache.spark.deploy.DeployMessages.ExecutorStateChanged

/**
 * Manages the execution of one executor process.
 */
private[spark] class ExecutorRunner(
    val appId: String,
    val execId: Int,
    val appDesc: ApplicationDescription,
    val cores: Int,
    val memory: Int,
    val worker: ActorRef,
    val workerId: String,
    val host: String,
    val sparkHome: File,
    val workDir: File,
    val workerUrl: String,
    var state: ExecutorState.Value)
  extends Logging {

  val fullId = appId + "/" + execId
  var workerThread: Thread = null
  var process: Process = null

  // NOTE: This is now redundant with the automated shut-down enforced by the Executor. It might
  // make sense to remove this in the future.
  var shutdownHook: Thread = null

  def start() {
    workerThread = new Thread("ExecutorRunner for " + fullId) {
      override def run() { fetchAndRunExecutor() }
    }
    workerThread.start()

    // Shutdown hook that kills actors on shutdown.
    shutdownHook = new Thread() {
      override def run() {
        if (process != null) {
          logInfo("Shutdown hook killing child process.")
          killProcess()
        }
      }
    }
    Runtime.getRuntime.addShutdownHook(shutdownHook)
  }
  
  private def killProcess() {
	  if (process != null) {
		  logInfo("Killing process! fullId:" + fullId)
		  process.destroy()
		  val exitCode = process.waitFor()
		  logInfo("Killed process! fullId:" + fullId + " exitCode:" + exitCode)
		  process = null
	  }
  }

  /** Stop this executor runner, including killing the process it launched */
  def kill() {
    if (workerThread != null) {
      workerThread.interrupt()
      workerThread = null
      state = ExecutorState.KILLED
      worker ! ExecutorStateChanged(appId, execId, state, None, None)
      Runtime.getRuntime.removeShutdownHook(shutdownHook)
    }
  }

  /** Replace variables such as {{EXECUTOR_ID}} and {{CORES}} in a command argument passed to us */
  def substituteVariables(argument: String): String = argument match {
    case "{{WORKER_URL}}" => workerUrl
    case "{{EXECUTOR_ID}}" => execId.toString
    case "{{HOSTNAME}}" => host
    case "{{CORES}}" => cores.toString
    case other => other
  }

  def getCommandSeq = {
    val user = appDesc.user
    val ldLibEnv:String = "LD_LIBRARY_PATH=" + appDesc.command.environment.getOrElse("LD_LIBRARY_PATH", "")
    val sudo = if (System.getProperty("os.name").startsWith("Windows"))
    	Seq("runas","/profile","/env","/user:" + user) 
      else 
        Seq("sudo","-E","-u",user, ldLibEnv)
    
    val command = Command(appDesc.command.mainClass,
      appDesc.command.arguments.map(substituteVariables) ++ Seq(appId), appDesc.command.environment)
    sudo ++ CommandUtils.buildCommandSeq(command, memory, sparkHome.getAbsolutePath)
  }

  /**
   * Download and run the executor described in our ApplicationDescription
   */
  def fetchAndRunExecutor() {
    try {
      // Create the executor's working directory
      val executorDir = new File(workDir, appId + "/" + execId)
      if (!executorDir.mkdirs()) {
        throw new IOException("Failed to create directory " + executorDir)
      }
      
      if (System.getProperty("os.name").equalsIgnoreCase("Linux")){
    	  val appDir = new File(workDir, appId)
    	  Runtime.getRuntime().exec("sudo chown " + appDesc.user + " -R "+ appDir.getCanonicalPath())
      }

      // Launch the process
      val command = getCommandSeq
      logInfo("Launch command: " + command.mkString("\"", "\" \"", "\""))
      val builder = new ProcessBuilder(command: _*).directory(executorDir)
      val env = builder.environment()
      for ((key, value) <- appDesc.command.environment) {
        env.put(key, value)
      }
      // In case we are running this from within the Spark Shell, avoid creating a "scala"
      // parent process for the executor command
      env.put("SPARK_LAUNCH_WITH_SCALA", "0")
      process = builder.start()

      val header = "Spark Executor Command: %s\n%s\n\n".format(
        command.mkString("\"", "\" \"", "\""), "=" * 40)

      // Redirect its stdout and stderr to files
      val stdout = new File(executorDir, "stdout")
      CommandUtils.redirectStream(process.getInputStream, stdout)

      val stderr = new File(executorDir, "stderr")
      Files.write(header, stderr, Charsets.UTF_8)
      CommandUtils.redirectStream(process.getErrorStream, stderr)

      // Wait for it to exit; this is actually a bad thing if it happens, because we expect to run
      // long-lived processes only. However, in the future, we might restart the executor a few
      // times on the same machine.
      val exitCode = process.waitFor()
      killProcess()
      state = ExecutorState.FAILED
      val message = "Command exited with code " + exitCode
      worker ! ExecutorStateChanged(appId, execId, state, Some(message), Some(exitCode))
    } catch {
      case interrupted: InterruptedException => {
        logInfo("Runner thread for executor " + fullId + " interrupted")
        killProcess()
      }
        

      case e: Exception => {
        logError("Error running executor", e)
        killProcess()
        state = ExecutorState.FAILED
        val message = e.getClass + ": " + e.getMessage
        worker ! ExecutorStateChanged(appId, execId, state, Some(message), None)
      }
    }
  }
}
