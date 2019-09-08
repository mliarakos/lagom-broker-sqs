package com.lightbend.lagom.internal.scaladsl.broker.sqs

import java.util.concurrent.atomic.AtomicInteger

import akka.Done
import akka.actor.ActorSystem
import akka.actor.SupervisorStrategy
import akka.pattern.BackoffSupervisor
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Source
import com.lightbend.lagom.internal.broker.sqs.ConsumerConfig
import com.lightbend.lagom.internal.broker.sqs.SqsConfig
import com.lightbend.lagom.internal.broker.sqs.SqsSubscriberActor
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.broker.Message
import com.lightbend.lagom.scaladsl.api.broker.MetadataKey
import com.lightbend.lagom.scaladsl.api.broker.Subscriber
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.{ Message => SqsMessage }

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

private[lagom] class ScaladslSqsSubscriber[Payload, SubscriberPayload](
    sqsConfig: SqsConfig,
    topicCall: TopicCall[Payload],
    groupId: Subscriber.GroupId,
    info: ServiceInfo,
    system: ActorSystem,
    transform: SqsMessage => SubscriberPayload
)(implicit sqsClient: SqsAsyncClient, mat: Materializer, ec: ExecutionContext)
    extends Subscriber[SubscriberPayload] {

  private val log = LoggerFactory.getLogger(classOf[ScaladslSqsSubscriber[_, _]])

  import ScaladslSqsSubscriber._

  private lazy val consumerId = SqsClientIdSequenceNumber.getAndIncrement

  private def consumerConfig = ConsumerConfig(system.settings.config)

  @throws(classOf[IllegalArgumentException])
  override def withGroupId(groupIdName: String): Subscriber[SubscriberPayload] = {
    val newGroupId = {
      if (groupIdName == null) {
        // An empty group id is not allowed by SQS
        val defaultGroupId = GroupId.default(info)
        log.debug {
          "Passed a null groupId, but SQS requires clients to set one. " +
            s"Defaulting $this consumer groupId to $defaultGroupId."
        }
        defaultGroupId
      } else GroupId(groupIdName)
    }

    if (newGroupId == groupId) this
    else new ScaladslSqsSubscriber(sqsConfig, topicCall, newGroupId, info, system, transform)
  }

  override def withMetadata = new ScaladslSqsSubscriber[Payload, Message[SubscriberPayload]](
    sqsConfig,
    topicCall,
    groupId,
    info,
    system,
    wrapPayload
  )

  private def wrapPayload(message: SqsMessage): Message[SubscriberPayload] = {
    Message(transform(message)) +
      (MetadataKey.MessageKey[String] -> message.messageId)
  }

  override def atMostOnceSource: Source[SubscriberPayload, _] = ???

  override def atLeastOnce(flow: Flow[SubscriberPayload, Done, _]): Future[Done] = {
    val streamCompleted = Promise[Done]
    val consumerProps =
      SqsSubscriberActor.props[Payload, SubscriberPayload](
        sqsConfig,
        consumerConfig,
        topicCall.topicId.name,
        flow,
        streamCompleted,
        transform
      )

    val backoffConsumerProps =
      BackoffSupervisor.propsWithSupervisorStrategy(
        consumerProps,
        s"SqsConsumerActor$consumerId-${topicCall.topicId.name}",
        consumerConfig.minBackoff,
        consumerConfig.maxBackoff,
        consumerConfig.randomBackoffFactor,
        SupervisorStrategy.stoppingStrategy
      )

    system.actorOf(backoffConsumerProps, s"SqsBackoffConsumer$consumerId-${topicCall.topicId.name}")

    streamCompleted.future
  }
}

private[lagom] object ScaladslSqsSubscriber {
  private val SqsClientIdSequenceNumber = new AtomicInteger(1)

  case class GroupId(groupId: String) extends Subscriber.GroupId {
    if (GroupId.isInvalidGroupId(groupId))
      throw new IllegalArgumentException(
        s"Failed to create group because [groupId=$groupId] contains invalid character(s). Check the SQS spec for creating a valid group id."
      )
  }

  case object GroupId {
    private val InvalidGroupIdChars                        = """[^a-zA-Z0-9!"#$%&'()*+,\-./:;<=>?@\[\]^_`{|}~]""".r
    private def isInvalidGroupId(groupId: String): Boolean = InvalidGroupIdChars.findFirstIn(groupId).nonEmpty
    def default(info: ServiceInfo): GroupId                = GroupId(info.serviceName)
  }

}
