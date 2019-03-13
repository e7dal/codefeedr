package org.codefeedr.plugins.ghtorrent.util

import java.io.IOException
import java.util

import com.rabbitmq.client._
import org.apache.flink.api.common.functions.RuntimeContext
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.api.java.typeutils.ResultTypeQueryable
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.source.{
  MultipleIdsMessageAcknowledgingSourceBase,
  SourceFunction
}
import org.apache.flink.streaming.api.operators.StreamingRuntimeContext
import org.apache.flink.streaming.connectors.rabbitmq.common.RMQConnectionConfig
import org.apache.flink.util.Preconditions
import org.slf4j.{Logger, LoggerFactory}

import scala.io.Source
import collection.JavaConverters._

class GHTorrentRMQSource(username: String,
                         routingKeysFile: String = "routing_keys.txt",
                         usesCorrelationId: Boolean = false)
    extends MultipleIdsMessageAcknowledgingSourceBase[String, String, Long](
      classOf[String])
    with ResultTypeQueryable[String] {

  private val serialVersionUID: Long = 1L
  private val LOG: Logger = LoggerFactory.getLogger(classOf[GHTorrentRMQSource])
  private val schema: SimpleStringSchema = new SimpleStringSchema()
  private val rmConnectionConfig: RMQConnectionConfig =
    new RMQConnectionConfig.Builder()
      .setHost("localhost")
      .setPort(5672)
      .setVirtualHost("/")
      .setUserName("streamer")
      .setPassword("streamer")
      .build()

  @transient
  protected var connection: Connection = null

  @transient
  protected var channel: Channel = null

  @transient
  protected var autoAck: Boolean = false

  @transient @volatile
  private var running: Boolean = false

  /** Setting queueName according to GHTorrent specification **/
  private val queueName = username + "_queue"

  /** Parse all routing keys from the file. We assume they are separated by new lines. **/
  val routingKeys = parseRoutingKeys()

  /** Name of the exchange, this is a requirement by the GHTorrent streaming service. **/
  val exchangeName = "ght-streams"

  /**
    * Setups queue according to http://ghtorrent.org/streaming.html
    */
  def setupQueue(): Unit = {
    // First of all, we declare an exchange with the correct name and type.
    channel.exchangeDeclare(exchangeName, "topic", true)

    // Create a queue with auto delete.
    channel.queueDeclare(username + "_queue",
                         false,
                         false,
                         true,
                         new util.HashMap[String, AnyRef]())

    // For each routing key, bind it to the channel.
    routingKeys.foreach(channel.queueBind(queueName, exchangeName, _))
  }

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)

    val factory: ConnectionFactory = rmConnectionConfig.getConnectionFactory()

    try {

      connection = factory.newConnection()
      channel = connection.createChannel()

      if (channel == null) {
        throw new RuntimeException("None of RabbitMQ channels are available.")
      }

      setupQueue()

      val runtimeContext: RuntimeContext = getRuntimeContext()

      if (runtimeContext.isInstanceOf[StreamingRuntimeContext] && runtimeContext
            .asInstanceOf[StreamingRuntimeContext]
            .isCheckpointingEnabled) {
        autoAck = false
        channel.txSelect() // enable transaction mode
      } else {
        autoAck = true
      }

    } catch {
      case e: IOException =>
        throw new RuntimeException(
          "Cannot create a RabbitMQ connection at " + rmConnectionConfig.getHost,
          e)
    }

    running = true
  }

  override def close(): Unit = {
    super.close()

    try {
      if (connection != null) {
        connection.close()
      }
    } catch {
      case e: IOException =>
        throw new RuntimeException(
          "Error while closing RabbitMQ connection at " + rmConnectionConfig.getHost,
          e)
    }

  }

  override def run(ctx: SourceFunction.SourceContext[String]): Unit = {
    LOG.debug("Starting RabbitMQ source with autoAck status: " + autoAck)
    val consumerTag = "codefeedrConsumerTag"
    channel.basicConsume(
      queueName,
      autoAck,
      "codefeedrConsumerTag",
      new DefaultConsumer(channel) {
        override def handleDelivery(consumerTag: String,
                                    envelope: Envelope,
                                    properties: AMQP.BasicProperties,
                                    body: Array[Byte]): Unit = {

          ctx.getCheckpointLock.synchronized {

            val routingKey = envelope.getRoutingKey()
            val result = schema.deserialize(body)

            if (schema.isEndOfStream(result)) {
              running = false
              channel.basicCancel(consumerTag)
            }

            if (!autoAck) {
              val deliveryTag = envelope.getDeliveryTag

              if (usesCorrelationId) {
                val correlationId = properties.getCorrelationId

                Preconditions.checkNotNull(
                  correlationId,
                  "RabbitMQ source was instantiated " + "with usesCorrelationId set to true but a message was received with " + "correlation id set to null!")
                if (!addId(correlationId)) {
                  return
                }
              }
              sessionIds.add(deliveryTag)
            }

            ctx.collect(s"$routingKey#$result")
          }

        }

      }
    )

    while (running) {}

  }

  override def acknowledgeSessionIDs(sessionIds: util.List[Long]): Unit = {
    try {
      sessionIds.asScala.foreach(channel.basicAck(_, false))
      channel.txCommit()
    } catch {
      case e: IOException =>
        throw new RuntimeException(
          "Messages could not be acknowledged during checkpoint creation.",
          e)
    }
  }

  override def getProducedType: TypeInformation[String] = schema.getProducedType

  override def cancel(): Unit = running = false

  /** Parses all routing keys from the file.
    *
    * @return a list of routing keys.
    */
  private def parseRoutingKeys(): List[String] =
    Source.fromResource(routingKeysFile).mkString.split("\n").toList
}
