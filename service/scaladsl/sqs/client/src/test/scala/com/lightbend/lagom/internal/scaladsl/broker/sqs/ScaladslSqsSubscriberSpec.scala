package com.lightbend.lagom.internal.scaladsl.broker.sqs

import org.scalatest.FlatSpec
import org.scalatest.Matchers

class ScaladslSqsSubscriberSpec extends FlatSpec with Matchers {

  behavior.of("ScaladslSqsSubscriber")

  it should "create a new subscriber with updated groupId" in {
    val subscriber =
      new ScaladslSqsSubscriber(null, null, ScaladslSqsSubscriber.GroupId("old"), null, null, null)(
        null,
        null,
        null
      )
    subscriber.withGroupId("newGID") should not be subscriber
  }

}
