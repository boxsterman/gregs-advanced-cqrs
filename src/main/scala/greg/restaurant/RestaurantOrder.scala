package greg.restaurant

import java.net.InetSocketAddress
import java.util.UUID

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import eventstore.tcp.ConnectionActor
import eventstore.{EsException, EventNumber, EventStream, ReadEvent, ReadEventCompleted, Settings, UserCredentials}
import play.api.libs.json._

import scala.collection.mutable
import scala.reflect.ClassTag
import scala.util.Failure

case class LineItem(name: String, qty: Int)

trait Message {
  val msgId = UUID.randomUUID().toString
  val corrId: String
  val causeId: String
}

case class CookFood(order: RestaurantOrder, corrId: String, causeId: String, ttl: Long = System.currentTimeMillis() + 3000) extends Message with HaveTTL
case class PriceOrder(order: RestaurantOrder, corrId: String, causeId: String) extends Message
case class TakePayment(order: RestaurantOrder, corrId: String, causeId: String) extends Message

case class OrderPlaced(order: RestaurantOrder, corrId: String, causeId: String, ttl: Long = System.currentTimeMillis() + 3000) extends Message with HaveTTL
case class FoodCooked(order: RestaurantOrder, corrId: String, causeId: String) extends Message
case class OrderPriced(order: RestaurantOrder, corrId: String, causeId: String) extends Message
case class OrderPaid(order: RestaurantOrder, corrId: String, causeId: String) extends Message

trait Handler[T <: Message] {
  def handle(t: T)
}

trait Startable {
  def start
}

trait HaveTTL {
  def ttl: Long
}

trait CanPublish {
  def publish[T <: Message](event: T)(implicit ct: ClassTag[T])
}

trait CanSubscribe {
  def subscribe[T <: Message](handler: Handler[T])(implicit ct: ClassTag[T])
  def subscribe[T <: Message](name: String, handler: Handler[T])
}

class TopicBasedPubSub extends CanPublish with CanSubscribe{
  var subscribers: Map[String, List[Handler[_ <: Message]]] = Map()

  def publish[T <: Message](event: T)(implicit ct: ClassTag[T]): Unit = {
    _publish(event.corrId, event)
    _publish(ct.runtimeClass.getSimpleName, event)
  }

  def _publish[T <: Message](topic: String, event: T)(implicit ct: ClassTag[T]): Unit = {
    val p = subscribers.getOrElse(topic ,Nil).map(x => x)
    p.foreach{ handler =>
      handler.asInstanceOf[Handler[T]].handle(event)
    }
  }

  def subscribe[T <: Message](handler: Handler[T])(implicit ct: ClassTag[T]): Unit =
    _subscribe(ct.runtimeClass.getSimpleName, handler)

  def subscribe[T <: Message](name: String, handler: Handler[T]): Unit =
    _subscribe(name, handler)

  def _subscribe[T <: Message](name: String, handler: Handler[T]): Unit =
    subscribers.synchronized {
      subscribers = subscribers + (name -> (handler :: subscribers.getOrElse(name, Nil)))
    }
}

class MidgetHouse(pub: CanPublish, sub: CanSubscribe) extends Handler[OrderPlaced] {

  var midgetToCorrId: Map[String, Midget] = Map()

  class Midget(pub: CanPublish, op: OrderPlaced, completeHandler: Midget => Unit) extends Handler[Message] {
    val corrId = op.corrId
    pub.publish(CookFood(op.order, op.corrId, op.msgId))

    def handle(t: Message): Unit = t match {
      case m: FoodCooked => pub.publish(PriceOrder(m.order, m.corrId, m.msgId))
      case m: OrderPriced => pub.publish(TakePayment(m.order, m.corrId, m.msgId))
      case m: OrderPaid => onComplete(this)
      case _ =>
    }
  }

  def onComplete(m: Midget) = {
    println(s"Midget completed: ${m.corrId}")
    midgetToCorrId = midgetToCorrId - m.corrId
  }

  def handle(t: OrderPlaced): Unit = {
    println(s"Creating new midget for ${t.corrId}")
    val midget = new Midget(pub, t, onComplete)
    midgetToCorrId = midgetToCorrId + (t.corrId -> midget)
    sub.subscribe(t.corrId, new Handler[Message] {
      def handle(t: Message): Unit = midget.handle(t)
    })
  }

}



