package com.lightbend.lagom.internal.broker.sqs

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

sealed trait SqsConfig {

  /** The SQS queue URL. Will be ignored if serviceName is defined. */
  def queueUrl: String

}

object SqsConfig {

  private final class SqsConfigImpl(conf: Config) extends SqsConfig {
    override val queueUrl: String = conf.getString("queue")
  }

  def apply(conf: Config): SqsConfig = new SqsConfigImpl(conf.getConfig("lagom.broker.aws.sqs"))

}

sealed trait ClientConfig {
  def minBackoff: FiniteDuration
  def maxBackoff: FiniteDuration
  def randomBackoffFactor: Double
}

object ClientConfig {
  private[sqs] class ClientConfigImpl(conf: Config) extends ClientConfig {
    val minBackoff: FiniteDuration  = conf.getDuration("failure-exponential-backoff.min", TimeUnit.MILLISECONDS).millis
    val maxBackoff: FiniteDuration  = conf.getDuration("failure-exponential-backoff.max", TimeUnit.MILLISECONDS).millis
    val randomBackoffFactor: Double = conf.getDouble("failure-exponential-backoff.random-factor")
  }
}

sealed trait ProducerConfig extends ClientConfig {
  def role: Option[String]
}

object ProducerConfig {

  private class ProducerConfigImpl(conf: Config) extends ClientConfig.ClientConfigImpl(conf) with ProducerConfig {
    val role: Option[String] = Some(conf.getString("role")).filter(_.nonEmpty)
  }

  def apply(conf: Config): ProducerConfig =
    new ProducerConfigImpl(conf.getConfig("lagom.broker.aws.sqs.client.producer"))

}

sealed trait ConsumerConfig extends ClientConfig {
  def offsetBuffer: Int
  def waitTimeSeconds: Int
  def maxBufferSize: Int
  def maxBatchSize: Int
}

object ConsumerConfig {

  private final class ConsumerConfigImpl(conf: Config) extends ClientConfig.ClientConfigImpl(conf) with ConsumerConfig {
    val offsetBuffer: Int    = conf.getInt("offset-buffer")
    val waitTimeSeconds: Int = conf.getInt("wait-time-seconds")
    val maxBufferSize: Int   = conf.getInt("max-buffer-size")
    val maxBatchSize: Int    = conf.getInt("max-batch-size")
  }

  def apply(conf: Config): ConsumerConfig =
    new ConsumerConfigImpl(conf.getConfig("lagom.broker.aws.sqs.client.consumer"))

}

private[lagom] final class NoSqsBrokersException(serviceName: String)
    extends RuntimeException(s"No SQS brokers found in service locator for SQS service name [$serviceName]")
    with NoStackTrace
