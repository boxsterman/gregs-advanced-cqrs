package greg.stoploss

import org.scalatest.{Matchers, WordSpec}

class StopLossProcessManagerSpec extends  WordSpec with Matchers {

  "A StopLossProcess Manager" should {

    "respond to a price change with two future messages" in new TestScope {

      // Given
      events shouldBe empty

      // when
      pm.handleMessage(PositionAcquired(30))

      // then
      events should have size(1)
      events.head should equal (StopLossTargetPriceUpdated(30))
    }

    "respond to a PriceUpdated" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(30))
      events = Nil

      // when
      pm.handleMessage(PriceUpdated(40))

      // then
      events should have size 2
      events(0) should equal(SendToMeIn(11, RemoveFrom11SecondsWindow(40)))
      events(1) should equal(SendToMeIn(15, RemoveFrom15SecondsWindow(40)))
    }

    "respond to a deferred RemoveFrom11Seconds window having a new min" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(25))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom11SecondsWindow(25))

      // then
      events should have size 1
      events.head should equal(StopLossTargetPriceUpdated(25))
    }

    "respond to a deferred RemoveFrom11Seconds window having no new min" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(25))
      pm.handleMessage(PriceUpdated(20))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom11SecondsWindow(25))

      // then
      events should have size 0
    }


    "respond to multiple deferred RemoveFrom11Seconds below 1 cent threshold" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(19))
      pm.handleMessage(PriceUpdated(21))
      pm.handleMessage(RemoveFrom11SecondsWindow(19))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom11SecondsWindow(21))

      // then
      events should have size 0
    }

    "respond to multiple deferred RemoveFrom11Seconds" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(19))
      pm.handleMessage(PriceUpdated(22))
      pm.handleMessage(RemoveFrom11SecondsWindow(19))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom11SecondsWindow(22))

      // then
      events should have size 1
      events.head should equal(StopLossTargetPriceUpdated(22))
    }

    "respond to a deferred RemoveFrom15Seconds window having a new max resulting in a TargetHit" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(19))
      pm.handleMessage(PriceUpdated(17))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom15SecondsWindow(19))

      // then
      events should have size 1
      events.head should equal(TargetHit(19))
    }

    "respond to a deferred RemoveFrom15Seconds window having no new max" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(19))
      pm.handleMessage(PriceUpdated(21))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom15SecondsWindow(19))

      // then
      events should have size 0
    }

    "respond to multiple deferred RemoveFrom15Seconds window " in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(21))
      pm.handleMessage(PriceUpdated(19))
      pm.handleMessage(RemoveFrom15SecondsWindow(21))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom15SecondsWindow(19))

      // then
      events should have size 1
      events.head should equal(TargetHit(19))
    }


    "respond to multiple messages" in new TestScope {

      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(25))
      pm.handleMessage(RemoveFrom11SecondsWindow(25))
      pm.handleMessage(PriceUpdated(21))
      pm.handleMessage(RemoveFrom11SecondsWindow(21))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom15SecondsWindow(25))
      pm.handleMessage(RemoveFrom15SecondsWindow(21))

      // then
      events should have size 1
      events.head should equal(TargetHit(21))
    }

    "immediate sell" in new TestScope {
      // Given
      pm.handleMessage(PositionAcquired(20))
      pm.handleMessage(PriceUpdated(10))
      pm.handleMessage(RemoveFrom11SecondsWindow(10))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom15SecondsWindow(10))

      // then
      events should have size 1
      events.head should equal(TargetHit(10))
    }

    "Handle double price value" in new TestScope {
      // Given
      pm.handleMessage(PositionAcquired(100))
      pm.handleMessage(PriceUpdated(89))
      pm.handleMessage(PriceUpdated(89))
      pm.handleMessage(RemoveFrom11SecondsWindow(89))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom11SecondsWindow(89))

      // then
      events should have size 0
    }

    "Avoid double sell" in new TestScope {
      // Given
      pm.handleMessage(PositionAcquired(100))
      pm.handleMessage(PriceUpdated(89))
      pm.handleMessage(PriceUpdated(89))
      pm.handleMessage(RemoveFrom11SecondsWindow(89))
      pm.handleMessage(RemoveFrom11SecondsWindow(89))
      events = Nil

      // When
      pm.handleMessage(RemoveFrom15SecondsWindow(89))
      pm.handleMessage(RemoveFrom15SecondsWindow(89))

      // then
      events should have size 1
      events.head should equal(TargetHit(89))
    }
  }

}

abstract class TestScope {
  var events: List[MessageX] = Nil
  val pm = new StopLossProcessManager(m => events = events ::: List(m))
}