object EventStorePubSub extends CanPublish with CanSubscribe {

  val system = ActorSystem()
  val settings = Settings(
    address = new InetSocketAddress("127.0.0.1", 1113),
    defaultCredentials = Some(UserCredentials("admin", "changeit")))

  val connection = system.actorOf(ConnectionActor.props(settings))
  implicit val readResult = system.actorOf(Props[ReadResult])

  connection ! ReadEvent(EventStream.Id("my-stream"), EventNumber.First)

  class ReadResult extends Actor with ActorLogging {
    def receive = {
      case ReadEventCompleted(event) =>
        log.info("event: {}", event)
        context.system.terminate()

      case Failure(e: EsException) =>
        log.error(e.toString)
        context.system.terminate()
    }
  }


  def publish[T <: Message](event: T)(implicit ct: ClassTag[T]): Unit = ???

  def subscribe[T <: Message](handler: Handler[T])(implicit ct: ClassTag[T]): Unit = ???

  def subscribe[T <: Message](name: String, handler: Handler[T]): Unit = ???
}

class OrderTracer extends Handler[Message] {
  def handle(t: Message): Unit = println(s"Trace: ${t.corrId} => $t")
}

class OrderPrinter extends Handler[Message] {

  def handle(t: Message): Unit = println(s"Printer: $t")

//  def handleOrder(order: RestaurantOrder): Unit =
//    println(RestaurantOrder.asJsonString(order))
}

class CountingHandler(name: String) extends Handler[OrderPaid] {

  var count = 0

  def handle(t: OrderPaid): Unit =  {
    count = count + 1
    println(s"Counting $name: $count for new order $t")
  }
}

class NullHandler extends Handler[Message] {
  def handle(t: Message): Unit = {}
}

class TTLHandler[T <: Message](handler: Handler[T]) extends Handler[T] {

  def handle(t: T): Unit = t match {
    case e: HaveTTL if e.ttl > System.currentTimeMillis() =>
      handler.handle(t)
    case e: HaveTTL =>
      println(s"Dropping order ${e.msgId}")
    case e =>
      handler.handle(e)
  }
}


class ThreadedHandler[T <: Message](handler: Handler[T], val name: String = "n/a") extends Handler[T] with Startable {

  val mailbox = new java.util.concurrent.ConcurrentLinkedQueue[T]

  def count = mailbox.size

  def start: Unit = {
    new Thread(new Runnable {
      def run(): Unit = {
        while(true) {
          if(mailbox.isEmpty) Thread.sleep(1000) else {
            val event = mailbox.poll()
//            println(s"$name dequeued ${order.id}, mailbox=${mailbox}")
            try {
              handler.handle(event)
            } catch {
              case e: Throwable => println(s"Order $event failed with Throwable $e")
            }
          }
        }
      }
    }).start()
  }


  def handle(event: T): Unit = {
    mailbox.offer(event)
  }
}

class Multiplexer[T <: Message](handleOrders: List[Handler[T]]) extends Handler[T] {

  def handle(t: T): Unit = handleOrders.foreach(_.handle(t))
}

class RoundRobin[T <: Message](handleOrders: List[Handler[T]]) extends Handler[T] {
  val queue = mutable.Queue[Handler[T]]()
  queue ++= handleOrders

  def handle(t: T): Unit = {
    val h = queue.dequeue()
    try {
      h.handle(t)
    } finally {
      queue.enqueue(h)
    }
  }
}

class MFDispatcher[T <: Message](handlers: List[ThreadedHandler[T]]) extends Handler[T] {

  def handle(t: T): Unit =
    handlers.find(th => th.count < 5).fold{
      Thread.sleep(100)
      handle(t)
    }(th => th.handle(t))
}


// aka "the publisher"
class Waiter(publisher: CanPublish) {
  def placeOrder(orderId: UUID, tableNumber: Int, lineItems: List[LineItem]): UUID = {
    val newOrder = RestaurantOrder.newOrder(orderId).tableNumber(tableNumber).lineItems(lineItems)
    println(s"${this.getClass.getSimpleName}: Place order ${newOrder.id}")
    publisher.publish(OrderPlaced(newOrder, newOrder.id.toString, "initial"))
    newOrder.id
  }
}

// aka "the enricher"
class Cook(publisher: CanPublish, cookingTimeInMillis: Long = 1000, name: String = "Unnamed") extends Handler[CookFood] {

