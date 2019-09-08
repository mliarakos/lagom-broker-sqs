package com.lightbend.lagom.internal.scaladsl.broker.sqs

import akka.actor.ActorSystem
import akka.persistence.query.Offset
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import com.lightbend.internal.broker.TaggedOffsetTopicProducer
import com.lightbend.lagom.internal.broker.sqs.Producer
import com.lightbend.lagom.internal.broker.sqs.SqsConfig
import com.lightbend.lagom.internal.scaladsl.api.broker.TopicFactory
import com.lightbend.lagom.scaladsl.api.Descriptor.TopicCall
import com.lightbend.lagom.scaladsl.api.ServiceInfo
import com.lightbend.lagom.scaladsl.api.ServiceSupport.ScalaMethodTopic
import com.lightbend.lagom.scaladsl.server.LagomServer
import com.lightbend.lagom.spi.persistence.OffsetStore
import org.slf4j.LoggerFactory
import software.amazon.awssdk.services.sqs.SqsAsyncClient

import scala.concurrent.ExecutionContext

class ScaladslRegisterTopicProducers(
    lagomServer: LagomServer,
    topicFactory: TopicFactory,
    info: ServiceInfo,
    actorSystem: ActorSystem,
    offsetStore: OffsetStore
)(implicit qsClient: SqsAsyncClient, ec: ExecutionContext, mat: Materializer) {

  private val log       = LoggerFactory.getLogger(classOf[ScaladslRegisterTopicProducers])
  private val sqsConfig = SqsConfig(actorSystem.settings.config)

  // Goes through the services' descriptors and publishes the streams registered in
  // each of the service's topic method implementation.
  for {
    service <- lagomServer.serviceBindings
    tc      <- service.descriptor.topics
    topicCall = tc.asInstanceOf[TopicCall[Any]]
  } {
    topicCall.topicHolder match {
      case holder: ScalaMethodTopic[Any] =>
        val topicProducer = holder.method.invoke(service.service)
        val topicId       = topicCall.topicId

        topicFactory.create(topicCall) match {
          case topicImpl: ScaladslSqsTopic[Any] =>
            topicProducer match {
              case tagged: TaggedOffsetTopicProducer[Any, _] =>
                val tags = tagged.tags

                val eventStreamFactory: (String, Offset) => Source[(Any, Offset), _] = { (tag, offset) =>
                  tags.find(_.tag == tag) match {
                    case Some(aggregateTag) => tagged.readSideStream(aggregateTag, offset)
                    case None               => throw new RuntimeException("Unknown tag: " + tag)
                  }
                }

                // TODO: implement partitionKeyStrategy
                // val partitionKeyStrategy: Option[Any => String] = {
                //   topicCall.properties.get(KafkaProperties.partitionKeyStrategy).map { pks => message =>
                //     pks.computePartitionKey(message)
                //   }
                // }

                val partitionKeyStrategy: Option[Any => String] = None

                val serializer                       = topicCall.messageSerializer.serializerForRequest
                def transform[T](message: T): String = serializer.serialize(message).utf8String

                Producer.startTaggedOffsetProducer(
                  actorSystem,
                  tags.map(_.tag),
                  sqsConfig,
                  topicId.name,
                  eventStreamFactory,
                  partitionKeyStrategy,
                  transform,
                  offsetStore
                )

              case other =>
                log.warn {
                  s"Unknown topic producer ${other.getClass.getName}. " +
                    s"This will likely result in no events published to topic ${topicId.name} by service ${info.serviceName}."
                }
            }

          case otherTopicImpl =>
            log.warn {
              s"Expected Topic type ${classOf[ScaladslSqsTopic[_]].getName}, but found incompatible type ${otherTopicImpl.getClass.getName}." +
                s"This will likely result in no events published to topic ${topicId.name} by service ${info.serviceName}."
            }
        }

      case other =>
        log.error {
          s"Cannot plug publisher source for topic ${topicCall.topicId}. " +
            s"Reason was that it was expected a topicHolder of type ${classOf[ScalaMethodTopic[_]]}, " +
            s"but ${other.getClass} was found instead."
        }
    }
  }

}
