package com.wavesplatform.matcher.market

import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorRef
import akka.persistence.serialization.Snapshot
import akka.testkit.{ImplicitSender, TestActorRef, TestProbe}
import com.wavesplatform.NTPTime
import com.wavesplatform.OrderOps._
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.matcher.api.AlreadyProcessed
import com.wavesplatform.matcher.fixtures.RestartableActor
import com.wavesplatform.matcher.fixtures.RestartableActor.RestartActor
import com.wavesplatform.matcher.market.MatcherActor.SaveSnapshot
import com.wavesplatform.matcher.market.OrderBookActor._
import com.wavesplatform.matcher.model.Events.OrderAdded
import com.wavesplatform.matcher.model._
import com.wavesplatform.matcher.{MatcherTestData, SnapshotUtils}
import com.wavesplatform.transaction.assets.exchange.{AssetPair, Order}
import com.wavesplatform.utils.EmptyBlockchain
import org.scalamock.scalatest.PathMockFactory

import scala.concurrent.duration._
import scala.util.Random

class OrderBookActorSpecification extends MatcherSpec("OrderBookActor") with NTPTime with ImplicitSender with MatcherTestData with PathMockFactory {

  private val txFactory = new ExchangeTransactionCreator(EmptyBlockchain, MatcherAccount, matcherSettings).createTransaction _
  private val obc       = new ConcurrentHashMap[AssetPair, OrderBook.AggregatedSnapshot]
  private val md        = new ConcurrentHashMap[AssetPair, MarketStatus]

  private def update(ap: AssetPair)(snapshot: OrderBook.AggregatedSnapshot): Unit = obc.put(ap, snapshot)

  private def obcTest(f: (AssetPair, TestActorRef[OrderBookActor with RestartableActor], TestProbe) => Unit): Unit =
    obcTestWithPrepare(_ => ()) { (pair, actor, probe) =>
      probe.expectMsg(OrderBookRecovered(pair, None))
      f(pair, actor, probe)
    }

  private def obcTestWithPrepare(prepare: AssetPair => Unit)(
      f: (AssetPair, TestActorRef[OrderBookActor with RestartableActor], TestProbe) => Unit): Unit = {
    obc.clear()
    md.clear()
    val b = ByteStr(new Array[Byte](32))
    Random.nextBytes(b.arr)

    val tp   = TestProbe()
    val pair = AssetPair(Some(b), None)
    prepare(pair)

    val actor = TestActorRef(
      new OrderBookActor(
        tp.ref,
        tp.ref,
        ActorRef.noSender, // TODO
        pair,
        update(pair),
        p => Option(md.get(p)),
        txFactory,
        ntpTime
      ) with RestartableActor)

    f(pair, actor, tp)
    system.stop(actor)
  }

