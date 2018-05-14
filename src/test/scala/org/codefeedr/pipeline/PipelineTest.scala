package org.codefeedr.pipeline

import java.util.Properties

import org.apache.flink.streaming.api.scala.DataStream
import org.codefeedr.pipeline.buffer.{BufferType, KafkaBuffer, NoAvroSerdeException}
import org.codefeedr.plugins.{StringSource, StringType}
import org.scalatest.{BeforeAndAfter, FunSuite}
import org.apache.flink.api.scala._
import org.apache.flink.runtime.client.JobExecutionException
import org.apache.flink.runtime.messages.JobManagerMessages.JobResultSuccess
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig, KafkaAdminClient}
import org.codefeedr.pipeline.buffer.serialization.Serializer
import org.codefeedr.pipeline.buffer.serialization.schema_exposure.{RedisSchemaExposer, ZookeeperSchemaExposer}
import org.codefeedr.testUtils._

import scala.collection.JavaConverters._

class PipelineTest extends FunSuite with BeforeAndAfter {

  var builder: PipelineBuilder = _

  before {
    builder = new PipelineBuilder()
    CollectSink.result.clear()
  }

  test("Simple pipeline test wordcount") {
    builder
      .append(new StringSource("hallo hallo doei doei doei"))
      .append { x: DataStream[StringType] =>
        x.map(x => (x.value, 1))
          .keyBy(0)
          .sum(1)
          .map(x => WordCount(x._1, x._2))
      }
      .append { x: DataStream[WordCount] =>
        x.addSink(new CollectSink)
      }
      .build()
      .startMock()

    val res = CollectSink.result.asScala

    assert(res.contains(WordCount("doei", 3)))
    assert(res.contains(WordCount("hallo", 2)))
  }

  test("Simple pipeline test") {
    builder
      .append(new StringSource())
      .append { x: DataStream[StringType] =>
        x.map(x => WordCount(x.value, 1)).setParallelism(1)
          .addSink(new CollectSink).setParallelism(1)
      }
      .build()
      .startMock()

    val res = CollectSink.result.asScala

    assert(res.contains(WordCount("", 1)))
  }

  test("Non-sequential pipeline mock test") {
    val pipeline = simpleDAGPipeline().build()

    assertThrows[IllegalStateException] {
      pipeline.start(Array("-runtime", "mock"))
    }
  }

  test("Non-sequential pipeline local test") {
    val pipeline = simpleDAGPipeline(1)
        .setBufferType(BufferType.Kafka)
        .build()

    assertThrows[JobExecutionException] {
      pipeline.startLocal()
    }
  }

  test("Simple pipeline schema exposure test (redis)") {
    val pipeline = simpleDAGPipeline(2)
      .setBufferType(BufferType.Kafka)
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE, "true")
      .setBufferProperty(KafkaBuffer.SERIALIZER, Serializer.AVRO)
      .build()

    assertThrows[JobExecutionException] {
      pipeline.startLocal()
    }

    val exposer = new RedisSchemaExposer("redis://localhost:6379")

    val schema1 = exposer.get("org.codefeedr.testUtils.SimpleSourcePipelineObject")
    val schema2 = exposer.get("org.codefeedr.testUtils.SimpleTransformPipelineObject")

    assert(!schema1.isEmpty)
    assert(!schema2.isEmpty)
  }

  test("Simple pipeline schema exposure and deserialization test with JSON (redis)") {
    val pipeline = simpleDAGPipeline(2)
      .setBufferType(BufferType.Kafka)
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE, "true")
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE_DESERIALIZATION, "true")
      .setBufferProperty(KafkaBuffer.SERIALIZER, Serializer.JSON)
      .build()

    assertThrows[NoAvroSerdeException] {
      pipeline.startLocal()
    }
  }

  test("Simple pipeline schema exposure and deserialization test (redis)") {
    val pipeline = simpleDAGPipeline(2)
      .setBufferType(BufferType.Kafka)
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE, "true")
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE_DESERIALIZATION, "true")
      .setBufferProperty(KafkaBuffer.SERIALIZER, Serializer.AVRO)
      .build()

    assertThrows[JobExecutionException] {
      pipeline.startLocal()
    }
  }

  test("Simple pipeline schema exposure test (zookeeper)") {
    val pipeline = simpleDAGPipeline(2)
      .setBufferType(BufferType.Kafka)
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE, "true")
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE_SERVICE, "zookeeper")
      .setBufferProperty(KafkaBuffer.SCHEMA_EXPOSURE_HOST, "localhost:2181")
      .setBufferProperty(KafkaBuffer.SERIALIZER, Serializer.AVRO)
      .build()

    assertThrows[JobExecutionException] {
      pipeline.startLocal()
    }

    val exposer = new ZookeeperSchemaExposer("localhost:2181")

    val schema1 = exposer.get("org.codefeedr.testUtils.SimpleSourcePipelineObject")
    val schema2 = exposer.get("org.codefeedr.testUtils.SimpleTransformPipelineObject")

    assert(!schema1.isEmpty)
    assert(!schema2.isEmpty)
  }

  /**
    * Builds a really simple (DAG) graph
    * @param expectedMessages after the job will finish.
    * @return a pipelinebuilder (add elements or finish it with .build())
    */
  def simpleDAGPipeline(expectedMessages : Int = -1) = {
    val source = new SimpleSourcePipelineObject()
    val a = new SimpleTransformPipelineObject()
    val b = new SimpleTransformPipelineObject()
    val sink = new SimpleSinkPipelineObject(expectedMessages)

    builder
      .append(source)
      .setPipelineType(PipelineType.DAG)
      .edge(source, a)
      .edge(source, b)
      .edge(a, sink)
      .extraEdge(b, sink)
  }


}
