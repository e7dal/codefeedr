/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.codefeedr.stages.utilities

import org.apache.flink.api.scala._
import org.apache.flink.streaming.api.scala.DataStream
import org.codefeedr.stages.OutputStage
import org.json4s._
import org.json4s.jackson.Serialization

import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

/** Prints elements in DataStream in JSON format. */
class JsonPrinterOutput[T <: Serializable with AnyRef: ClassTag: TypeTag]
    extends OutputStage[T] {

  /** Transforms into JSON and prints DataStream. */
  override def main(source: DataStream[T]): Unit = {
    source
      .map { x: T =>
        implicit lazy val formats = Serialization.formats(NoTypeHints)
        new String(Serialization.write(x)(formats))
      }
      .print()
  }
}
