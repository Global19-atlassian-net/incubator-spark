/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements. See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.spark.util

import org.apache.hadoop.security.KerberosName
import org.apache.hadoop.fs.Path
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.FileSystem
import java.io.DataOutputStream
import org.apache.hadoop.security.Credentials
import org.apache.hadoop.security.token.Token
import java.io.FileOutputStream
import java.io.File
import java.io.DataInputStream
import java.io.FileInputStream
import org.apache.spark.Logging

private[spark] object SparkSecurityUtils extends Logging {

  def getMasterKerberosPrincipal(): String = {
    System.getProperty("SPARK_MASTER_PRINCIPAL", System.getenv("SPARK_MASTER_PRINCIPAL"))
  }

  def getMasterKeytabPath(): String = {
    System.getProperty("SPARK_MASTER_KEYTAB", System.getenv("SPARK_MASTER_KEYTAB"))
  }

  def getWorkerKerberosPrincipal(): String = {
    System.getProperty("SPARK_WORKER_PRINCIPAL", System.getenv("SPARK_WORKER_PRINCIPAL"))
  }

  def getWorkerKeytabPath(): String = {
    System.getProperty("SPARK_WORKER_KEYTAB", System.getenv("SPARK_WORKER_KEYTAB"))
  }
}