package com.lightbend.lagom.internal.broker.sqs

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.Status
import akka.actor.SupervisorStrategy
import akka.cluster.sharding.ClusterShardingSettings
import akka.pattern.BackoffSupervisor
import akka.pattern.pipe
import akka.persistence.query.Offset
import akka.stream.FlowShape
import akka.stream.KillSwitch
import akka.stream.KillSwitches
import akka.stream.Materializer
import akka.stream.alpakka.sqs.scaladsl.SqsPublishFlow
import akka.stream.scaladsl._
import com.lightbend.lagom.internal.persistence.cluster.ClusterDistribution.EnsureActive
import com.lightbend.lagom.internal.persistence.cluster.ClusterDistribution
import com.lightbend.lagom.internal.persistence.cluster.ClusterDistributionSettings
import com.lightbend.lagom.spi.persistence.OffsetDao
import com.lightbend.lagom.spi.persistence.OffsetStore
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageRequest

import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

/**
 * A Producer for publishing messages in SQS using the Alpakka SQS API.
 */
private[lagom] object Producer {

  def startTaggedOffsetProducer[Message](
      system: ActorSystem,
      tags: immutable.Seq[String],
      sqsConfig: SqsConfig,
      topicId: String,
      eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
      partitionKeyStrategy: Option[Message => String],
      transform: Message => String,
      offsetStore: OffsetStore
  )(implicit sqsClient: SqsAsyncClient, mat: Materializer, ec: ExecutionContext): Unit = {

    val producerConfig = ProducerConfig(system.settings.config)
    val publisherProps = TaggedOffsetProducerActor.props(
      sqsConfig,
      topicId,
      eventStreamFactory,
      partitionKeyStrategy,
      transform,
      offsetStore
    )

    val backoffPublisherProps = BackoffSupervisor.propsWithSupervisorStrategy(
      publisherProps,
      s"producer",
      producerConfig.minBackoff,
      producerConfig.maxBackoff,
      producerConfig.randomBackoffFactor,
      SupervisorStrategy.stoppingStrategy
    )
    val clusterShardingSettings = ClusterShardingSettings(system).withRole(producerConfig.role)

    ClusterDistribution(system).start(
      s"sqsProducer-$topicId",
      backoffPublisherProps,
      tags.toSet,
      ClusterDistributionSettings(system).copy(clusterShardingSettings = clusterShardingSettings)
    )
  }

  private class TaggedOffsetProducerActor[Message](
      sqsConfig: SqsConfig,
      topicId: String,
      eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
      partitionKeyStrategy: Option[Message => String],
      transform: Message => String,
      offsetStore: OffsetStore
  )(implicit sqsClient: SqsAsyncClient, mat: Materializer, ec: ExecutionContext)
      extends Actor
      with ActorLogging {

    /** Switch used to terminate the on-going SQS publishing stream when this actor fails. */
    private var shutdown: Option[KillSwitch] = None

    override def postStop(): Unit = {
      shutdown.foreach(_.shutdown())
    }

    override def receive = {
      case EnsureActive(tag) =>
        val queueUrl  = sqsConfig.queueUrl
        val daoFuture = offsetStore.prepare(s"topicProducer-$topicId", tag)

        daoFuture.map(dao => (dao, queueUrl)).pipeTo(self)

        context.become(initializing(tag))
    }

    def generalHandler: Receive = {
      case Status.Failure(e) => throw e
      case EnsureActive(_)   =>
    }

    private def initializing(tag: String): Receive = generalHandler.orElse {
      case (queueUrl: String, dao: OffsetDao) => run(tag, queueUrl, dao)
    }

    private def active: Receive = generalHandler.orElse {
      case Done =>
        log.info("SQS producer stream for topic {} was completed.", topicId)
        context.stop(self)
    }

    private def run(tag: String, queueUrl: String, dao: OffsetDao): Unit = {
      val readSideSource = eventStreamFactory(tag, dao.loadedOffset)

      val (killSwitch, streamDone) = readSideSource
        .viaMat(KillSwitches.single)(Keep.right)
        .via(eventsPublisherFlow(queueUrl, dao))
        .toMat(Sink.ignore)(Keep.both)
        .run()

      shutdown = Some(killSwitch)
      streamDone.pipeTo(self)
      context.become(active)
    }

    private def eventsPublisherFlow(queueUrl: String, offsetDao: OffsetDao): Flow[(Message, Offset), Future[Done], _] =
      Flow.fromGraph(GraphDSL.create(sqsFlowPublisher(queueUrl)) { implicit builder => publishFlow =>
        import GraphDSL.Implicits._
        val unzip = builder.add(Unzip[Message, Offset])
        val zip   = builder.add(Zip[Any, Offset])
        val offsetCommitter = builder.add(Flow.fromFunction { e: (Any, Offset) =>
          offsetDao.saveOffset(e._2)
        })

        unzip.out0 ~> publishFlow ~> zip.in0
        unzip.out1 ~> zip.in1
        zip.out ~> offsetCommitter.in
        FlowShape(unzip.in, offsetCommitter.out)
      })

    private def sqsFlowPublisher(queueUrl: String): Flow[Message, _, _] = {
      def groupOf(message: Message): String = {
        partitionKeyStrategy match {
          case Some(strategy) => strategy(message)
          case None           => ""
        }
      }

      val clientIdAttribute = MessageAttributeValue.builder().stringValue(self.path.toStringWithoutAddress).build()
      val attributes        = immutable.Map("client.id" -> clientIdAttribute).asJava

      // TODO: use Lagom groupId
      // TODO: set messageDeduplicationId ?
      Flow[Message]
        .map(message => {
          val body = transform(message)
          SendMessageRequest
            .builder()
            .messageBody(body)
            .messageAttributes(attributes)
            .messageGroupId(groupOf(message))
            .build()
        })
        .via(SqsPublishFlow(queueUrl))
    }

  }

  private object TaggedOffsetProducerActor {
    def props[Message](
        sqsConfig: SqsConfig,
        topicId: String,
        eventStreamFactory: (String, Offset) => Source[(Message, Offset), _],
        partitionKeyStrategy: Option[Message => String],
        transform: Message => String,
        offsetStore: OffsetStore
    )(implicit sqsClient: SqsAsyncClient, mat: Materializer, ec: ExecutionContext) =
      Props(
        new TaggedOffsetProducerActor[Message](
          sqsConfig,
          topicId,
          eventStreamFactory,
          partitionKeyStrategy,
          transform,
          offsetStore
        )
      )
  }
}
