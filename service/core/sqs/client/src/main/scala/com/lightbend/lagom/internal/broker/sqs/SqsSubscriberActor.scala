package com.lightbend.lagom.internal.broker.sqs

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.Props
import akka.actor.Status
import akka.pattern.pipe
import akka.stream._
import akka.stream.alpakka.sqs.MessageAction
import akka.stream.alpakka.sqs.scaladsl.SqsAckFlow
import akka.stream.alpakka.sqs.scaladsl.SqsSource
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.GraphDSL
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Unzip
import akka.stream.scaladsl.Zip
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.Message

import scala.concurrent.ExecutionContext
import scala.concurrent.Promise

private[lagom] class SqsSubscriberActor[Payload, SubscriberPayload](
    sqsConfig: SqsConfig,
    consumerConfig: ConsumerConfig,
    topicId: String,
    flow: Flow[SubscriberPayload, Done, _],
    streamCompleted: Promise[Done],
    transform: Message => SubscriberPayload
)(implicit sqsClient: SqsAsyncClient, mat: Materializer, ec: ExecutionContext)
    extends Actor
    with ActorLogging {

  /** Switch used to terminate the on-going SQS publishing stream when this actor fails. */
  private var shutdown: Option[KillSwitch] = None

  override def preStart(): Unit = {
    val queueUrl = sqsConfig.queueUrl
    run(queueUrl)
  }

  override def postStop(): Unit = {
    shutdown.foreach(_.shutdown())
  }

  private def running: Receive = {
    case Status.Failure(e) =>
      log.error("Topic subscription interrupted due to failure: [{}]", e)
      throw e

    case Done =>
      log.info("SQS subscriber stream for topic {} was completed.", topicId)
      streamCompleted.success(Done)
      context.stop(self)
  }

  override def receive = PartialFunction.empty

  private def run(queueUrl: String): Unit = {
    val (killSwitch, streamDone) =
      atLeastOnce(queueUrl)
        .viaMat(KillSwitches.single)(Keep.right)
        .toMat(Sink.ignore)(Keep.both)
        .run()

    shutdown = Some(killSwitch)
    streamDone.pipeTo(self)
    context.become(running)
  }

  private def atLeastOnce(queueUrl: String): Source[Done, _] = {
    // TODO: load SqsSourceSettings

    // Create a source of pairs where the first element is a SQS message, and the second is the
    // message body. Then, the source of pairs is split into two streams, so that the `flow` passed
    // in as an argument can be applied to the message body. After having applied the `flow`, the
    // two streams are combined back and the processed message is deleted from the queue.
    val pairedSource = SqsSource(queueUrl).map(message => (message, transform(message)))

    val deleteMessageFlow = Flow.fromGraph(GraphDSL.create(flow) { implicit builder => flow =>
      import GraphDSL.Implicits._
      val unzip = builder.add(Unzip[Message, SubscriberPayload])
      val zip   = builder.add(Zip[Message, Done])
      val delete = {
        val deleteFlow = Flow[(Message, Done)]
          .map({ case (message, _) => MessageAction.Delete(message) })
          .via(SqsAckFlow(queueUrl))
          .map(_ => Done)
        builder.add(deleteFlow)
      }
      // To allow the user flow to do its own batching, the message side of the flow needs to effectively buffer
      // infinitely to give full control of backpressure to the user side of the flow.
      val offsetBuffer = Flow[Message].buffer(consumerConfig.offsetBuffer, OverflowStrategy.backpressure)

      unzip.out0 ~> offsetBuffer ~> zip.in0
      unzip.out1 ~> flow ~> zip.in1
      zip.out ~> delete.in

      FlowShape(unzip.in, delete.out)
    })

    pairedSource.via(deleteMessageFlow)
  }
}

object SqsSubscriberActor {
  def props[Payload, SubscriberPayload](
      sqsConfig: SqsConfig,
      consumerConfig: ConsumerConfig,
      topicId: String,
      flow: Flow[SubscriberPayload, Done, _],
      streamCompleted: Promise[Done],
      transform: Message => SubscriberPayload
  )(implicit sqsClient: SqsAsyncClient, mat: Materializer, ec: ExecutionContext) =
    Props(
      new SqsSubscriberActor[Payload, SubscriberPayload](
        sqsConfig,
        consumerConfig,
        topicId,
        flow,
        streamCompleted,
        transform
      )
    )
}
