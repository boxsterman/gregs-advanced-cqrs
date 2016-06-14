package greg.restaurant

import java.util.UUID

import play.api.libs.json._

import scala.collection.mutable
import scala.reflect.ClassTag

case class LineItem(name: String, qty: Int)

trait Message {
  val id = UUID.randomUUID()
}

case class CookFood(order: RestaurantOrder, ttl: Long = System.currentTimeMillis() + 3000) extends Message with HaveTTL
case class PriceOrder(order: RestaurantOrder) extends Message
case class TakePayment(order: RestaurantOrder) extends Message

case class OrderPlaced(order: RestaurantOrder, ttl: Long = System.currentTimeMillis() + 3000) extends Message with HaveTTL
case class OrderCooked(order: RestaurantOrder) extends Message
case class OrderPriced(order: RestaurantOrder) extends Message
case class OrderPaid(order: RestaurantOrder) extends Message

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
}

class TopicBasedPubSub extends CanPublish with CanSubscribe{
  var subscribers: Map[String, List[Handler[_ <: Message]]] = Map()

  def publish[T <: Message](event: T)(implicit ct: ClassTag[T]): Unit = {
    subscribers.getOrElse(ct.runtimeClass.getSimpleName ,Nil).foreach{ handler =>
      handler.asInstanceOf[Handler[T]].handle(event)
    }
  }

  def subscribe[T <: Message](handler: Handler[T])(implicit ct: ClassTag[T]): Unit =
    subscribers.synchronized {
      val name = ct.runtimeClass.getSimpleName
      subscribers = subscribers + (name -> (handler :: subscribers.getOrElse(name, Nil)))
    }
}

//object EventStorePubSub extends CanPublish with CanSubscribe {
//
//  val system = ActorSystem()
//  val settings = Settings(
//    address = new InetSocketAddress("127.0.0.1", 1113),
//    defaultCredentials = Some(UserCredentials("admin", "changeit")))
//
//  val connection = system.actorOf(ConnectionActor.props(settings))
////  implicit val readResult = system.actorOf(Props[ReadResult])
//
//  connection ! ReadEvent(EventStream.Id("my-stream"), EventNumber.First)
//
//
//
//
//  def publish[T <: Message](event: T)(implicit ct: ClassTag[T]): Unit = ???
//
//  def subscribe[T <: Message](handler: Handler[T])(implicit ct: ClassTag[T]): Unit = ???
//}







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
      println(s"Dropping order ${e.id}")
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
  def placeOrder(tableNumber: Int, lineItems: List[LineItem]): UUID = {
    val newOrder = new RestaurantOrder().tableNumber(tableNumber).lineItems(lineItems)
    println(s"${this.getClass.getSimpleName}: Place order ${newOrder.id}")
    publisher.publish(OrderPlaced(newOrder))
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
    publisher.publish(OrderCooked(order.ingredients(ingredients)))
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
    bus.publish(OrderPriced(order.total(total).tax(taxes)))
  }
}

// aka "the controller"
class Cashier(publisher: CanPublish) extends Handler[TakePayment] {

  var pendingOrders: Map[UUID, RestaurantOrder] = Map()

  def ordersToPay = pendingOrders.keys.toList

  def handle(t: TakePayment): Unit = {
    println(s"${this.getClass.getSimpleName}: Ready for payment ${t.order.id}")
    pendingOrders = pendingOrders + (t.order.id -> t.order)
  }

  def paid(orderId: UUID): Boolean = {
    if(pendingOrders.contains(orderId)) {
      val order = pendingOrders(orderId)
      println(s"${this.getClass.getSimpleName}: Paying $orderId")
      pendingOrders = pendingOrders - orderId
      publisher.publish(OrderPaid(order.paid(true)))
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
  def fromJsonString(jsonString: String) = new RestaurantOrder(Json.parse(jsonString).as[JsObject])
  def asJsonString(order: RestaurantOrder) = Json.prettyPrint(order.json)
}