  val cookbook: Map[String, List[String]] = Map(
    "Steak" -> List("A really good piece of meat", "olive oil", "pepper", "salt")
  )

  def handle(t: CookFood): Unit =  {
    val order = t.order
    println(s"${this.getClass.getSimpleName}:$name: Starting cooking ${order.id}")
    Thread.sleep(cookingTimeInMillis)

    val ingredients: List[String] = order.lineItems.flatMap(li => cookbook.get(li.name) match {
      case None => throw new RuntimeException(s"Can not cook: ${li.name}")
      case Some(is) => is
    })
    publisher.publish(FoodCooked(order.ingredients(ingredients), t.corrId, t.msgId))
  }
}

// aka "the enricher"
class AssistantManager(bus: CanPublish) extends Handler[PriceOrder] {

  val priceList: Map[String, Int] = Map("Steak" -> 34)

  def handle(t: PriceOrder): Unit = {
    val order = t.order
    println(s"${this.getClass.getSimpleName}: Calculate ${order.id}")
    Thread.sleep(500)
    val total = order.lineItems.foldLeft(0)((t, li) => t + (priceList.get(li.name) match {
      case None => throw new RuntimeException(s"Can not price item: ${li.name}")
      case Some(x) => x
    }) * li.qty)
    val taxes = total * 0.14
    bus.publish(OrderPriced(order.total(total).tax(taxes), t.corrId, t.msgId))
  }
}

// aka "the controller"
class Cashier(publisher: CanPublish) extends Handler[TakePayment] {

  var pendingOrders: Map[UUID, TakePayment] = Map()

  def ordersToPay = pendingOrders.keys.toList

  def handle(t: TakePayment): Unit = {
    println(s"${this.getClass.getSimpleName}: Ready for payment ${t.order.id}")
    pendingOrders = pendingOrders + (t.order.id -> t)
  }

  def paid(orderId: UUID): Boolean = {
    if(pendingOrders.contains(orderId)) {
      val takePayment = pendingOrders(orderId)
      println(s"${this.getClass.getSimpleName}: Paying $orderId")
      pendingOrders = pendingOrders - orderId
      publisher.publish(OrderPaid(takePayment.order.paid(true), takePayment.corrId, takePayment.msgId))
      true
    } else {
      false
    }
  }
}

class RestaurantOrder(val json: JsObject = JsObject(List("id" -> JsString(UUID.randomUUID().toString))), val ttl: Long = System.currentTimeMillis() + 3000) extends HaveTTL {

  implicit val lineItemFormat = Json.format[LineItem]

  def tableNumber: Int = (json \ "tableNumber").as[Int]
  def tableNumber(t: Int): RestaurantOrder = {
    val newJson = json ++ JsObject(List("tableNumber" -> JsNumber(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }
  def total: Double = (json \ "total").as[Double]
  def total(t: Double): RestaurantOrder = {
    val newJson = json ++ JsObject(List("total" -> JsNumber(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }
  def tax: Double = (json \ "tax").as[Double]
  def tax(t: Double): RestaurantOrder = {
    val newJson = json ++ JsObject(List("tax" -> JsNumber(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }
  def paid: Boolean = (json \ "paid").as[Boolean]
  def paid(t: Boolean): RestaurantOrder = {
    val newJson = json ++ JsObject(List("paid" -> JsBoolean(t)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }

  def lineItems: List[LineItem] = (json \ "lineItems").as[List[LineItem]]
  def lineItems(items: List[LineItem]): RestaurantOrder = {
    val newJson = json ++ JsObject(List("lineItems" -> Json.toJson(items)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }

  def ingredients: List[String] = (json \ "ingredients").as[List[String]]
  def ingredients(items: List[String]): RestaurantOrder = {
    val newJson = json ++ JsObject(List("ingredients" -> Json.toJson(items)))
    RestaurantOrder.fromJsonString(Json.prettyPrint(newJson))
  }

  def id: UUID = UUID.fromString((json \ "id").as[String])

}

object RestaurantOrder {
  def newOrder(id: UUID) = new RestaurantOrder(JsObject(List("id" -> JsString(id.toString))))
  def fromJsonString(jsonString: String) = new RestaurantOrder(Json.parse(jsonString).as[JsObject])
  def asJsonString(order: RestaurantOrder) = Json.prettyPrint(order.json)
}
