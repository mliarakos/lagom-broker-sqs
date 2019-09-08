package com.lightbend.lagom.internal.scaladsl.broker.sqs

import akka.actor.ActorSystem
import akka.stream.Materializer
import com.lightbend.lagom.internal.broker.sqs.SqsConfig
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactory
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.broker.Topic
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import scala.concurrent.ExecutionContext

private[lagom] class SqsTopicFactory(serviceInfo: ServiceInfo, system: ActorSystem)(
    implicit sqsClient: SqsAsyncClient,
    mat: Materializer,
    ec: ExecutionContext
) extends TopicFactory {

  private val config = SqsConfig(system.settings.config)

  def create[Message](topicCall: TopicCall[Message]): Topic[Message] = {
    new ScaladslSqsTopic(config, topicCall, serviceInfo, system)
  }

}