  "OrderBookActor" should {
    "recover from snapshot - 1" in obcTestWithPrepare(_ => ()) { (pair, _, tp) =>
      tp.expectMsg(OrderBookRecovered(pair, None))
    }

    "recover from snapshot - 2" in obcTestWithPrepare { p =>
      SnapshotUtils.provideSnapshot(
        OrderBookActor.name(p),
        Snapshot(OrderBookActor.Snapshot(Some(50), OrderBook.empty.snapshot))
      )
    } { (pair, _, tp) =>
      tp.expectMsg(OrderBookRecovered(pair, Some(50)))
    }

    "recovery - notify address actor about orders" in obcTestWithPrepare(
      { p =>
        val ord = buy(p, 10 * Order.PriceConstant, 100)
        val ob  = OrderBook.empty
        ob.add(ord, ord.timestamp)
        SnapshotUtils.provideSnapshot(
          OrderBookActor.name(p),
          Snapshot(OrderBookActor.Snapshot(Some(50), ob.snapshot))
        )
      }
    ) { (pair, _, tp) =>
      tp.expectMsgType[OrderAdded]
      tp.expectMsg(OrderBookRecovered(pair, Some(50)))
    }

    "place buy and sell order to the order book and preserve it after restart" in obcTest { (pair, orderBook, tp) =>
      val ord1 = buy(pair, 10 * Order.PriceConstant, 100)
      val ord2 = sell(pair, 15 * Order.PriceConstant, 150)

      orderBook ! wrap(ord1)
      orderBook ! wrap(ord2)
      tp.receiveN(2)

      orderBook ! SaveSnapshot(Long.MaxValue)
      tp.expectMsgType[OrderBookSnapshotUpdateCompleted]
      orderBook ! RestartActor

      tp.receiveN(2) shouldEqual Seq(ord2, ord1).map(o => OrderAdded(LimitOrder(o)))
      tp.expectMsgType[OrderBookRecovered]
    }

    "execute partial market orders and preserve remaining after restart" in obcTest { (pair, actor, tp) =>
      val ord1 = buy(pair, 10 * Order.PriceConstant, 100)
      val ord2 = sell(pair, 15 * Order.PriceConstant, 100)

      actor ! wrap(ord1)
      actor ! wrap(ord2)

      tp.receiveN(3)

      actor ! SaveSnapshot(Long.MaxValue)
      tp.expectMsgType[OrderBookSnapshotUpdateCompleted]

      actor ! RestartActor
      tp.expectMsg(
        OrderAdded(
          SellLimitOrder(
            ord2.amount - ord1.amount,
            ord2.matcherFee - LimitOrder.partialFee(ord2.matcherFee, ord2.amount, ord1.amount),
            ord2
          )))
      tp.expectMsgType[OrderBookRecovered]
    }

    "execute one order fully and other partially and restore after restart" in obcTest { (pair, actor, tp) =>
      val ord1 = buy(pair, 10 * Order.PriceConstant, 100)
      val ord2 = buy(pair, 5 * Order.PriceConstant, 100)
      val ord3 = sell(pair, 12 * Order.PriceConstant, 100)

      actor ! wrap(ord1)
      actor ! wrap(ord2)
      actor ! wrap(ord3)
      tp.receiveN(4)

      actor ! SaveSnapshot(Long.MaxValue)
      tp.expectMsgType[OrderBookSnapshotUpdateCompleted]
      actor ! RestartActor

      val restAmount = ord1.amount + ord2.amount - ord3.amount
      tp.expectMsg(
        OrderAdded(
          BuyLimitOrder(
            restAmount,
            ord2.matcherFee - LimitOrder.partialFee(ord2.matcherFee, ord2.amount, ord2.amount - restAmount),
            ord2
          )))
      tp.expectMsgType[OrderBookRecovered]
    }

    "match multiple best orders at once and restore after restart" in obcTest { (pair, actor, tp) =>
      val ord1 = sell(pair, 10 * Order.PriceConstant, 100)
      val ord2 = sell(pair, 5 * Order.PriceConstant, 100)
      val ord3 = sell(pair, 5 * Order.PriceConstant, 90)
      val ord4 = buy(pair, 19 * Order.PriceConstant, 100)

      actor ! wrap(ord1)
      actor ! wrap(ord2)
      actor ! wrap(ord3)
      actor ! wrap(ord4)
      tp.receiveN(6)

      actor ! SaveSnapshot(Long.MaxValue)
      tp.expectMsgType[OrderBookSnapshotUpdateCompleted]
      actor ! RestartActor

      val restAmount = ord1.amount + ord2.amount + ord3.amount - ord4.amount
      tp.expectMsg(
        OrderAdded(
          SellLimitOrder(
            restAmount,
            ord2.matcherFee - LimitOrder.partialFee(ord2.matcherFee, ord2.amount, ord2.amount - restAmount),
            ord2
          )))
      tp.expectMsgType[OrderBookRecovered]
    }

    "place orders and restart without waiting for response" in obcTest { (pair, actor, tp) =>
      val ord1 = sell(pair, 10 * Order.PriceConstant, 100)
      val ts   = System.currentTimeMillis()

      (1 to 100).foreach({ i =>
        actor ! wrap(ord1.updateTimestamp(ts + i))
      })

      within(10.seconds) {
        tp.receiveN(100)
      }

      actor ! SaveSnapshot(Long.MaxValue)
      tp.expectMsgType[OrderBookSnapshotUpdateCompleted]
      actor ! RestartActor

      within(10.seconds) {
        tp.receiveN(100)
      }
      tp.expectMsgType[OrderBookRecovered]
    }

    "ignore outdated requests" in obcTest { (pair, actor, tp) =>
      (1 to 10).foreach { i =>
        actor ! wrap(i, buy(pair, 100000000, 0.00041))
      }
      tp.receiveN(10)

      (1 to 10).foreach { i =>
        actor ! wrap(i, buy(pair, 100000000, 0.00041))
      }
      all(receiveN(10)) shouldBe AlreadyProcessed
    }

    "respond on SaveSnapshotCommand" in obcTest { (pair, actor, tp) =>
      (1 to 10).foreach { i =>
        actor ! wrap(i, buy(pair, 100000000, 0.00041))
      }
      tp.receiveN(10)

      actor ! SaveSnapshot(10)
      tp.expectMsg(OrderBookSnapshotUpdateCompleted(pair, Some(10)))

      (11 to 20).foreach { i =>
        actor ! wrap(i, buy(pair, 100000000, 0.00041))
      }
      tp.receiveN(10)

      actor ! SaveSnapshot(20)
      tp.expectMsg(OrderBookSnapshotUpdateCompleted(pair, Some(20)))
    }

    "don't do a snapshot if there is no changes" in obcTest { (pair, actor, tp) =>
      (1 to 10).foreach { i =>
        actor ! wrap(i, buy(pair, 100000000, 0.00041))
      }
      tp.receiveN(10)

      actor ! SaveSnapshot(10)
      actor ! SaveSnapshot(10)
      tp.expectMsgType[OrderBookSnapshotUpdateCompleted]
      tp.expectNoMessage(200.millis)
    }

    "restore its state at start" in obcTest { (pair, actor, tp) =>
      (1 to 10).foreach { i =>
        actor ! wrap(i, buy(pair, 100000000, 0.00041))
      }
      tp.receiveN(10)

      actor ! SaveSnapshot(10)
      tp.expectMsgType[OrderBookSnapshotUpdateCompleted]
    }
  }
}
