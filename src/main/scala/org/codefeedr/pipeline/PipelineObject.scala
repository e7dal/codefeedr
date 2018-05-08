package org.codefeedr.pipeline

import org.apache.flink.api.java.operators.DataSink
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.streaming.api.scala.DataStream
import org.codefeedr.pipeline.buffer.BufferFactory

import scala.reflect.{ClassTag, Manifest}
import scala.reflect.runtime.universe._

/**
  * This class represents a processing job within the pipeline.
  *
  * @tparam In  input type for this pipeline object.
  * @tparam Out output type for this pipeline object.
  */
abstract class PipelineObject[In <: PipelineItem : ClassTag : Manifest, Out <: PipelineItem : ClassTag : Manifest] {

  var pipeline: Pipeline = _

  /**
    * Setups the pipeline object with a pipeline.
    * @param pipeline the pipeline it belongs to.
    */
  def setUp(pipeline: Pipeline): Unit = {
    this.pipeline = pipeline
  }

  /**
    * Transforms the pipeline object from its input type to its output type.
    * This requires using the Flink DataStream API.
    *
    * @param source the input source.
    * @return the transformed stream.
    */
  def transform(source: DataStream[In]): DataStream[Out]

  /**
    * Removes the pipeline.
    */
  def tearDown(): Unit = {
    this.pipeline = null
  }

  /**
    * Get all parents for this object
    *
    * @return set of parents. Can be empty
    */
  def getParents: Set[PipelineObject[PipelineItem, PipelineItem]] = {
    pipeline.graph.getParents(this).asInstanceOf[Set[PipelineObject[PipelineItem, PipelineItem]]]
  }

  /**
    * Check if this pipeline object is sourced from a Buffer.
    * @return if this object has a (buffer) source.
    */
  def hasMainSource: Boolean =
    typeOf[In] != typeOf[NoType] && pipeline.graph.getMainParent(this).isDefined

  /**
    * Check if this pipeline object is sinked to a Buffer.
    * @return if this object has a (buffer) sink.
    */
  def hasSink: Boolean = typeOf[Out] != typeOf[NoType]

  /**
    * Returns the buffer source of this pipeline object.
    * @return the DataStream resulting from the buffer.
    */
  def getMainSource: DataStream[In] = {
    assert(pipeline != null)

    if (!hasMainSource) {
      throw NoSourceException("PipelineObject defined NoType as In type. Buffer can't be created.")
    }

//    val parentNode = pipeline.graph.getMainParent(this)

    val factory = new BufferFactory(pipeline)
    val buffer = factory.create[In]()

    buffer.getSource
  }

  /**
    * Returns the buffer sink of this pipeline object.
    * @return the SinkFunction resulting from the buffer.
    */
  def getSink: SinkFunction[Out] = {
    assert(pipeline != null)

    if (!hasSink) {
      throw NoSinkException("PipelineObject defined NoType as Out type. Buffer can't be created.")
    }

    val factory = new BufferFactory(pipeline)
    val buffer = factory.create[Out]()

    buffer.getSink
  }

  def getStorageSource[T](typ: String, collection: String): DataStream[T] = ???

  def getStorageSink[T](typ: String, collection: String): DataSink[T] = ???

  /**
    * Get a source for given parent object.
    *
    * @param objectClass
    * @tparam T
    * @return
    */
//  def getSource[T](objectClass: Class): DataStream[T] = {
//
//    val parents = pipeline.graph.getParents(this)
//
//    null
//  }
}
