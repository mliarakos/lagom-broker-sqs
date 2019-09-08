package com.lightbend.lagom.internal.scaladsl.broker.sqs

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.ByteString
import com.lightbend.lagom.internal.broker.sqs.SqsConfig
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.broker.Subscriber
import com.lightbend.lagom.scaladsl.api.broker.Topic
import com.lightbend.lagom.scaladsl.api.broker.Topic.TopicId
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{ Message => SqsMessage }

import scala.concurrent.ExecutionContext

private[lagom] class ScaladslSqsTopic[Message](
    sqsConfig: SqsConfig,
    topicCall: TopicCall[Message],
    info: ServiceInfo,
    system: ActorSystem
)(implicit sqsClient: SqsAsyncClient, mat: Materializer, ec: ExecutionContext)
    extends Topic[Message] {

  override def topicId: TopicId = topicCall.topicId

  override def subscribe: Subscriber[Message] = {
    val messageSerializer = topicCall.messageSerializer
    val protocol          = messageSerializer.serializerForRequest.protocol
    val deserializer      = messageSerializer.deserializer(protocol)

    def transform(message: SqsMessage): Message = deserializer.deserialize(ByteString.fromString(message.body))

    new ScaladslSqsSubscriber[Message, Message](
      sqsConfig,
      topicCall,
      ScaladslSqsSubscriber.GroupId.default(info),
      info,
      system,
      transform
    )
  }

}
